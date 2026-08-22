package com.roadlink.net

import com.roadlink.domain.EmergencyEvent
import com.roadlink.domain.TransportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin client for the RoadLink ingestion backend.
 *
 * Uses HttpURLConnection and a hand-built JSON body rather than a HTTP or JSON
 * library. The payload is a fixed ten-field schema, so a dependency buys very
 * little, and keeping it dependency-free means this class - including the exact
 * bytes it puts on the wire - is testable on a plain JVM.
 */
class SosApiClient(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) : SosApi {

    /** Liveness check. The device uses this to decide whether it is really online. */
    override suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = open("/healthz", "GET")
            try {
                conn.responseCode == 200
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    /**
     * Submit an emergency.
     *
     * [via] becomes the backend's delivery path. A simulated relay reports
     * `simulated_relay`, never `ble_relay` - fabricated BLE evidence in the
     * audit trail would be worse than no evidence at all.
     */
    override suspend fun submit(
        event: EmergencyEvent,
        via: TransportKind,
        relayId: String?,
        relayReceivedAt: Long?,
    ): IngestResponse = withContext(Dispatchers.IO) {
        val body = buildEnvelope(event, via, relayId, relayReceivedAt)
        val conn = open("/api/v1/sos", "POST")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

            IngestResponse(
                httpStatus = status,
                duplicate = readBool(text, "duplicate") ?: false,
                sigValid = readBool(text, "sig_valid") ?: false,
                state = readString(text, "state"),
                auditEntries = readInt(text, "audit_entries"),
                raw = text,
            )
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun activeEmergencies(): List<ResponderEvent> = withContext(Dispatchers.IO) {
        val conn = open("/api/v1/sos/active", "GET")
        try {
            if (conn.responseCode != 200) return@withContext emptyList()
            val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            parseActive(text)
        } finally {
            conn.disconnect()
        }
    }

    /** Uses org.json, which is part of the Android framework - no dependency added. */
    private fun parseActive(text: String): List<ResponderEvent> = runCatching {
        val events = JSONObject(text).optJSONArray("events") ?: return emptyList()
        (0 until events.length()).mapNotNull { i ->
            val o = events.optJSONObject(i) ?: return@mapNotNull null
            ResponderEvent(
                eventId = o.optString("event_id"),
                riderId = o.optString("rider_id"),
                createdAtDevice = o.optLong("created_at_device"),
                receivedAt = o.optString("received_at").takeIf { it.isNotBlank() },
                lat = if (o.isNull("lat")) null else o.optDouble("lat"),
                lng = if (o.isNull("lng")) null else o.optDouble("lng"),
                accuracyMetres = if (o.isNull("acc_m")) null else o.optInt("acc_m"),
                confidence = o.optInt("conf"),
                triggers = o.optJSONArray("trigger")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                }.orEmpty(),
                simulated = o.optBoolean("simulated"),
                state = o.optString("state"),
                firstDeliveryPath = o.optString("first_delivery_path"),
                firstRelayId = o.optString("first_relay_id").takeIf { it.isNotBlank() && it != "null" },
                signatureValid = o.optBoolean("sig_valid"),
            )
        }
    }.getOrDefault(emptyList())

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
        }

    // ------------------------------------------------------------ serialising

    /**
     * Build the `{packet, delivery}` envelope.
     *
     * Coordinates are emitted at exactly 7 decimal places - the same
     * representation the signature is computed over. That matters: the backend
     * re-derives the canonical string from the parsed JSON, so emitting full
     * double precision here and 7dp in the signature would make every packet
     * verify as invalid.
     */
    internal fun buildEnvelope(
        event: EmergencyEvent,
        via: TransportKind,
        relayId: String?,
        relayReceivedAt: Long?,
    ): String {
        // Exactly the bytes a relay would have carried over GATT. Sharing the
        // codec is what makes a relayed emergency and a directly-uploaded one
        // byte-identical, and therefore verifiable against the same signature.
        val packet = SosPacketCodec.encode(event)

        val delivery = buildString {
            append("{\"path\":").append(quote(via.wireName))
            if (relayId != null) append(",\"relay_id\":").append(quote(relayId))
            if (relayReceivedAt != null) append(",\"relay_received_at\":").append(relayReceivedAt)
            append("}")
        }

        return "{\"packet\":$packet,\"delivery\":$delivery}"
    }

    private fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // ---------------------------------------------------------- deserialising
    // Enough to read the handful of scalar fields the ingest response carries.
    // Not a general JSON parser and not pretending to be one.

    private fun readBool(json: String, key: String): Boolean? =
        Regex("\"$key\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()

    private fun readInt(json: String, key: String): Int? =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()

    private fun readString(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
}
