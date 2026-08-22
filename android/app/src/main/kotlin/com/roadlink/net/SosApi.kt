package com.roadlink.net

import com.roadlink.domain.EmergencyEvent
import com.roadlink.domain.TransportKind

/**
 * The backend ingestion API, behind an interface so transports can be tested
 * on a plain JVM without a server or a network.
 */
interface SosApi {

    /** Liveness. The device uses this to decide whether it is really online. */
    suspend fun health(): Boolean

    /**
     * Submit an emergency.
     *
     * [via] becomes the backend's delivery path. A simulated relay reports
     * `simulated_relay`, never `ble_relay`.
     */
    suspend fun submit(
        event: EmergencyEvent,
        via: TransportKind,
        relayId: String? = null,
        relayReceivedAt: Long? = null,
    ): IngestResponse
}

data class IngestResponse(
    val httpStatus: Int,
    val duplicate: Boolean,
    val sigValid: Boolean,
    val state: String?,
    val auditEntries: Int?,
    val raw: String,
) {
    val accepted: Boolean get() = httpStatus == 200 || httpStatus == 201
}
