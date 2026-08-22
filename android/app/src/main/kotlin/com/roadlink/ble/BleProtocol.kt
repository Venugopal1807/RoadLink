package com.roadlink.ble

import com.roadlink.domain.Canonical
import com.roadlink.domain.EmergencyEvent
import java.util.UUID

/**
 * The RoadLink BLE wire protocol.
 *
 * Byte-for-byte identical to the protocol in `:spike-ble`, deliberately. The
 * spike remains the S0-S5 diagnostic harness and is not being modified before
 * hardware validation, so the two implementations must stay interoperable -
 * that also means a product device and a spike device can be paired during
 * bring-up to isolate which side of a failure is at fault.
 *
 * Protocol decisions carried over unchanged from
 * docs/phase1-spike-plan.md §F:
 *
 *  - The advertisement is a beacon/trigger ONLY. It carries no rider identity,
 *    no coordinates and not the raw event_id.
 *  - The GATT connection carries the actual SOS packet and the acknowledgement.
 *  - Correlation across the air uses `event_ref`, a truncated one-way hash.
 */
object RoadLinkUuids {

    /** RoadLink Emergency Service. Advertised so a relay can hardware-filter for it. */
    val SERVICE: UUID = UUID.fromString("5a746561-2f72-41ee-b188-1b938007e52a")

    /** The signed SOS packet. READ, <= 512 bytes. Served by the rider. */
    val CHAR_SOS_PACKET: UUID = UUID.fromString("56d053bc-a578-44e4-8abe-4df8b607ec7a")

    /** Acknowledgement. WRITE with response. Written by the relay. */
    val CHAR_ACK: UUID = UUID.fromString("222881b9-b12a-46fb-a11e-b5fb6e9389dc")

    /** Peripheral self-report, used during bring-up. READ. */
    val CHAR_META: UUID = UUID.fromString("9c425ae0-66bd-4dea-8ee5-9d87b1698cce")

    /** Standard Client Characteristic Configuration Descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Bluetooth SIG company identifier reserved for testing and development.
     * A real deployment requires a SIG member ID. Documented shortcut.
     */
    const val COMPANY_ID: Int = 0xFFFF
}

/**
 * The 13-byte beacon carried in the BLE scan response.
 *
 * ```
 *   offset  size  field
 *   0       1     schemaVersion
 *   1       1     flags   bit0 simulated, bit1 needsGatt,
 *                         bit2 hasLocation, bit3 ackWanted
 *   2       8     eventRef   first 8 bytes of SHA-256(event_id)
 *   10      1     confidence 0..100
 *   11      2     ageSeconds uint16 big-endian, saturating
 * ```
 *
 * `eventRef` is a truncated hash rather than the event_id for two reasons: a
 * passive eavesdropper must not be able to correlate a broadcast with a backend
 * record, and Android randomises the advertising MAC so a scanner cannot
 * deduplicate on address.
 *
 * `simulated` is carried here as well as in the signed packet so a relay can
 * tell what it is picking up before it connects.
 */
data class Beacon(
    val schemaVersion: Int = SCHEMA_VERSION,
    val simulated: Boolean,
    val needsGatt: Boolean = true,
    val hasLocation: Boolean,
    val ackWanted: Boolean = true,
    val eventRef: ByteArray,
    val confidence: Int,
    val ageSeconds: Int,
) {
    init {
        require(eventRef.size == EVENT_REF_BYTES) {
            "eventRef must be $EVENT_REF_BYTES bytes, was ${eventRef.size}"
        }
        require(confidence in 0..100) { "confidence out of range: $confidence" }
    }

    fun encode(): ByteArray {
        val out = ByteArray(SIZE)
        out[0] = schemaVersion.toByte()

        var flags = 0
        if (simulated) flags = flags or FLAG_SIMULATED
        if (needsGatt) flags = flags or FLAG_NEEDS_GATT
        if (hasLocation) flags = flags or FLAG_HAS_LOCATION
        if (ackWanted) flags = flags or FLAG_ACK_WANTED
        out[1] = flags.toByte()

        System.arraycopy(eventRef, 0, out, 2, EVENT_REF_BYTES)
        out[10] = confidence.toByte()

        val age = ageSeconds.coerceIn(0, 0xFFFF)
        out[11] = ((age shr 8) and 0xFF).toByte()
        out[12] = (age and 0xFF).toByte()
        return out
    }

    fun refHex(): String = eventRef.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Beacon) return false
        return schemaVersion == other.schemaVersion &&
            simulated == other.simulated &&
            needsGatt == other.needsGatt &&
            hasLocation == other.hasLocation &&
            ackWanted == other.ackWanted &&
            eventRef.contentEquals(other.eventRef) &&
            confidence == other.confidence &&
            ageSeconds == other.ageSeconds
    }

    override fun hashCode(): Int {
        var result = schemaVersion
        result = 31 * result + simulated.hashCode()
        result = 31 * result + needsGatt.hashCode()
        result = 31 * result + hasLocation.hashCode()
        result = 31 * result + ackWanted.hashCode()
        result = 31 * result + eventRef.contentHashCode()
        result = 31 * result + confidence
        result = 31 * result + ageSeconds
        return result
    }

    companion object {
        const val SIZE = 13
        const val EVENT_REF_BYTES = 8
        const val SCHEMA_VERSION = 1

        const val FLAG_SIMULATED = 0x01
        const val FLAG_NEEDS_GATT = 0x02
        const val FLAG_HAS_LOCATION = 0x04
        const val FLAG_ACK_WANTED = 0x08

        /** Mirrors Canonical.eventRef and the backend's canonical.event_ref. */
        fun eventRefOf(eventId: String): ByteArray = Canonical.eventRef(eventId)

        /** Build the beacon that announces [event], without leaking anything about it. */
        fun forEvent(event: EmergencyEvent, now: Long): Beacon = Beacon(
            simulated = event.simulated,
            needsGatt = true,
            hasLocation = event.hasLocation,
            ackWanted = true,
            eventRef = eventRefOf(event.eventId),
            confidence = event.confidence,
            ageSeconds = event.ageSeconds(now),
        )

        fun decode(raw: ByteArray?): Beacon? {
            if (raw == null || raw.size < SIZE) return null
            val flags = raw[1].toInt() and 0xFF
            val confidence = raw[10].toInt() and 0xFF
            if (confidence > 100) return null
            return Beacon(
                schemaVersion = raw[0].toInt() and 0xFF,
                simulated = flags and FLAG_SIMULATED != 0,
                needsGatt = flags and FLAG_NEEDS_GATT != 0,
                hasLocation = flags and FLAG_HAS_LOCATION != 0,
                ackWanted = flags and FLAG_ACK_WANTED != 0,
                eventRef = raw.copyOfRange(2, 2 + EVENT_REF_BYTES),
                confidence = confidence,
                ageSeconds = ((raw[11].toInt() and 0xFF) shl 8) or (raw[12].toInt() and 0xFF),
            )
        }
    }
}

/**
 * The acknowledgement a relay writes back once it has the packet safely stored.
 *
 * ```
 *   ACK|<event_id>|<relay_id>|<epoch_millis>
 * ```
 *
 * The spike's ACK carried only the relay id and a timestamp, which was enough
 * to prove the round trip but not to correlate. The event_id is added here
 * because the rider may be advertising one emergency while holding several,
 * and must mark exactly the right one as relayed. Sending it is safe: this
 * travels over the connected GATT link, never in the open advertisement.
 */
data class BleAck(
    val eventId: String,
    val relayId: String,
    val atMillis: Long,
) {
    fun encode(): ByteArray = "$PREFIX|$eventId|$relayId|$atMillis".toByteArray(Charsets.UTF_8)

    companion object {
        const val PREFIX = "ACK"

        fun decode(raw: ByteArray?): BleAck? {
            if (raw == null || raw.isEmpty()) return null
            val parts = String(raw, Charsets.UTF_8).split("|")
            if (parts.size < 4 || parts[0] != PREFIX) return null
            val at = parts[3].toLongOrNull() ?: return null
            if (parts[1].isBlank() || parts[2].isBlank()) return null
            return BleAck(eventId = parts[1], relayId = parts[2], atMillis = at)
        }
    }
}
