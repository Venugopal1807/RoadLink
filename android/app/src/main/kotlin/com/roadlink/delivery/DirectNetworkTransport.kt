package com.roadlink.delivery

import com.roadlink.domain.EmergencyEvent
import com.roadlink.domain.TransportKind
import com.roadlink.net.SosApi

/**
 * The rider's own phone reaching the backend over IP.
 *
 * This is the transport that makes RoadLink a complete product even if BLE
 * never works: an emergency created with no connectivity waits on disk, and
 * this delivers it the moment the network returns. It is last in the priority
 * order not because it is least important but because it is the one that needs
 * infrastructure the rider may not have.
 */
class DirectNetworkTransport(
    private val api: SosApi,
    private val connectivity: Connectivity,
    private val log: (String) -> Unit = {},
) : Transport {

    override val kind: TransportKind = TransportKind.DIRECT_NETWORK

    /**
     * A transport-level network check, not a reachability promise.
     *
     * Android reporting a validated network is not the same as the backend
     * being reachable - captive portals and a laptop that has moved off the
     * LAN both look "online". [deliver] therefore still handles failure, and
     * this stays cheap rather than doing a probe request on every pass.
     */
    override suspend fun isAvailable(): Boolean = connectivity.isOnline()

    override suspend fun deliver(event: EmergencyEvent): TransportResult {
        if (!connectivity.isOnline()) {
            return TransportResult.Unavailable("no network")
        }

        val response = runCatching {
            api.submit(event = event, via = kind)
        }.getOrElse { error ->
            // Network errors are expected and retryable, never fatal to the event.
            return TransportResult.Failed(
                "upload failed: ${error.message ?: error.javaClass.simpleName}"
            )
        }

        if (!response.accepted) {
            return TransportResult.Failed(
                "backend rejected: HTTP ${response.httpStatus} ${response.raw.take(160)}"
            )
        }

        log("direct upload of ${event.eventId.take(8)} accepted, HTTP ${response.httpStatus}")
        return TransportResult.Delivered(
            detail = "direct upload; backend HTTP ${response.httpStatus}, sig_valid=${response.sigValid}",
            duplicate = response.duplicate,
        )
    }
}

/**
 * Network availability, behind an interface so delivery logic stays testable
 * on a plain JVM and so "offline" can be forced during a demo.
 */
fun interface Connectivity {
    suspend fun isOnline(): Boolean
}

