package com.roadlink.net

import com.roadlink.domain.Canonical
import com.roadlink.domain.EmergencyEvent
import com.roadlink.domain.EventOrigin
import com.roadlink.domain.TransportKind
import com.roadlink.testEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The exact bytes that go on the wire.
 *
 * The envelope must agree with the signature: the backend re-derives the
 * canonical string from the JSON it parsed, so emitting a coordinate at full
 * double precision while signing it at 7dp would make every packet verify as
 * invalid. That failure is silent - the event is still stored, just flagged -
 * so it is pinned here.
 *
 * This test also writes the envelope to build/wire/ so it can be POSTed at the
 * real backend, which is what actually proves Kotlin and Python agree.
 */
class EnvelopeTest {

    private val client = SosApiClient("http://unused")

    private fun signed(event: EmergencyEvent) = event.copy(signature = Canonical.sign(event))

    @Test
    fun `envelope carries the packet and the delivery metadata separately`() {
        val event = signed(testEvent())
        val json = client.buildEnvelope(event, TransportKind.SIMULATED_RELAY, "rl_sim_relay_01", 1234L)

        assertTrue(json.startsWith("{\"packet\":"))
        assertTrue(json.contains("\"delivery\":"))
        assertTrue(json.contains("\"path\":\"simulated_relay\""))
        assertTrue(json.contains("\"relay_id\":\"rl_sim_relay_01\""))
        assertTrue(json.contains("\"relay_received_at\":1234"))
    }

    @Test
    fun `coordinates are emitted at the same precision they were signed at`() {
        val event = signed(testEvent())
        val json = client.buildEnvelope(event, TransportKind.DIRECT_NETWORK, null, null)

        // Signed as lat=17.4401000, so the wire must say 17.4401000 too.
        assertTrue("lat must be 7dp on the wire, was: $json", json.contains("\"lat\":17.4401000"))
        assertTrue("lng must be 7dp on the wire, was: $json", json.contains("\"lng\":78.3489000"))
    }

    @Test
    fun `absent location is null rather than zero`() {
        val event = signed(testEvent().copy(lat = null, lng = null, accuracyMetres = null))
        val json = client.buildEnvelope(event, TransportKind.DIRECT_NETWORK, null, null)

        assertTrue(json.contains("\"lat\":null"))
        assertTrue(json.contains("\"lng\":null"))
        assertTrue(json.contains("\"acc_m\":null"))
        // 0,0 is a real place in the Gulf of Guinea. Sending it for "unknown"
        // would be worse than sending nothing.
        assertFalse(json.contains("\"lat\":0"))
    }

    @Test
    fun `simulated flag is always present on the wire`() {
        val sim = client.buildEnvelope(signed(testEvent()), TransportKind.SIMULATED_RELAY, null, null)
        assertTrue(sim.contains("\"simulated\":true"))

        val real = client.buildEnvelope(
            signed(testEvent(origin = EventOrigin.REAL_SENSOR)),
            TransportKind.DIRECT_NETWORK, null, null,
        )
        assertTrue(real.contains("\"simulated\":false"))
    }

    @Test
    fun `optional delivery fields are omitted rather than sent as null`() {
        // The backend forbids unexpected fields; sending explicit nulls for
        // relay metadata on a direct upload is unnecessary and risks a 422.
        val json = client.buildEnvelope(signed(testEvent()), TransportKind.DIRECT_NETWORK, null, null)
        assertEquals("{\"path\":\"direct\"}", json.substringAfter("\"delivery\":").trimEnd('}').let { it + "}" })
    }

    @Test
    fun `strings are escaped`() {
        val event = signed(testEvent().copy(riderId = "rl_\"quoted\"\\back"))
        val json = client.buildEnvelope(event, TransportKind.DIRECT_NETWORK, null, null)
        assertTrue(json.contains("""rl_\"quoted\"\\back"""))
    }

    /**
     * Not an assertion - a fixture generator.
     *
     * Writes a genuinely signed envelope to disk so it can be POSTed at a
     * running backend. If the backend answers sig_valid=true, the Kotlin and
     * Python canonicalisations agree over real HTTP.
     */
    @Test
    fun `write a signed envelope fixture for backend verification`() {
        val event = signed(
            EmergencyEvent(
                eventId = "e2e00000-0000-4000-8000-00000000e2e1",
                riderId = "rl_e2e_test",
                createdAt = 1755763200123L,
                lat = 17.4401,
                lng = 78.3489,
                accuracyMetres = 12,
                confidence = 82,
                triggers = listOf("peak_g", "inactivity"),
                origin = EventOrigin.MANUAL_TEST,
                signature = null,
            )
        )
        val json = client.buildEnvelope(event, TransportKind.SIMULATED_RELAY, "rl_sim_relay_01", 1755763201000L)

        val out = File("build/wire").apply { mkdirs() }.resolve("signed-envelope.json")
        out.writeText(json)
        println("WIRE FIXTURE -> ${out.absolutePath}")
        println(json)

        assertTrue(out.exists())
    }
}
