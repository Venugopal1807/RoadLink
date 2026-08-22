package com.roadlink.domain

/**
 * Evidence that a crash may have occurred.
 *
 * Carries its own [EventOrigin] so the pipeline downstream never has to guess
 * whether a real sensor or a test button produced it - and so a simulated
 * trigger cannot be laundered into a real one anywhere along the way.
 */
data class CrashSignal(
    /** 0-100. Evidence strength, explicitly NOT a medical or diagnostic claim. */
    val confidence: Int,
    /** Which signals fired, e.g. peak_g, inactivity. Explainable by construction. */
    val triggers: List<String>,
    val origin: EventOrigin,
) {
    init {
        require(confidence in 0..100) { "confidence out of range: $confidence" }
    }
}

/**
 * Produces [CrashSignal]s.
 *
 * The domain deliberately does not care which implementation is running. A
 * future SensorCrashDetector doing accelerometer/gyroscope fusion and the
 * present [TestCrashDetector] hand exactly the same signal to exactly the same
 * controller, so the pipeline being demonstrated now is the pipeline that will
 * run for a real crash - only the trigger changes.
 */
interface CrashDetector {
    val fidelity: Fidelity

    /** Called by the controller when a signal should be produced. */
    fun sample(): CrashSignal?
}

/**
 * DEVELOPMENT ONLY. Produces a signal on demand, for CREATE TEST SOS.
 *
 * This is what makes the demo reproducible: a real crash cannot be staged on
 * cue, and a demo that depends on shaking a phone hard enough is a demo that
 * fails in front of an audience. Every signal it produces is stamped
 * MANUAL_TEST, which sets `simulated = true` inside the signed packet - so the
 * marker cannot be stripped downstream without invalidating the signature.
 */
class TestCrashDetector(
    private val confidence: Int = 82,
    private val triggers: List<String> = listOf("peak_g", "inactivity"),
) : CrashDetector {

    override val fidelity: Fidelity = Fidelity.SIMULATED

    override fun sample(): CrashSignal = CrashSignal(
        confidence = confidence,
        triggers = triggers,
        origin = EventOrigin.MANUAL_TEST,
    )
}

/**
 * Where the emergency's coordinates come from.
 *
 * Returns null rather than a fabricated position when there is no fix. An SOS
 * with no location is still worth delivering; an SOS with a made-up location
 * is actively dangerous.
 */
fun interface LocationProvider {
    suspend fun current(): Fix?

    data class Fix(val lat: Double, val lng: Double, val accuracyMetres: Int?)
}
