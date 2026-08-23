package com.roadlink.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Input handling for the on-device backend address.
 *
 * This is typed by hand, on a phone, by someone who did not build the APK -
 * usually in a hurry. It has to tolerate a trailing slash and a missing scheme,
 * and it has to reject input that would make every later request throw.
 */
class BackendConfigTest {

    private fun norm(raw: String) = BackendConfig.normalise(raw)

    @Test
    fun `a complete url is kept as it is`() {
        assertEquals("http://192.168.1.10:8000", norm("http://192.168.1.10:8000"))
        assertEquals("https://roadlink.example.com", norm("https://roadlink.example.com"))
    }

    @Test
    fun `a missing scheme is assumed to be http`() {
        // The prototype backend has no certificate, so http is the right guess.
        assertEquals("http://192.168.1.10:8000", norm("192.168.1.10:8000"))
        assertEquals("http://roadlink.local", norm("roadlink.local"))
    }

    @Test
    fun `https is never downgraded`() {
        assertEquals("https://example.com:8443", norm("https://example.com:8443"))
    }

    @Test
    fun `surrounding whitespace and trailing slashes are removed`() {
        assertEquals("http://192.168.1.10:8000", norm("  http://192.168.1.10:8000/  "))
        assertEquals("http://192.168.1.10:8000", norm("192.168.1.10:8000///"))
    }

    @Test
    fun `input that cannot form a url is rejected rather than stored`() {
        // Storing any of these would break every later request with no way for
        // the user to tell why, so they are refused and the previous value stands.
        assertNull(norm(""))
        assertNull(norm("   "))
        assertNull(norm("http://"))
        assertNull(norm(":8000"))
        assertNull(norm("/api/v1/sos"))
    }

    @Test
    fun `a path on the base url survives, since a backend may be hosted under one`() {
        assertEquals("https://example.com/roadlink", norm("https://example.com/roadlink/"))
    }
}
