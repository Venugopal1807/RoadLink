package com.roadlink.spike.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.roadlink.spike.SpikeLog

/**
 * S0 - radio capability probe, and test T2 (empirical advertisement ceiling).
 *
 * This runs BEFORE any protocol code, because it answers the question that
 * decides whether the RoadLink topology is possible at all on this hardware:
 *
 *     Can this phone act as a BLE PERIPHERAL?
 *
 * getBluetoothLeAdvertiser() returns null on devices whose chipset/firmware
 * does not support the LE peripheral role. Central-only Android devices exist.
 * If Phone A cannot advertise, it cannot be the rider in this design.
 */
@SuppressLint("MissingPermission")
class RadioCapabilities(private val context: Context) {

    private val manager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    val adapter: BluetoothAdapter? = manager?.adapter

    data class Report(
        val deviceModel: String,
        val androidRelease: String,
        val apiLevel: Int,
        val hasBleFeature: Boolean,
        val adapterPresent: Boolean,
        val adapterEnabled: Boolean,
        val canAdvertise: Boolean,
        val multipleAdvertisementSupported: Boolean,
        val extendedAdvertisingSupported: Boolean,
        val le2MPhySupported: Boolean,
        val leCodedPhySupported: Boolean,
        val maxAdvertisingDataLength: Int,
        val offloadedFilteringSupported: Boolean,
        val offloadedScanBatchingSupported: Boolean,
    ) {
        /** True when this phone can take the Phone A (rider/peripheral) role. */
        val canBePeripheral: Boolean get() = adapterEnabled && canAdvertise

        fun render(): String = buildString {
            appendLine("Device            : $deviceModel")
            appendLine("Android           : $androidRelease (API $apiLevel)")
            appendLine("BLE feature       : $hasBleFeature")
            appendLine("Adapter present   : $adapterPresent")
            appendLine("Adapter enabled   : $adapterEnabled")
            appendLine("--- peripheral role gate ---")
            appendLine("CAN ADVERTISE     : $canAdvertise  ${if (canAdvertise) "" else "<-- CANNOT BE PHONE A"}")
            appendLine("Multi-advertise   : $multipleAdvertisementSupported")
            appendLine("--- BLE 5 ---")
            appendLine("Extended adv      : $extendedAdvertisingSupported")
            appendLine("LE 2M PHY         : $le2MPhySupported")
            appendLine("LE Coded PHY      : $leCodedPhySupported")
            appendLine("Max adv data len  : $maxAdvertisingDataLength bytes")
            appendLine("--- scanner offload ---")
            appendLine("Offloaded filter  : $offloadedFilteringSupported")
            append("Offloaded batching: $offloadedScanBatchingSupported")
        }
    }

    fun probe(): Report {
        val a = adapter
        val report = Report(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidRelease = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            hasBleFeature = context.packageManager
                .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            adapterPresent = a != null,
            adapterEnabled = a?.isEnabled == true,
            // THE gate. Null here means no peripheral role on this hardware.
            canAdvertise = a?.bluetoothLeAdvertiser != null,
            multipleAdvertisementSupported = a?.isMultipleAdvertisementSupported == true,
            extendedAdvertisingSupported = a?.isLeExtendedAdvertisingSupported == true,
            le2MPhySupported = a?.isLe2MPhySupported == true,
            leCodedPhySupported = a?.isLeCodedPhySupported == true,
            maxAdvertisingDataLength = a?.leMaximumAdvertisingDataLength ?: 0,
            offloadedFilteringSupported = a?.isOffloadedFilteringSupported == true,
            offloadedScanBatchingSupported = a?.isOffloadedScanBatchingSupported == true,
        )
        SpikeLog.log("S0 capability probe:\n${report.render()}")
        return report
    }

    // ------------------------------------------------------------------ T2

    /**
     * Test T2: find the real ceiling for manufacturer-specific data by
     * advertising increasing payload sizes until the stack rejects one.
     *
     * We measure rather than trust the 31-byte arithmetic, because what the
     * stack actually accepts is what matters. Runs sequentially: each attempt
     * must fully start or fail before the next begins.
     *
     * @param inScanResponse true measures the scan-response budget (where the
     *        RoadLink beacon actually lives), false measures the AdvData budget
     *        that has to share its 31 bytes with Flags and the 128-bit UUID.
     */
    fun probePayloadCeiling(inScanResponse: Boolean, onResult: (Int) -> Unit) {
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            SpikeLog.logError("T2 skipped: this device cannot advertise")
            onResult(-1)
            return
        }

        val where = if (inScanResponse) "scanResponse" else "advData"
        SpikeLog.mark("T2 payload ceiling ($where)")

        val handler = Handler(Looper.getMainLooper())
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        var size = 1
        var largestAccepted = 0
        var active: AdvertiseCallback? = null

        fun stop() {
            active?.let {
                runCatching { advertiser.stopAdvertising(it) }
                active = null
            }
        }

        fun attempt() {
            if (size > MAX_PROBE_BYTES) {
                stop()
                SpikeLog.log("T2 ($where): reached probe cap; largest accepted = $largestAccepted bytes")
                onResult(largestAccepted)
                return
            }

            val payload = ByteArray(size) { 0xAB.toByte() }
            val uuidData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)  // never leak the device name
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(RoadLinkUuids.SERVICE))
                .build()
            val mfgData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .apply {
                    if (!inScanResponse) addServiceUuid(ParcelUuid(RoadLinkUuids.SERVICE))
                    addManufacturerData(RoadLinkUuids.COMPANY_ID, payload)
                }
                .build()

            val timeout = Runnable {
                SpikeLog.logError("T2 ($where): no callback for size=$size; stopping probe")
                stop()
                onResult(largestAccepted)
            }

            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    handler.removeCallbacks(timeout)
                    largestAccepted = size
                    SpikeLog.log("T2 ($where): size=$size accepted")
                    stop()
                    size += 1
                    handler.postDelayed({ attempt() }, SETTLE_MS)
                }

                override fun onStartFailure(errorCode: Int) {
                    handler.removeCallbacks(timeout)
                    stop()
                    if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE) {
                        SpikeLog.log(
                            "T2 ($where): size=$size REJECTED (DATA_TOO_LARGE). " +
                                "Ceiling = $largestAccepted bytes"
                        )
                        onResult(largestAccepted)
                    } else {
                        SpikeLog.logError(
                            "T2 ($where): size=$size failed with ${describeAdvertiseError(errorCode)}"
                        )
                        onResult(largestAccepted)
                    }
                }
            }

            active = callback
            handler.postDelayed(timeout, CALLBACK_TIMEOUT_MS)
            runCatching {
                if (inScanResponse) {
                    advertiser.startAdvertising(settings, uuidData, mfgData, callback)
                } else {
                    advertiser.startAdvertising(settings, mfgData, callback)
                }
            }.onFailure {
                handler.removeCallbacks(timeout)
                SpikeLog.logError("T2 ($where): startAdvertising threw at size=$size", it)
                onResult(largestAccepted)
            }
        }

        attempt()
    }

    companion object {
        private const val MAX_PROBE_BYTES = 40
        private const val SETTLE_MS = 180L
        private const val CALLBACK_TIMEOUT_MS = 4000L

        fun describeAdvertiseError(code: Int): String = when (code) {
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
            else -> "UNKNOWN($code)"
        }
    }
}
