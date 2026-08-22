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

    /**
     * What the responder can actually see.
     *
     * Read from the backend rather than from local state on purpose: the demo
     * claim is that the SOS *arrived*, and only the server can attest to that.
     * A responder view backed by the device's own database would prove nothing.
     */
    suspend fun activeEmergencies(): List<ResponderEvent>
}

/**
 * An emergency as the backend holds it.
 *
 * `firstDeliveryPath` is the honesty field: it says whether this emergency
 * reached the server over a real BLE relay, a real network upload, or the
 * development simulator, and the responder UI renders that distinction rather
 * than hiding it.
 */
data class ResponderEvent(
    val eventId: String,
    val riderId: String,
    val createdAtDevice: Long,
    val receivedAt: String?,
    val lat: Double?,
    val lng: Double?,
    val accuracyMetres: Int?,
    val confidence: Int,
    val triggers: List<String>,
    val simulated: Boolean,
    val state: String,
    val firstDeliveryPath: String,
    val firstRelayId: String?,
    val signatureValid: Boolean,
) {
    val hasLocation: Boolean get() = lat != null && lng != null

    /** True only when nothing about how this arrived was simulated. */
    val arrivedByRealTransport: Boolean
        get() = firstDeliveryPath == "ble_relay" || firstDeliveryPath == "direct"
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
