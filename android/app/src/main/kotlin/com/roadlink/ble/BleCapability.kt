package com.roadlink.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * What this device's radio can actually do.
 *
 * `canAdvertise` is the single value the whole RoadLink topology rests on. The
 * rider's phone must take the BLE peripheral role, and
 * `getBluetoothLeAdvertiser()` returns null on chipsets with no LE peripheral
 * role at all - central-only Android devices exist. Until this has been read
 * off real hardware, phone-to-phone relaying is a design, not a capability.
 *
 * This is the S0 probe from docs/s0-s5-runbook.md, in the product.
 */
data class BleCapability(
    val hasBleFeature: Boolean,
    val adapterPresent: Boolean,
    val adapterEnabled: Boolean,
    val canAdvertise: Boolean,
    val scannerAvailable: Boolean,
    val multipleAdvertisementSupported: Boolean,
    val extendedAdvertisingSupported: Boolean,
    val le2MPhySupported: Boolean,
    val leCodedPhySupported: Boolean,
    val maxAdvertisingDataLength: Int,
    val offloadedFilteringSupported: Boolean,
    val deviceModel: String,
    val manufacturer: String,
    val androidRelease: String,
    val apiLevel: Int,
) {
    /** True when this phone can take the rider (peripheral) role. */
    val canBePeripheral: Boolean get() = adapterEnabled && canAdvertise

    /** True when this phone can take the relay (central) role. */
    val canBeCentral: Boolean get() = adapterEnabled && scannerAvailable

    /** Why this device cannot advertise, or null if it can. */
    val peripheralBlocker: String?
        get() = when {
            !hasBleFeature -> "device has no Bluetooth LE"
            !adapterPresent -> "no Bluetooth adapter"
            !adapterEnabled -> "Bluetooth is off"
            !canAdvertise -> "chipset has no LE peripheral role (cannot advertise)"
            else -> null
        }

    /** The verdict line for the S0 gate. Recorded verbatim in the verification log. */
    val advertiseVerdict: String
        get() = if (canAdvertise) "CAN ADVERTISE" else "CANNOT ADVERTISE"

    fun render(): String = buildString {
        appendLine("Device            : $manufacturer $deviceModel")
        appendLine("Android           : $androidRelease (API $apiLevel)")
        appendLine("BLE feature       : $hasBleFeature")
        appendLine("Adapter present   : $adapterPresent")
        appendLine("Adapter enabled   : $adapterEnabled")
        appendLine("--- peripheral role gate (S0) ---")
        appendLine("$advertiseVerdict${if (canAdvertise) "" else "  <-- CANNOT BE THE RIDER"}")
        appendLine("LE advertiser     : ${if (canAdvertise) "present" else "null"}")
        appendLine("Multi-advertise   : $multipleAdvertisementSupported")
        appendLine("--- central role ---")
        appendLine("Scanner available : $scannerAvailable")
        appendLine("Offloaded filter  : $offloadedFilteringSupported")
        appendLine("--- BLE 5 ---")
        appendLine("Extended adv      : $extendedAdvertisingSupported")
        appendLine("LE 2M PHY         : $le2MPhySupported")
        appendLine("LE Coded PHY      : $leCodedPhySupported")
        append("Max adv data len  : $maxAdvertisingDataLength bytes")
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun probe(context: Context): BleCapability {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = manager?.adapter
            return BleCapability(
                hasBleFeature = context.packageManager
                    .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
                adapterPresent = adapter != null,
                adapterEnabled = adapter?.isEnabled == true,
                // THE gate. Null means no peripheral role on this hardware.
                canAdvertise = runCatching { adapter?.bluetoothLeAdvertiser != null }.getOrDefault(false),
                scannerAvailable = runCatching { adapter?.bluetoothLeScanner != null }.getOrDefault(false),
                multipleAdvertisementSupported = adapter?.isMultipleAdvertisementSupported == true,
                extendedAdvertisingSupported = adapter?.isLeExtendedAdvertisingSupported == true,
                le2MPhySupported = adapter?.isLe2MPhySupported == true,
                leCodedPhySupported = adapter?.isLeCodedPhySupported == true,
                maxAdvertisingDataLength = adapter?.leMaximumAdvertisingDataLength ?: 0,
                offloadedFilteringSupported = adapter?.isOffloadedFilteringSupported == true,
                deviceModel = Build.MODEL,
                manufacturer = Build.MANUFACTURER,
                androidRelease = Build.VERSION.RELEASE,
                apiLevel = Build.VERSION.SDK_INT,
            )
        }
    }
}
