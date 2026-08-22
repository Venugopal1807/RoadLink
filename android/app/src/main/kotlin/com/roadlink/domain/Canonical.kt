package com.roadlink.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Kotlin mirror of the backend's `app/canonical.py`.
 *
 * This must produce BYTE-IDENTICAL output to the Python implementation. Any
 * change here is a wire-protocol break and requires a schema_version bump on
 * both sides.
 *
 * WHY NOT JSON: signing over JSON across Kotlin and Python is a classic source
 * of silent signature mismatches - the two languages disagree on float repr,
 * key ordering and unicode escaping. Instead a fixed, field-ordered,
 * explicitly-formatted string is signed, in which every field has exactly one
 * textual representation.
 *
 * The vectors in `CanonicalTest` are generated from the Python implementation,
 * so a divergence fails the JVM test suite rather than silently producing
 * packets every relay will reject.
 */
object Canonical {

    /**
     * Spike-grade pre-shared key, identical to the backend's.
     *
     * This is NOT key management and is not treated as a secret: it ships
     * inside the APK, so anyone holding the APK holds it. It proves the
     * plumbing - canonical serialisation, verify-before-forward,
     * reject-on-tamper - and nothing more. Production is a per-device Ed25519
     * keypair with the private key in the Android Keystore.
     * See docs/phase1-spike-plan.md G.2.
     */
    private val SPIKE_HMAC_KEY: ByteArray =
        "roadlink-phase1-spike-key-not-for-production".toByteArray(Charsets.UTF_8)

    /** HMAC-SHA256 truncated to 128 bits, matching the backend. */
    private const val SIG_BYTES = 16

    const val SCHEMA_VERSION = 1

    /**
     * Coordinates are formatted to exactly 7 decimal places (~1.1 cm).
     *
     * BigDecimal(double) is used on purpose: it expands the EXACT binary value
     * of the double, which is what C's printf - and therefore Python's "%.7f" -
     * rounds from. Going through Double.toString first (BigDecimal.valueOf)
     * would round twice and could disagree with Python in the last place.
     * HALF_EVEN matches the C library's tie behaviour.
     *
     * String.format is deliberately NOT used here: it is locale-sensitive and
     * would emit a comma decimal separator under a locale such as de-DE,
     * silently breaking every signature on those devices.
     */
    private fun fmtCoord(value: Double?): String {
        if (value == null) return ""
        val rounded = BigDecimal(value).setScale(7, RoundingMode.HALF_EVEN)
        val text = rounded.toPlainString()
        // C's printf - and therefore Python's "%.7f" - keeps the sign of a
        // negative value that rounds to zero: -4.9e-8 formats as "-0.0000000".
        // BigDecimal has no negative zero, so the sign is restored by hand.
        // Verified against the Python implementation; see CanonicalTest.
        val isNegativeZero = rounded.signum() == 0 && (value < 0.0 || 1.0 / value < 0.0)
        return if (isNegativeZero) "-$text" else text
    }

    private fun fmtInt(value: Int?): String = value?.toString() ?: ""

    private fun fmtLong(value: Long?): String = value?.toString() ?: ""

    private fun fmtBool(value: Boolean): String = if (value) "1" else "0"

    private fun fmtTriggers(triggers: List<String>?): String =
        if (triggers.isNullOrEmpty()) "" else triggers.sorted().joinToString(",")

    /**
     * Build the exact string that gets signed. Field order is fixed and must
     * match `canonical.py::canonical_string` exactly.
     */
    fun canonicalString(
        v: Int,
        eventId: String,
        riderId: String,
        createdAt: Long,
        lat: Double?,
        lng: Double?,
        accuracyMetres: Int?,
        confidence: Int,
        triggers: List<String>,
        simulated: Boolean,
    ): String = listOf(
        "v=" + fmtInt(v),
        "event_id=$eventId",
        "rider_id=$riderId",
        "created_at=" + fmtLong(createdAt),
        "lat=" + fmtCoord(lat),
        "lng=" + fmtCoord(lng),
        "acc_m=" + fmtInt(accuracyMetres),
        "conf=" + fmtInt(confidence),
        "trigger=" + fmtTriggers(triggers),
        "simulated=" + fmtBool(simulated),
    ).joinToString("|")

    fun canonicalString(event: EmergencyEvent): String = canonicalString(
        v = SCHEMA_VERSION,
        eventId = event.eventId,
        riderId = event.riderId,
        createdAt = event.createdAt,
        lat = event.lat,
        lng = event.lng,
        accuracyMetres = event.accuracyMetres,
        confidence = event.confidence,
        triggers = event.triggers,
        simulated = event.simulated,
    )

    /** The packet's `sig` field: base64 of the truncated HMAC. */
    fun sign(event: EmergencyEvent, key: ByteArray = SPIKE_HMAC_KEY): String =
        sign(canonicalString(event), key)

    fun sign(canonical: String, key: ByteArray = SPIKE_HMAC_KEY): String {
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }
        val digest = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        return base64(digest.copyOf(SIG_BYTES))
    }

    /**
     * First 8 bytes of SHA-256(event_id).
     *
     * This is what the BLE advertisement broadcasts instead of the raw
     * event_id, so a passive eavesdropper cannot correlate a broadcast with a
     * backend record, and because Android randomises the BLE advertising
     * address so a scanner cannot dedupe on MAC.
     * Mirrors `canonical.py::event_ref` and `ble/Beacon.kt`.
     */
    fun eventRef(eventId: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(eventId.toByteArray(Charsets.UTF_8))
            .copyOf(EVENT_REF_BYTES)

    fun eventRefHex(eventId: String): String = eventRef(eventId).toHex()

    const val EVENT_REF_BYTES = 8

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * Base64 without android.util.Base64, so this object stays testable on a
     * plain JVM rather than needing an instrumented device.
     */
    private fun base64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)
}
