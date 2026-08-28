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
import java.util.concurrent.ConcurrentHashMap

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
     * The last "unavailable" reason recorded per (event, transport).
     *
     * A transport that reports itself unavailable was never tried, so it does
     * not advance the event's attempt count and therefore does not consume
     * backoff. Without this memo the loop would append an identical audit row
     * for every transport on every pass - roughly one row per transport per
     * loop interval, growing without bound during exactly the long offline
     * stretch the product exists for, and burying the informative rows.
     *
     * The first occurrence is recorded, repeats are suppressed until the reason
     * changes, and availability is still re-checked on every pass - so delivery
     * resumes the moment a transport comes back.
     *
     * Concurrent because [deliver] is public and therefore not guaranteed to be
     * called under [passLock].
     */
    private val lastUnavailable = ConcurrentHashMap<Pair<String, TransportKind>, String>()

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
            // Each emergency is isolated. One that cannot even be attempted
            // must never abort the pass, because the events behind it in the
            // queue would then never be tried either - an unbounded outage
            // caused by a single bad record.
            val outcome = runCatching { deliver(event) }
            outcome.onFailure { error ->
                log(
                    "could not attempt ${event.eventId.take(8)}: " +
                        "${error.message ?: error.javaClass.simpleName} - event retained, still queued"
                )
            }
            if (outcome.getOrNull()?.state == DeliveryState.DELIVERED) delivered++ else queued++
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

        var current = recoverIfInterrupted(event)

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
                if (recordUnavailable(current, transport, "not available")) {
                    log("${transport.kind.label} unavailable for ${current.eventId.take(8)}")
                }
                continue
            }

            // The transport is back. Forget the suppressed reason so a later
            // outage is recorded afresh rather than silently deduplicated.
            lastUnavailable.remove(current.eventId to transport.kind)

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
                    lastUnavailable.keys.removeAll { it.first == current.eventId }
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

    // ---------------------------------------------------------------- recovery

    /**
     * Return an emergency that was interrupted mid-flight to the retryable
     * resting state, so it can be delivered rather than being stuck forever.
     *
     * An attempt is written to disk BEFORE the transport is called, because an
     * attempt recorded only after the fact cannot be audited if the phone dies
     * during it. The consequence is that DELIVERY_ATTEMPT is the on-disk state
     * for the whole time a transport is working - a five-second connect
     * timeout, a twenty-second advertising window. Being killed inside that
     * window is the likely case, not the unlucky one.
     *
     * Such an event is still pending, so the next launch picks it up, and
     * QUEUED_OFFLINE is the only state a delivery attempt may begin from.
     * Without this step the pass threw on it every three seconds forever and
     * the emergency was never delivered - which is the product's central
     * promise, broken by exactly the interruption the product exists to
     * survive.
     *
     * Recovery happens here rather than only at startup so it covers every
     * entry point into delivery. The interruption is recorded rather than
     * quietly repaired: the attempt did happen, and its outcome is genuinely
     * unknown.
     */
    private suspend fun recoverIfInterrupted(event: EmergencyEvent): EmergencyEvent {
        if (event.state == DeliveryState.QUEUED_OFFLINE || event.state == DeliveryState.RELAYED) {
            return event
        }

        val interruptedTransport = event.activeTransport
        val recovered = event.transitionTo(
            DeliveryState.QUEUED_OFFLINE,
            at = clock.now(),
            error = interruptedTransport?.let {
                "${it.label}: interrupted before the outcome was known"
            },
        )
        store.update(recovered)

        if (interruptedTransport != null) {
            // Only a real attempt gets an audit row. An emergency interrupted
            // before it was ever queued had no transport and nothing to report.
            recordAttempt(
                event = recovered,
                transport = interruptedTransport,
                outcome = AttemptOutcome.INTERRUPTED,
                detail = "attempt ${event.attemptCount} interrupted by shutdown; outcome unknown",
            )
            log(
                "RECOVERED ${event.eventId.take(8)} - attempt ${event.attemptCount} via " +
                    "${interruptedTransport.label} was interrupted; back in the retry queue"
            )
        } else {
            log("RECOVERED ${event.eventId.take(8)} - confirmed but never queued; back in the retry queue")
        }
        return recovered
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

    /**
     * Record that [transport] was skipped, unless the identical reason is
     * already the last thing recorded for this event and transport.
     *
     * @return true if a new audit row was written, so the caller can suppress a
     *         duplicate log line too.
     */
    private suspend fun recordUnavailable(
        event: EmergencyEvent,
        transport: Transport,
        reason: String,
    ): Boolean {
        val key = event.eventId to transport.kind
        if (lastUnavailable[key] == reason) return false
        lastUnavailable[key] = reason
        recordAttempt(event, transport, AttemptOutcome.UNAVAILABLE, reason)
        return true
    }

    private suspend fun recordAttempt(
        event: EmergencyEvent,
        transport: Transport,
        outcome: AttemptOutcome,
        detail: String?,
    ) = recordAttempt(event, transport.kind, outcome, detail)

    private suspend fun recordAttempt(
        event: EmergencyEvent,
        transport: TransportKind,
        outcome: AttemptOutcome,
        detail: String?,
    ) {
        store.appendAttempt(
            DeliveryAttempt(
                eventId = event.eventId,
                at = clock.now(),
                transport = transport,
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
