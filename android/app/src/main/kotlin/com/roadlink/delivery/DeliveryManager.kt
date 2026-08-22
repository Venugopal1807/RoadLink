package com.roadlink.delivery

import com.roadlink.domain.AttemptOutcome
import com.roadlink.domain.Clock
import com.roadlink.domain.DeliveryAttempt
import com.roadlink.domain.DeliveryState
import com.roadlink.domain.EmergencyEvent
import com.roadlink.domain.EmergencyStore
import com.roadlink.domain.TransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the delivery lifecycle of every emergency.
 *
 * Two rules define this class:
 *
 * 1. PERSIST BEFORE TRANSMIT. [submit] writes the event to disk and only
 *    proceeds if that write succeeded. If persistence fails, nothing is
 *    transmitted and the caller is told - because an event that was sent but
 *    not stored cannot be retried, and would be lost the moment delivery
 *    failed downstream.
 *
 * 2. DELIVERY FAILURE IS NEVER TERMINAL. Every failure path returns the event
 *    to QUEUED_OFFLINE. There is no branch anywhere in this file that removes
 *    an event, and the store offers no method that could.
 *
 * Transports are consulted in priority order and are interchangeable. The
 * manager does not know or care whether a delivery happened over BLE, over IP,
 * or through the development simulator - only which [TransportKind] reported
 * success, which it records so the distinction survives to the responder.
 */
class DeliveryManager(
    private val store: EmergencyStore,
    private val transports: List<Transport>,
    private val clock: Clock = Clock.System,
    private val log: (String) -> Unit = {},
) {

    /** Serialises delivery passes so two passes cannot race on the same event. */
    private val passLock = Mutex()

    private var loop: Job? = null

    /**
     * DEVELOPMENT/DEMO ONLY. When set, only this transport is attempted.
     *
     * This exists for one reason: a live demo must be deterministic. Bluetooth,
     * OEM background execution and conference Wi-Fi all fail unpredictably, and
     * the audience should see a product rather than a debugging session. Normal
     * users never see this control - `null` is the real product behaviour,
     * where every transport is tried in priority order.
     */
    @Volatile
    var forcedTransport: TransportKind? = null

    data class PassResult(
        val considered: Int = 0,
        val delivered: Int = 0,
        val stillQueued: Int = 0,
    )

    // ------------------------------------------------------------- submission

    /**
     * Persist a confirmed emergency, then attempt delivery.
     *
     * The ordering here is the product's core invariant and is covered by a
     * dedicated test that asserts the store is written before any transport is
     * touched.
     *
     * @return the event as stored, in QUEUED_OFFLINE.
     * @throws Exception if persistence failed. Delivery is NOT attempted in
     *         that case; an unstored event is worse than a delayed one.
     */
    suspend fun submit(event: EmergencyEvent): EmergencyEvent {
        // ---- step 1: durable local write. Nothing below happens until this
        // ---- returns successfully.
        store.persist(event)
        log("PERSISTED ${event.eventId.take(8)} origin=${event.origin.label} - safe on disk before any delivery attempt")

        // ---- step 2: the event is now safe. Move it to the retryable
        // ---- resting state and let the delivery loop pick it up.
        val queued = event.transitionTo(DeliveryState.QUEUED_OFFLINE, at = clock.now())
        store.update(queued)
        return queued
    }

    // --------------------------------------------------------------- delivery

    /**
     * One delivery pass over every pending event whose backoff has elapsed.
     */
    suspend fun runDeliveryPass(): PassResult = passLock.withLock {
        val pending = store.pending()
        var delivered = 0
        var queued = 0

        for (event in pending) {
            if (!isDue(event)) {
                queued++
                continue
            }
            val result = deliver(event)
            if (result.state == DeliveryState.DELIVERED) delivered++ else queued++
        }
        PassResult(considered = pending.size, delivered = delivered, stillQueued = queued)
    }

    /**
     * Try each transport in priority order until one delivers.
     *
     * A transport that reports itself unavailable is skipped without counting
     * against the event; a transport that tries and fails counts an attempt and
     * the next transport is tried immediately. That cascade is the fallback
     * behaviour - BLE relay unavailable falls through to direct upload, and if
     * neither works the event simply stays queued.
     */
    suspend fun deliver(event: EmergencyEvent): EmergencyEvent {
        if (event.state == DeliveryState.DELIVERED) return event

        var current = event

        // A forced transport narrows the cascade but changes nothing else:
        // same state machine, same persistence, same audit trail.
        val candidates = forcedTransport
            ?.let { forced -> transports.filter { it.kind == forced } }
            ?: transports

        for (transport in candidates) {
            val available = runCatching { transport.isAvailable() }.getOrElse { error ->
                log("${transport.kind.label} availability check threw: ${error.message}")
                false
            }

            if (!available) {
                recordAttempt(current, transport, AttemptOutcome.UNAVAILABLE, "not available")
                log("${transport.kind.label} unavailable for ${current.eventId.take(8)}")
                continue
            }

            current = current.transitionTo(
                DeliveryState.DELIVERY_ATTEMPT,
                transport = transport.kind,
                at = clock.now(),
            )
            store.update(current)
            log("ATTEMPT ${current.eventId.take(8)} via ${transport.kind.label} (${transport.kind.fidelity}) attempt=${current.attemptCount}")

            val result = runCatching { transport.deliver(current) }
                .getOrElse { error -> TransportResult.Failed(error.message ?: error.javaClass.simpleName) }

            when (result) {
                is TransportResult.Delivered -> {
                    recordAttempt(current, transport, AttemptOutcome.SUCCESS, result.detail)
                    current = current.transitionTo(
                        DeliveryState.DELIVERED,
                        transport = transport.kind,
                        at = clock.now(),
                    )
                    store.update(current)
                    log("DELIVERED ${current.eventId.take(8)} via ${transport.kind.label}" + if (result.duplicate) " (backend reported duplicate)" else "")
                    return current
                }

                is TransportResult.HandedOff -> {
                    recordAttempt(
                        current, transport, AttemptOutcome.HANDED_OFF,
                        result.detail ?: "relay ${result.relayId} took custody",
                    )
                    current = current.transitionTo(
                        DeliveryState.RELAYED,
                        transport = transport.kind,
                        at = clock.now(),
                        relayId = result.relayId,
                    )
                    store.update(current)
                    log("RELAYED ${current.eventId.take(8)} to ${result.relayId} via ${transport.kind.label} - NOT yet confirmed at the backend")
                    // Real progress, so stop the cascade here. The event stays
                    // pending and will be retried, which is how the rider still
                    // delivers directly if its own network returns.
                    return current
                }

                is TransportResult.Failed -> {
                    recordAttempt(current, transport, AttemptOutcome.FAILED, result.reason)
                    // Back to the safe retryable state. The event is NOT
                    // dropped, and the next transport still gets a turn.
                    current = current.transitionTo(
                        DeliveryState.QUEUED_OFFLINE,
                        at = clock.now(),
                        error = "${transport.kind.label}: ${result.reason}",
                    )
                    store.update(current)
                    log("FAILED ${current.eventId.take(8)} via ${transport.kind.label}: ${result.reason} - event retained, still queued")
                }

                is TransportResult.Unavailable -> {
                    recordAttempt(current, transport, AttemptOutcome.UNAVAILABLE, result.reason)
                    current = current.transitionTo(
                        DeliveryState.QUEUED_OFFLINE,
                        at = clock.now(),
                        error = "${transport.kind.label}: ${result.reason}",
                    )
                    store.update(current)
                    log("UNAVAILABLE ${current.eventId.take(8)} via ${transport.kind.label}: ${result.reason}")
                }
            }
        }

        // Nothing delivered. The event stays queued and will be retried; this
        // is a normal resting state, not an error.
        if (current.state == DeliveryState.DELIVERY_ATTEMPT) {
            current = current.transitionTo(DeliveryState.QUEUED_OFFLINE, at = clock.now())
            store.update(current)
        }
        return current
    }

    /**
     * Deliberately re-submit an already-delivered event over another transport.
     *
     * This is the designed redundancy, not a bug: the rider's phone and any
     * number of relays may each submit the same event independently, and the
     * backend's idempotency is what makes that safe. Exposed so the behaviour
     * can be demonstrated rather than merely asserted.
     */
    suspend fun redeliver(event: EmergencyEvent, via: Transport): TransportResult {
        val result = runCatching { via.deliver(event) }
            .getOrElse { TransportResult.Failed(it.message ?: it.javaClass.simpleName) }
        val outcome = when (result) {
            is TransportResult.Delivered -> AttemptOutcome.SUCCESS
            is TransportResult.HandedOff -> AttemptOutcome.HANDED_OFF
            is TransportResult.Failed -> AttemptOutcome.FAILED
            is TransportResult.Unavailable -> AttemptOutcome.UNAVAILABLE
        }
        val detail = when (result) {
            is TransportResult.Delivered ->
                (result.detail ?: "redelivered") + if (result.duplicate) " duplicate=true" else ""
            is TransportResult.HandedOff ->
                result.detail ?: "relay ${result.relayId} took custody"
            is TransportResult.Failed -> result.reason
            is TransportResult.Unavailable -> result.reason
        }
        recordAttempt(event, via, outcome, "redelivery: $detail")
        log("REDELIVER ${event.eventId.take(8)} via ${via.kind.label} -> $detail")
        return result
    }

    // ---------------------------------------------------------------- backoff

    /**
     * Exponential backoff: 1s, 2s, 4s, 8s, 16s, capped at 30s.
     *
     * Matches the reconnect backoff already used by the BLE spike's central
     * role, so the two layers behave consistently.
     */
    fun backoffMillis(attemptCount: Int): Long {
        if (attemptCount <= 0) return 0L
        val shift = (attemptCount - 1).coerceAtMost(30)
        return (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    fun isDue(event: EmergencyEvent, now: Long = clock.now()): Boolean {
        if (!event.state.isPending) return false
        val last = event.lastAttemptAt ?: return true
        return now - last >= backoffMillis(event.attemptCount)
    }

    // ------------------------------------------------------------------- loop

    /**
     * Background retry loop. Every queued emergency keeps being retried for as
     * long as the app is alive, and survives on disk when it is not.
     */
    fun start(scope: CoroutineScope, intervalMs: Long = LOOP_INTERVAL_MS) {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                runCatching { runDeliveryPass() }
                    .onFailure { log("delivery pass error: ${it.message}") }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    // ----------------------------------------------------------------- audit

    private suspend fun recordAttempt(
        event: EmergencyEvent,
        transport: Transport,
        outcome: AttemptOutcome,
        detail: String?,
    ) {
        store.appendAttempt(
            DeliveryAttempt(
                eventId = event.eventId,
                at = clock.now(),
                transport = transport.kind,
                outcome = outcome,
                detail = detail,
            )
        )
    }

    private companion object {
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val LOOP_INTERVAL_MS = 3_000L
    }
}
