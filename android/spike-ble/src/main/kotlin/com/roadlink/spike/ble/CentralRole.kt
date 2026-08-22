package com.roadlink.spike.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.roadlink.spike.SpikeLog

/**
 * Phone B - the relay. BLE central: scanner + GATT client.
 *
 * Drives the S1-S5 ladder end to end:
 *   S1 discover -> S2 connect + discover services -> S3 read + notify
 *   -> S4 write ACK -> S5 disconnect / reconnect with backoff
 */
@SuppressLint("MissingPermission")
class CentralRole(private val context: Context) {

    interface Events {
        fun onBeaconDiscovered(address: String, rssi: Int, beacon: Beacon)
        fun onConnected(address: String)
        fun onServicesDiscovered(address: String)
        fun onPayloadRead(address: String, payload: ByteArray)
        fun onNotification(address: String, value: ByteArray)
        fun onAckWritten(address: String)
        fun onDisconnected(address: String, status: Int, willRetry: Boolean)
        fun onFailure(stage: String, detail: String)
    }

    private val manager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = manager.adapter
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var scanning = false

    /** eventRef hex values already acted on, so we do not reconnect for the same event. */
    private val seenRefs = mutableSetOf<String>()

    private var retryAttempt = 0
    private var transferComplete = false

    var events: Events? = null
    var relayId: String = "rl_relay_${Build.MODEL.filter { it.isLetterOrDigit() }.takeLast(4).lowercase()}"

    // ------------------------------------------------------------------- scan

    fun startScan(): Boolean {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || scanner == null) {
            events?.onFailure("S1", "Bluetooth is off or scanner unavailable")
            return false
        }
        if (scanning) return true

        // Hardware-offloaded filter on the service UUID. This is why the UUID
        // must be in AdvData rather than the scan response.
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(RoadLinkUuids.SERVICE))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .build()

        SpikeLog.mark("S1 scan started (filter = RoadLink service UUID)")
        return runCatching {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
            true
        }.onFailure {
            SpikeLog.logError("S1: startScan threw", it)
            events?.onFailure("S1", it.message ?: "startScan threw")
        }.getOrDefault(false)
    }

    fun stopScan() {
        if (!scanning) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
        SpikeLog.log("S1: scan stopped")
    }

    /** Clears the dedupe set so the same peripheral can be exercised again (T6). */
    fun forgetSeen() {
        seenRefs.clear()
        SpikeLog.log("dedupe set cleared")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val raw = record.getManufacturerSpecificData(RoadLinkUuids.COMPANY_ID)

            // T11 evidence: dump exactly what went over the air.
            SpikeLog.log(
                "S1: HIT ${result.device.address} rssi=${result.rssi} " +
                    "mfgData=${SpikeLog.hex(raw)}"
            )

            val beacon = Beacon.decode(raw)
            if (beacon == null) {
                SpikeLog.log("S1: manufacturer data did not decode as a RoadLink beacon; ignoring")
                return
            }
            val ref = beacon.refHex()
            if (!seenRefs.add(ref)) return  // already acting on this event

            SpikeLog.log(
                "S1: beacon OK ref=$ref conf=${beacon.confidence} " +
                    "age=${beacon.ageSeconds}s simulated=${beacon.simulated} " +
                    "needsGatt=${beacon.needsGatt}"
            )
            events?.onBeaconDiscovered(result.device.address, result.rssi, beacon)

            stopScan()
            if (beacon.needsGatt) connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REGISTRATION_FAILED"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                else -> "UNKNOWN($errorCode)"
            }
            SpikeLog.logError("S1: scan failed - $reason")
            events?.onFailure("S1", reason)
        }
    }

    // ---------------------------------------------------------------- connect

    fun connect(device: BluetoothDevice) {
        targetDevice = device
        transferComplete = false
        SpikeLog.log("S2: connectGatt -> ${device.address} (attempt ${retryAttempt + 1})")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val address = g.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    SpikeLog.log("S2: CONNECTED to $address (status=$status)")
                    retryAttempt = 0
                    events?.onConnected(address)
                    // Best-effort. Long reads use ATT_READ_BLOB regardless, so
                    // nothing below depends on this succeeding.
                    g.requestMtu(517)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    SpikeLog.log("S5: DISCONNECTED from $address (status=$status)")
                    g.close()
                    gatt = null
                    val willRetry = !transferComplete && status != BluetoothGatt.GATT_SUCCESS
                    events?.onDisconnected(address, status, willRetry)
                    if (willRetry) scheduleRetry()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            SpikeLog.log("S3: MTU -> $mtu (status=$status)")
            SpikeLog.log("S2: discoverServices()")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                SpikeLog.logError("S2: service discovery failed status=$status")
                events?.onFailure("S2", "discoverServices status=$status")
                return
            }
            val service = g.getService(RoadLinkUuids.SERVICE)
            if (service == null) {
                SpikeLog.logError("S2: RoadLink service not present on peer")
                events?.onFailure("S2", "service not found")
                g.disconnect()
                return
            }
            SpikeLog.log("S2: services discovered, ${service.characteristics.size} characteristics")
            events?.onServicesDiscovered(g.device.address)

            val sos = service.getCharacteristic(RoadLinkUuids.CHAR_SOS_PACKET)
            if (sos == null) {
                events?.onFailure("S3", "SOS_PACKET characteristic missing")
                g.disconnect()
                return
            }
            SpikeLog.log("S3: reading SOS_PACKET")
            g.readCharacteristic(sos)
        }

        // API 33+ signature
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = handleRead(g, characteristic, value, status)

        // Pre-33 signature. Intentionally implemented: minSdk is 26, so this is
        // the live path on Android 8-12 devices, not dead legacy code.
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleRead(g, characteristic, characteristic.value ?: ByteArray(0), status)
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid == RoadLinkUuids.CCCD) {
                SpikeLog.log("S3: CCCD write complete status=$status; notifications live")
                writeAck(g)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid == RoadLinkUuids.CHAR_ACK) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    transferComplete = true
                    SpikeLog.log("S4: ACK write CONFIRMED by peripheral")
                    events?.onAckWritten(g.device.address)
                } else {
                    SpikeLog.logError("S4: ACK write failed status=$status")
                    events?.onFailure("S4", "ack write status=$status")
                }
            }
        }

        // API 33+ signature
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            SpikeLog.log("S3: NOTIFICATION ${value.size} B: \"${String(value, Charsets.UTF_8)}\"")
            events?.onNotification(g.device.address, value)
        }

        // Pre-33 signature. Live path on Android 8-12, see above.
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value ?: ByteArray(0)
                SpikeLog.log("S3: NOTIFICATION ${value.size} B: \"${String(value, Charsets.UTF_8)}\"")
                events?.onNotification(g.device.address, value)
            }
        }
    }

    private fun handleRead(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (characteristic.uuid != RoadLinkUuids.CHAR_SOS_PACKET) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            SpikeLog.logError("S3: SOS_PACKET read failed status=$status")
            events?.onFailure("S3", "read status=$status")
            return
        }
        SpikeLog.log("S3: SOS_PACKET read OK, ${value.size} bytes: \"${String(value, Charsets.UTF_8)}\"")
        events?.onPayloadRead(g.device.address, value)
        enableNotifications(g, characteristic)
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val descriptor = characteristic.getDescriptor(RoadLinkUuids.CCCD)
        if (descriptor == null) {
            SpikeLog.log("S3: no CCCD; skipping notifications and going straight to ACK")
            writeAck(g)
            return
        }
        g.setCharacteristicNotification(characteristic, true)
        SpikeLog.log("S3: enabling notifications (CCCD write)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(descriptor)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeAck(g: BluetoothGatt) {
        val ackChar = g.getService(RoadLinkUuids.SERVICE)
            ?.getCharacteristic(RoadLinkUuids.CHAR_ACK)
        if (ackChar == null) {
            events?.onFailure("S4", "ACK characteristic missing")
            return
        }
        val payload = "ACK|$relayId|${System.currentTimeMillis()}".toByteArray(Charsets.UTF_8)
        SpikeLog.log("S4: writing ACK (${payload.size} B)")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                ackChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else {
            ackChar.value = payload
            ackChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(ackChar)
        }
    }

    // ------------------------------------------------------------------ retry

    /** S5: exponential backoff 1s, 2s, 4s, 8s, 16s, capped at 30s. */
    private fun scheduleRetry() {
        val device = targetDevice ?: return
        if (retryAttempt >= MAX_RETRIES) {
            SpikeLog.logError("S5: giving up after $MAX_RETRIES reconnect attempts")
            events?.onFailure("S5", "max retries reached")
            return
        }
        val delay = (1000L shl retryAttempt).coerceAtMost(30_000L)
        retryAttempt += 1
        SpikeLog.log("S5: reconnect attempt $retryAttempt in ${delay}ms")
        handler.postDelayed({ connect(device) }, delay)
    }

    fun disconnect() {
        transferComplete = true   // suppress the retry path on an intentional close
        handler.removeCallbacksAndMessages(null)
        runCatching {
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = null
        retryAttempt = 0
        SpikeLog.log("S5: central disconnected by request")
    }

    fun stop() {
        stopScan()
        disconnect()
    }

    private companion object {
        const val MAX_RETRIES = 5
    }
}
