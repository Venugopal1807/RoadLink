package com.roadlink.ble

import com.roadlink.domain.Canonical
import com.roadlink.domain.EventOrigin
import com.roadlink.net.SosPacketCodec
import com.roadlink.testEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BLE wire protocol, tested on a plain JVM.
 *
 * None of this needs a radio: the beacon, the ACK and the packet are all pure
 * codecs. Testing them here means that when hardware bring-up starts tomorrow,
 * a failure is a radio problem rather than a serialisation problem - which is
 * the whole reason to separate them.
 */
class BleProtocolTest {

    // -------------------------------------------------------------- beacon

    /**
     * Pinned against the same vector the spike's SelfTest uses, which was in
     * turn generated from the Python backend. The product and the S0-S5
     * harness must stay interoperable so either side can be swapped in during
     * bring-up to isolate a fault.
     */
    @Test
    fun `beacon matches the spike and backend vector byte for byte`() {
        val ref = Beacon.eventRefOf("00000000-0000-4000-8000-000000000001")
        assertEquals("11e594f481958c10", ref.joinToString("") { "%02x".format(it) })

        val beacon = Beacon(
            simulated = true,
            needsGatt = true,
            hasLocation = false,
            ackWanted = true,
            eventRef = ref,
            confidence = 82,
            ageSeconds = 300,
        )
        assertEquals(
            "010b11e594f481958c1052012c",
            beacon.encode().joinToString("") { "%02x".format(it) },
        )
        assertEquals(Beacon.SIZE, beacon.encode().size)
    }

    @Test
    fun `beacon round trips`() {
        val beacon = Beacon(
            simulated = false,
            needsGatt = true,
            hasLocation = true,
            ackWanted = true,
            eventRef = Beacon.eventRefOf("round-trip"),
            confidence = 55,
            ageSeconds = 4200,
        )
        assertEquals(beacon, Beacon.decode(beacon.encode()))
    }

    @Test
    fun `beacon fits the 13 byte scan response budget`() {
        // The spec arithmetic allows ~27 bytes of manufacturer data in the scan
        // response. If the beacon ever outgrows that, advertising starts
        // failing with DATA_TOO_LARGE on hardware and nowhere else.
        val encoded = Beacon.forEvent(testEvent(), now = 2_000L).encode()
        assertEquals(13, encoded.size)
        assertTrue("beacon must stay inside the scan-response budget", encoded.size <= 27)
    }

    @Test
    fun `beacon leaks neither the event id nor any coordinate`() {
        val event = testEvent()
        val hex = Beacon.forEvent(event, now = 2_000L).encode().joinToString("") { "%02x".format(it) }

        assertFalse(
            "the raw event_id must never be broadcast",
            hex.contains(event.eventId.replace("-", "")),
        )
        // 17.4401 / 78.3489 must not appear in any byte-level encoding.
        val latBits = java.lang.Double.doubleToRawLongBits(event.lat!!).toULong().toString(16)
        val lngBits = java.lang.Double.doubleToRawLongBits(event.lng!!).toULong().toString(16)
        assertFalse("latitude must not be broadcast", hex.contains(latBits))
        assertFalse("longitude must not be broadcast", hex.contains(lngBits))
        assertFalse("rider id must not be broadcast", hex.contains("rl_test".toByteArray().toHex()))
    }

    @Test
    fun `beacon carries the simulated flag so a relay knows before it connects`() {
        val simulated = Beacon.forEvent(testEvent(origin = EventOrigin.MANUAL_TEST), 2_000L)
        val real = Beacon.forEvent(testEvent(origin = EventOrigin.REAL_SENSOR), 2_000L)
        assertTrue(simulated.simulated)
        assertFalse(real.simulated)
        assertTrue(Beacon.decode(simulated.encode())!!.simulated)
        assertFalse(Beacon.decode(real.encode())!!.simulated)
    }

    @Test
    fun `beacon age saturates rather than wrapping`() {
        val beacon = Beacon.forEvent(testEvent(createdAt = 0L), now = 10_000_000_000L)
        val encoded = beacon.encode()
        assertEquals(0xFF.toByte(), encoded[11])
        assertEquals(0xFF.toByte(), encoded[12])
    }

    @Test
    fun `malformed beacons are rejected`() {
        assertNull(Beacon.decode(null))
        assertNull(Beacon.decode(ByteArray(5)))
        // confidence > 100 is not a RoadLink beacon
        val bogus = ByteArray(13).also { it[10] = 200.toByte() }
        assertNull(Beacon.decode(bogus))
    }

    // ----------------------------------------------------------------- ack

    @Test
    fun `ack round trips and carries the event id for correlation`() {
        val ack = BleAck("9f2c1d4e-7a3b-4c5d-8e6f-0a1b2c3d4e5f", "rl_relay_b", 1_755_763_200_123L)
        val decoded = BleAck.decode(ack.encode())
        assertNotNull(decoded)
        assertEquals(ack, decoded)
    }

    @Test
    fun `malformed acks are rejected rather than half parsed`() {
        assertNull(BleAck.decode(null))
        assertNull(BleAck.decode(ByteArray(0)))
        assertNull(BleAck.decode("NOPE|a|b|1".toByteArray()))
        assertNull(BleAck.decode("ACK|a|b".toByteArray()))
        assertNull(BleAck.decode("ACK|a|b|notanumber".toByteArray()))
        assertNull(BleAck.decode("ACK||b|1".toByteArray()))
    }

    // -------------------------------------------------------------- packet

    @Test
    fun `packet survives the round trip a relay performs`() {
        val original = testEvent().let { it.copy(signature = Canonical.sign(it)) }

        val overTheAir = SosPacketCodec.encodeBytes(original)
        val received = SosPacketCodec.decode(overTheAir)

        assertNotNull("a relay must be able to parse what a rider serves", received)
        received!!

        // Every signed field must survive untouched, or the signature breaks.
        assertEquals(original.eventId, received.eventId)
        assertEquals(original.riderId, received.riderId)
        assertEquals(original.createdAt, received.createdAt)
        assertEquals(original.lat!!, received.lat!!, 0.0000001)
        assertEquals(original.lng!!, received.lng!!, 0.0000001)
        assertEquals(original.accuracyMetres, received.accuracyMetres)
        assertEquals(original.confidence, received.confidence)
        assertEquals(original.triggers.sorted(), received.triggers.sorted())
        assertEquals(original.simulated, received.simulated)
        assertEquals("a relay never re-signs", original.signature, received.signature)
    }

    @Test
    fun `a relayed packet still verifies and re-encodes identically`() {
        val original = testEvent().let { it.copy(signature = Canonical.sign(it)) }
        val received = SosPacketCodec.decode(SosPacketCodec.encodeBytes(original))!!

        assertTrue("the relay must be able to verify before forwarding", SosPacketCodec.verify(received))
        // What the relay uploads must be byte-identical to what the rider would
        // have uploaded, or the two paths would not be idempotent at the backend.
        assertEquals(SosPacketCodec.encode(original), SosPacketCodec.encode(received))
    }

    @Test
    fun `a tampered packet fails verification so a relay drops it`() {
        val original = testEvent().let { it.copy(signature = Canonical.sign(it)) }
        val json = SosPacketCodec.encode(original).replace("\"conf\":82", "\"conf\":17")

        val tampered = SosPacketCodec.decode(json)
        assertNotNull("it still parses", tampered)
        assertFalse(
            "but it must not verify - forwarding it would let anyone inject emergencies",
            SosPacketCodec.verify(tampered!!),
        )
    }

    @Test
    fun `packet with no location round trips as null rather than zero`() {
        val original = testEvent()
            .copy(lat = null, lng = null, accuracyMetres = null)
            .let { it.copy(signature = Canonical.sign(it)) }
        val received = SosPacketCodec.decode(SosPacketCodec.encodeBytes(original))!!

        assertNull(received.lat)
        assertNull(received.lng)
        assertNull(received.accuracyMetres)
        assertTrue(SosPacketCodec.verify(received))
    }

    @Test
    fun `packet fits inside a single GATT characteristic`() {
        val event = testEvent().let { it.copy(signature = Canonical.sign(it)) }
        val size = SosPacketCodec.encodeBytes(event).size
        assertTrue(
            "packet is $size B, over the ${SosPacketCodec.MAX_PACKET_BYTES} B ATT limit",
            size <= SosPacketCodec.MAX_PACKET_BYTES,
        )
    }

    @Test
    fun `malformed packets decode to null rather than a guess`() {
        assertNull(SosPacketCodec.decode("not json"))
        assertNull(SosPacketCodec.decode("{}"))
        assertNull(SosPacketCodec.decode("""{"event_id":"x"}"""))
        // confidence outside 0..100 is not a valid emergency
        assertNull(
            SosPacketCodec.decode(
                """{"v":1,"event_id":"a","rider_id":"b","created_at":1,"lat":null,"lng":null,
                   "acc_m":null,"conf":250,"trigger":[],"simulated":true,"sig":null}"""
            )
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
