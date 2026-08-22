package com.roadlink.delivery

import android.content.Context
import com.roadlink.ble.BleCapability
import com.roadlink.ble.BlePeripheral
import com.roadlink.domain.DeliveryState
import com.roadlink.domain.EmergencyEvent
import com.roadlink.domain.TransportKind

/**
 * Phone-to-phone BLE relay, rider side.
 *
 * Advertises the emergency as a beacon, serves the signed packet over GATT to
 * whichever relay connects, and waits for that relay's acknowledgement. The
 * radio work lives entirely in [BlePeripheral]; this class only adapts it to
 * the [Transport] contract, so nothing above this file sees a Bluetooth type.
 *
 * ## Status: NOT VALIDATED ON PHYSICAL HARDWARE
 *
 * [enabled] is false by default and must stay false until the S0-S5 ladder in
 * docs/s0-s5-runbook.md has been observed passing on two real phones. The
 * decisive unknown is whether `getBluetoothLeAdvertiser()` is non-null on the
 * actual handsets - central-only Android devices exist, and on those this
 * topology is impossible rather than merely slow.
 *
 * Until then this transport reports itself unavailable, and the offline queue
 * plus direct upload remains the product's proven path.
 */
class BleRelayTransport(
    private val context: Context,
    /**
     * Master switch. Turn on only after S0 reports CAN ADVERTISE on the
     * device taking the rider role.
     */
    @Volatile var enabled: Boolean = false,
    /**
     * How long to advertise before giving up on this pass.
     *
     * Bounded rather than indefinite because a delivery pass must stay free to
     * try other transports. A timeout is not a failure of the emergency: the
     * event stays queued and is offered again next pass.
     */
    @Volatile var offerTimeoutMs: Long = 20_000L,
    private val log: (String) -> Unit = {},
) : Transport {

    override val kind: TransportKind = TransportKind.BLE_RELAY

    private val peripheral by lazy { BlePeripheral(context, log) }

    fun probe(): BleCapability = BleCapability.probe(context)

    /**
     * Cheap and side-effect free: no radio work, just a capability read.
     * Reports false while disabled so the manager never spends a delivery
     * window on a path that has never moved a byte.
     */
    override suspend fun isAvailable(): Boolean {
        if (!enabled) return false
        return probe().canBePeripheral
    }

    override suspend fun deliver(event: EmergencyEvent): TransportResult {
        if (!enabled) {
            return TransportResult.Unavailable(
                "BLE transport disabled pending S0-S5 physical validation"
            )
        }

        // Already handed to a relay: do not spend another advertising window on
        // it. Let the cascade fall through so the rider's own network can still
        // deliver it directly if that becomes possible.
        if (event.state == DeliveryState.RELAYED) {
            return TransportResult.Unavailable(
                "already handed to relay ${event.relayedTo ?: "unknown"}"
            )
        }

        val capability = probe()
        capability.peripheralBlocker?.let { return TransportResult.Unavailable(it) }

        return when (val outcome = peripheral.offerEvent(event, offerTimeoutMs)) {
            is BlePeripheral.Outcome.Acked -> TransportResult.HandedOff(
                relayId = outcome.relayId,
                detail = "relay ${outcome.relayId} acknowledged over BLE after ${outcome.elapsedMs}ms",
            )

            // Nobody was in range. Entirely ordinary; the emergency waits.
            is BlePeripheral.Outcome.NotCollected -> TransportResult.Failed(
                "no relay collected the emergency within ${outcome.waitedMs}ms"
            )

            is BlePeripheral.Outcome.Unavailable -> TransportResult.Unavailable(outcome.reason)

            is BlePeripheral.Outcome.Failed -> TransportResult.Failed(outcome.reason)
        }
    }

    /** Stop advertising immediately, e.g. when the user leaves relay/rider mode. */
    fun stop() = peripheral.stop()
}
