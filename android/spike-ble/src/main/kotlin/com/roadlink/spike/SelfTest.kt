package com.roadlink.spike

import com.roadlink.spike.ble.Beacon

/**
 * Known-answer tests for the wire codecs.
 *
 * event_ref crosses a language boundary: Kotlin computes it for the BLE
 * beacon, Python computes it in the backend (app/canonical.py), and the two
 * must agree byte for byte. A mismatch would be invisible until a relay
 * silently failed to correlate an event, so it is pinned here with vectors
 * generated from the Python implementation on 2026-08-21.
 *
 * Runs at startup and logs to RLSPIKE. Cheap, and it fails loudly.
 */
object SelfTest {

    private const val TEST_EVENT_ID = "00000000-0000-4000-8000-000000000001"

    /** Produced by backend: canonical.event_ref(TEST_EVENT_ID) */
    private const val EXPECTED_EVENT_REF = "11e594f481958c10"

    /**
     * Beacon with confidence=82, age=300s, simulated+needsGatt+ackWanted set.
     *   01        schemaVersion
     *   0b        flags: simulated|needsGatt|ackWanted
     *   11e5..10  eventRef (8 B)
     *   52        confidence = 82
     *   012c      ageSeconds = 300
     */
    private const val EXPECTED_BEACON = "010b11e594f481958c1052012c"

    fun run(): Boolean {
        var passed = 0
        var failed = 0

        fun check(name: String, condition: Boolean, detail: String = "") {
            if (condition) {
                passed++
                SpikeLog.log("  selftest PASS  $name")
            } else {
                failed++
                SpikeLog.logError("  selftest FAIL  $name  $detail")
            }
        }

        SpikeLog.log("running codec self-tests")

        val ref = Beacon.eventRefOf(TEST_EVENT_ID)
        val refHex = ref.joinToString("") { "%02x".format(it) }
        check("event_ref is 8 bytes", ref.size == 8, "was ${ref.size}")
        check(
            "event_ref matches backend vector",
            refHex == EXPECTED_EVENT_REF,
            "expected $EXPECTED_EVENT_REF got $refHex",
        )

        val beacon = Beacon(
            simulated = true,
            needsGatt = true,
            hasLocation = false,
            ackWanted = true,
            eventRef = ref,
            confidence = 82,
            ageSeconds = 300,
        )
        val encoded = beacon.encode()
        val encodedHex = encoded.joinToString("") { "%02x".format(it) }
        check("beacon encodes to ${Beacon.SIZE} bytes", encoded.size == Beacon.SIZE, "was ${encoded.size}")
        check(
            "beacon matches expected bytes",
            encodedHex == EXPECTED_BEACON,
            "expected $EXPECTED_BEACON got $encodedHex",
        )

        val decoded = Beacon.decode(encoded)
        check("beacon decodes", decoded != null)
        check("round trip is lossless", decoded == beacon, "got $decoded")

        // ageSeconds is a uint16 and must saturate rather than wrap.
        val saturated = beacon.copy(ageSeconds = 999_999).encode()
        check(
            "ageSeconds saturates at 65535",
            saturated[11] == 0xFF.toByte() && saturated[12] == 0xFF.toByte(),
            "got ${"%02x%02x".format(saturated[11], saturated[12])}",
        )

        check("short input rejected", Beacon.decode(ByteArray(5)) == null)
        check("null input rejected", Beacon.decode(null) == null)

        // Privacy invariant (T11): the beacon must not contain the event_id.
        check(
            "raw event_id absent from beacon",
            !encodedHex.contains(TEST_EVENT_ID.replace("-", "")),
        )

        SpikeLog.log("self-tests: $passed passed, $failed failed")
        return failed == 0
    }
}
