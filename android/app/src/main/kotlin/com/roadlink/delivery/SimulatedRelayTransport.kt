package com.roadlink.delivery

import com.roadlink.domain.Clock
import com.roadlink.domain.EmergencyEvent
import com.roadlink.domain.TransportKind
import com.roadlink.net.SosApi
import kotlinx.coroutines.delay

/**
 * DEVELOPMENT ONLY. A deterministic stand-in for a second physical phone.
 *
 * WHAT IS SIMULATED AND WHAT IS NOT - this distinction matters and is not
 * allowed to blur:
 *
 *   SIMULATED : the relay itself. Whether a nearby phone exists, whether it
 *               is in range, whether the handoff succeeds, how long discovery
 *               and transfer take. None of this involves a radio.
 *   REAL      : everything after the relay accepts the event. The forward to
 *               the backend is a genuine HTTP request, the backend genuinely
 *               stores it, and the audit trail is genuine.
 *
 * This transport therefore proves the pipeline - state machine, persistence,
 * retry, idempotency, responder rendering - and proves NOTHING about BLE.
 * Every delivery it makes is recorded against the wire path `simulated_relay`,
 * never `ble_relay`, so no simulated hop can ever be mistaken for physical
 * evidence in the data we intend to quote.
 *
 * The scripted knobs below exist so the scenarios in the demo are reproducible
 * rather than dependent on luck.
 */
class SimulatedRelayTransport(
    private val api: SosApi,
    private val clock: Clock = Clock.System,
    private val log: (String) -> Unit = {},
) : Transport {

    override val kind: TransportKind = TransportKind.SIMULATED_RELAY

    /** Scenario A/B: is there a relay phone nearby at all? */
    var relayInRange: Boolean = true

    /** Scenario C: fail this many times before succeeding, then stop failing. */
    var failuresBeforeSuccess: Int = 0

    /** Scenario E: the relay accepted the event but has no connectivity itself. */
    var relayHasNetwork: Boolean = true

    /** Rough stand-ins for BLE discovery and GATT transfer time. */
    var discoveryLatencyMs: Long = 700
    var transferLatencyMs: Long = 400

    /** Stable identity for the imaginary relay device. */
    var relayId: String = "rl_sim_relay_01"

    private var failuresSoFar: Int = 0

    /** The events this simulated relay is currently holding but has not forwarded. */
    private val held = mutableSetOf<String>()

    val heldCount: Int get() = held.size

    /** Reset the script so a demo run starts from a known state. */
    fun reset() {
        failuresSoFar = 0
        held.clear()
    }

    override suspend fun isAvailable(): Boolean = relayInRange

    override suspend fun deliver(event: EmergencyEvent): TransportResult {
        if (!relayInRange) {
            return TransportResult.Unavailable("SIMULATED: no relay phone in range")
        }

        // ---- simulated discovery ------------------------------------------
        if (discoveryLatencyMs > 0) delay(discoveryLatencyMs)
        log("SIMULATED relay $relayId discovered ${event.eventId.take(8)} (no radio involved)")

        // ---- scripted failure ---------------------------------------------
        if (failuresSoFar < failuresBeforeSuccess) {
            failuresSoFar++
            return TransportResult.Failed(
                "SIMULATED: relay handoff failed ($failuresSoFar of $failuresBeforeSuccess scripted failures)"
            )
        }

        // ---- simulated transfer -------------------------------------------
        if (transferLatencyMs > 0) delay(transferLatencyMs)
        val receivedAt = clock.now()
        held += event.eventId
        log("SIMULATED relay accepted ${event.eventId.take(8)} and is holding it")

        // ---- the relay now has the event but may not have connectivity ----
        if (!relayHasNetwork) {
            // The relay keeps holding it. The rider's own copy is untouched and
            // stays queued, which is the whole point: two independent holders.
            return TransportResult.Failed(
                "SIMULATED: relay holds the event but has no connectivity to forward it"
            )
        }

        // ---- REAL forward to the REAL backend ------------------------------
        val response = runCatching {
            api.submit(
                event = event,
                via = kind,
                relayId = relayId,
                relayReceivedAt = receivedAt,
            )
        }.getOrElse { error ->
            return TransportResult.Failed(
                "relay forward failed: ${error.message ?: error.javaClass.simpleName}"
            )
        }

        if (!response.accepted) {
            return TransportResult.Failed("backend rejected forward: HTTP ${response.httpStatus} ${response.raw.take(160)}")
        }

        held -= event.eventId
        return TransportResult.Delivered(
            detail = "forwarded by simulated relay $relayId; backend HTTP ${response.httpStatus}, sig_valid=${response.sigValid}",
            duplicate = response.duplicate,
        )
    }
}

