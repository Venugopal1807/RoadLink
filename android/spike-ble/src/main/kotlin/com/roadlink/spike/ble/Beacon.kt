package com.roadlink.spike.ble

import java.security.MessageDigest

/**
 * The 13-byte RoadLink beacon carried in the BLE scan response.
 *
 * Layout (see docs/phase1-spike-plan.md F.3):
 *
 *   offset  size  field
 *   0       1     schemaVersion
 *   1       1     flags      bit0 simulated, bit1 needsGatt,
 *                            bit2 hasLocation, bit3 ackWanted
 *   2       8     eventRef   first 8 bytes of SHA-256(event_id)
 *   10      1     confidence 0..100
 *   11      2     ageSeconds uint16 big-endian, saturating
 *
 * Two deliberate choices:
 *
 * 1. eventRef is a TRUNCATED HASH, not the event_id. A passive eavesdropper
 *    cannot correlate a broadcast against a backend record.
 * 2. It exists at all because Android randomises the BLE advertising address,
 *    so a scanner cannot deduplicate on MAC.
 *
 * Nothing identifying the rider and no coordinates appear here. That is the
 * privacy invariant, and test T11 verifies it by dumping the raw bytes.
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

    // Generated because the class has an ByteArray property.
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

        /** Mirrors canonical.event_ref() in the backend. Must stay byte-identical. */
        fun eventRefOf(eventId: String): ByteArray =
            MessageDigest.getInstance("SHA-256")
                .digest(eventId.toByteArray(Charsets.UTF_8))
                .copyOf(EVENT_REF_BYTES)

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
