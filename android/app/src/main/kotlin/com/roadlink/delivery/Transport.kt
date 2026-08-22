package com.roadlink.delivery

import com.roadlink.domain.EmergencyEvent
import com.roadlink.domain.TransportKind

/**
 * One way of getting an emergency to the backend.
 *
 * This interface is the boundary that keeps BLE replaceable. Nothing above it
 * knows about BluetoothGatt, advertisers, scanners, GATT callbacks or BLE
 * permissions; nothing below it knows about the state machine or the database.
 *
 * A transport is deliberately NOT allowed to mutate or delete the event. It
 * receives an immutable event and returns a result; the [DeliveryManager] owns
 * every state change. That is what stops delivery code from ever being in a
 * position to drop an emergency.
 */
interface Transport {

    val kind: TransportKind

    /**
     * Whether this transport could plausibly work right now.
     *
     * Cheap and side-effect free - no connections, no radio work. A `true`
     * here is a hint, not a promise: [deliver] may still return
     * [TransportResult.Unavailable].
     */
    suspend fun isAvailable(): Boolean

    /**
     * Attempt to deliver. Must not throw for expected failures; return
     * [TransportResult.Failed] instead so the manager can record and retry.
     */
    suspend fun deliver(event: EmergencyEvent): TransportResult
}

/** The outcome of one delivery attempt. */
sealed interface TransportResult {

    /** The backend has the event. Terminal for this event. */
    data class Delivered(
        val detail: String? = null,
        /** True when the backend recognised this event_id as already stored. */
        val duplicate: Boolean = false,
    ) : TransportResult

    /** This transport could have worked but did not. Retryable. */
    data class Failed(val reason: String) : TransportResult

    /**
     * This transport is not usable right now - no relay in range, no network,
     * no BLE hardware. Distinct from [Failed] because it is not worth counting
     * as an error against the event; the manager simply tries the next
     * transport.
     */
    data class Unavailable(val reason: String) : TransportResult
}
