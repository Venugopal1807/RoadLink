package com.roadlink.net

import com.roadlink.domain.Canonical
import com.roadlink.domain.EmergencyEvent
import com.roadlink.domain.EventOrigin
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The SOS packet's wire representation.
 *
 * One encoder, two carriers: the same bytes are read off a GATT characteristic
 * by a relay and POSTed as the `packet` half of the HTTP envelope. Keeping a
 * single codec is what guarantees a relayed emergency and a directly-uploaded
 * one are byte-identical, which is in turn what lets both verify against the
 * same signature.
 *
 * Encoding is hand-written rather than delegated to org.json because
 * coordinates must appear at exactly 7 decimal places - the precision the
 * signature was computed over. org.json would emit a Double's shortest repr,
 * which round-trips to the same value but is not the same text.
 */
object SosPacketCodec {

    /** GATT characteristic values are capped at 512 bytes by the ATT spec. */
    const val MAX_PACKET_BYTES = 512

    // ------------------------------------------------------------- encoding

    fun encode(event: EmergencyEvent): String = buildString {
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

    fun encodeBytes(event: EmergencyEvent): ByteArray = encode(event).toByteArray(Charsets.UTF_8)

    // ------------------------------------------------------------- decoding

    /**
     * Rebuild an emergency from a packet a relay read over GATT.
     *
     * The ORIGINAL signature is preserved verbatim. A relay never re-signs:
     * it does not hold the rider's key, and re-signing would destroy the only
     * evidence that the packet arrived unmodified.
     *
     * Delivery bookkeeping is deliberately reset - `state`, `attemptCount` and
     * so on describe THIS device's attempt to deliver, not the rider's.
     *
     * Returns null on anything malformed. A relay that cannot parse a packet
     * must drop it rather than forward a guess.
     */
    fun decode(json: String): EmergencyEvent? = runCatching {
        val o = JSONObject(json)

        val eventId = o.getString("event_id")
        val riderId = o.getString("rider_id")
        if (eventId.isBlank() || riderId.isBlank()) return null

        val confidence = o.getInt("conf")
        if (confidence !in 0..100) return null

        // `simulated` is inside the signature, so it cannot have been altered
        // in flight without invalidating the packet.
        val simulated = o.getBoolean("simulated")

        EmergencyEvent(
            eventId = eventId,
            riderId = riderId,
            createdAt = o.getLong("created_at"),
            lat = if (o.isNull("lat")) null else o.getDouble("lat"),
            lng = if (o.isNull("lng")) null else o.getDouble("lng"),
            accuracyMetres = if (o.isNull("acc_m")) null else o.getInt("acc_m"),
            confidence = confidence,
            triggers = o.optJSONArray("trigger").toStringList(),
            origin = if (simulated) EventOrigin.MANUAL_TEST else EventOrigin.REAL_SENSOR,
            signature = if (o.isNull("sig")) null else o.getString("sig"),
        )
    }.getOrNull()

    fun decode(bytes: ByteArray): EmergencyEvent? =
        decode(String(bytes, Charsets.UTF_8))

    /**
     * Does this packet's signature match its contents?
     *
     * A relay verifies before forwarding. An unverifiable packet is dropped,
     * not relayed - forwarding one would let any nearby device inject
     * emergencies into the backend through an honest relay.
     */
    fun verify(event: EmergencyEvent): Boolean {
        val provided = event.signature ?: return false
        return Canonical.sign(event) == provided
    }

    // -------------------------------------------------------------- helpers

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
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
}
