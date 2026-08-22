package com.roadlink.delivery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.roadlink.domain.EmergencyEvent
import com.roadlink.domain.TransportKind

/**
 * Phone-to-phone BLE relay.
 *
 * STATUS: NOT VALIDATED ON PHYSICAL HARDWARE.
 *
 * This class is deliberately real but disarmed. It performs the genuine
 * capability probe - the same `getBluetoothLeAdvertiser() != null` gate the
 * spike's S0 step turns on - and reports honestly what this device can and
 * cannot do. What it does not yet do is move packets, because the working
 * radio code lives in :spike-ble and has never been executed on a phone.
 *
 * The gate is [enabled], which stays false until S0-S5 have actually passed on
 * two physical devices. Flipping it on before that would mean the app silently
 * preferring an unproven path over one that works.
 *
 * Tomorrow's integration is intended to be exactly this: lift the peripheral
 * and central roles from :spike-ble behind [deliver], flip [enabled], and
 * change nothing else. Nothing above this file imports a Bluetooth class, so
 * no caller has to change.
 */
@SuppressLint("MissingPermission")
class BleRelayTransport(
    private val context: Context,
    /**
     * Master switch. FALSE until the S0-S5 ladder has been observed passing on
     * real hardware. See docs/s0-s5-runbook.md.
     */
    var enabled: Boolean = false,
) : Transport {

    override val kind: TransportKind = TransportKind.BLE_RELAY

    /**
     * What this specific device's radio can actually do.
     *
     * `canAdvertise` is the decisive value for the whole RoadLink topology:
     * the rider's phone has to take the BLE peripheral role, and
     * getBluetoothLeAdvertiser() returns null on chipsets that have no LE
     * peripheral role at all. Central-only Android devices exist.
     */
    data class Capability(
        val hasBleFeature: Boolean,
        val adapterPresent: Boolean,
        val adapterEnabled: Boolean,
        val canAdvertise: Boolean,
        val deviceModel: String,
        val apiLevel: Int,
    ) {
        val canBePeripheral: Boolean get() = adapterEnabled && canAdvertise

        /** Human-readable reason this device cannot relay, or null if it could. */
        val blocker: String?
            get() = when {
                !hasBleFeature -> "device has no Bluetooth LE"
                !adapterPresent -> "no Bluetooth adapter"
                !adapterEnabled -> "Bluetooth is off"
                !canAdvertise -> "chipset has no LE peripheral role (cannot advertise)"
                else -> null
            }
    }

    fun probe(): Capability {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        return Capability(
            hasBleFeature = context.packageManager
                .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            adapterPresent = adapter != null,
            adapterEnabled = adapter?.isEnabled == true,
            // The gate. Null here means no peripheral role on this hardware.
            canAdvertise = runCatching { adapter?.bluetoothLeAdvertiser != null }.getOrDefault(false),
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            apiLevel = Build.VERSION.SDK_INT,
        )
    }

    /**
     * Always false until [enabled] is turned on after hardware validation.
     * Reporting availability we have not proven would let the manager waste
     * the only delivery window on a path that has never moved a byte.
     */
    override suspend fun isAvailable(): Boolean {
        if (!enabled) return false
        return probe().canBePeripheral
    }

    override suspend fun deliver(event: EmergencyEvent): TransportResult {
        if (!enabled) {
            return TransportResult.Unavailable(
                "BLE transport disabled pending S0-S5 physical-device validation"
            )
        }
        val capability = probe()
        capability.blocker?.let { return TransportResult.Unavailable(it) }

        // Reached only once `enabled` is true, which requires hardware
        // validation to have passed first. The radio implementation is lifted
        // from :spike-ble at that point.
        return TransportResult.Unavailable(
            "BLE relay not yet wired to the radio layer; run docs/s0-s5-runbook.md first"
        )
    }
}
