package com.roadlink.net

import com.roadlink.domain.Canonical
import com.roadlink.domain.EmergencyEvent
import com.roadlink.domain.TransportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.math.BigDecimal
import java.math.RoundingMode
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
) {

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

    /** Liveness check. The device uses this to decide whether it is really online. */
    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
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
    suspend fun submit(
        event: EmergencyEvent,
        via: TransportKind,
        relayId: String? = null,
        relayReceivedAt: Long? = null,
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
        val packet = buildString {
            append("{")
            append("\"v\":").append(Canonical.SCHEMA_VERSION)
            append(",\"event_id\":").append(quote(event.eventId))
            append(",\"rider_id\":").append(quote(event.riderId))
            append(",\"created_at\":").append(event.createdAt)
            append(",\"lat\":").append(coord(event.lat))
            append(",\"lng\":").append(coord(event.lng))
            append(",\"acc_m\":").append(event.accuracyMetres?.toString() ?: "null")
            append(",\"conf\":").append(event.confidence)
            append(",\"trigger\":[")
            append(event.triggers.sorted().joinToString(",") { quote(it) })
            append("]")
            // Never omitted, and never inferred by the receiver.
            append(",\"simulated\":").append(event.simulated)
            append(",\"sig\":").append(event.signature?.let { quote(it) } ?: "null")
            append("}")
        }

        val delivery = buildString {
            append("{\"path\":").append(quote(via.wireName))
            if (relayId != null) append(",\"relay_id\":").append(quote(relayId))
            if (relayReceivedAt != null) append(",\"relay_received_at\":").append(relayReceivedAt)
            append("}")
        }

        return "{\"packet\":$packet,\"delivery\":$delivery}"
    }

    private fun coord(value: Double?): String {
        if (value == null) return "null"
        val rounded = BigDecimal(value).setScale(7, RoundingMode.HALF_EVEN)
        val text = rounded.toPlainString()
        val isNegativeZero = rounded.signum() == 0 && (value < 0.0 || 1.0 / value < 0.0)
        return if (isNegativeZero) "-$text" else text
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
