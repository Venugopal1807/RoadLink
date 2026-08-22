package com.roadlink.domain

import com.roadlink.delivery.DeliveryManager
import java.util.UUID

/**
 * Turns a [CrashSignal] into a confirmed, signed, persisted emergency.
 *
 * This is the single entry point into the emergency pipeline. The CREATE TEST
 * SOS action and a future sensor-fusion engine both arrive here, which is what
 * guarantees the flow being demonstrated today is the flow that will run for a
 * real crash: same signing, same persistence, same state machine, same
 * transports, same audit trail. Only the trigger differs.
 */
class EmergencyController(
    private val deliveryManager: DeliveryManager,
    private val locationProvider: LocationProvider,
    private val riderId: String,
    private val clock: Clock = Clock.System,
) {

    /**
     * Confirm an emergency and hand it to delivery.
     *
     * The event is signed BEFORE it is persisted or transmitted, and its
     * `event_id` is generated here, once, at the moment of confirmation - it
     * never changes afterwards. That stable id is what makes the backend's
     * idempotency work when the rider and one or more relays all submit the
     * same emergency independently.
     *
     * @throws Exception if the event could not be persisted. Delivery is not
     *         attempted in that case; see [DeliveryManager.submit].
     */
    suspend fun confirmEmergency(signal: CrashSignal): EmergencyEvent {
        val fix = runCatching { locationProvider.current() }.getOrNull()

        val unsigned = EmergencyEvent(
            eventId = UUID.randomUUID().toString(),
            riderId = riderId,
            createdAt = clock.now(),
            lat = fix?.lat,
            lng = fix?.lng,
            accuracyMetres = fix?.accuracyMetres,
            confidence = signal.confidence,
            triggers = signal.triggers,
            // Carried from the signal, and inside the signature: a simulated
            // event cannot be relabelled as real without breaking its signature.
            origin = signal.origin,
            signature = null,
        )

        val signed = unsigned.copy(signature = Canonical.sign(unsigned))

        // Persist-then-deliver. The ordering lives in DeliveryManager.submit
        // so there is exactly one place it can be got wrong.
        return deliveryManager.submit(signed)
    }

    /** Convenience for the CREATE TEST SOS action. */
    suspend fun confirmFrom(detector: CrashDetector): EmergencyEvent? =
        detector.sample()?.let { confirmEmergency(it) }
}
