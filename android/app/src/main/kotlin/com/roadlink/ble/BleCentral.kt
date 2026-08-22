package com.roadlink.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.roadlink.domain.EmergencyEvent
import com.roadlink.net.SosPacketCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Phone B - the relay. BLE central: scanner plus GATT client.
 *
 * Collects an emergency from a rider in range, stores it, acknowledges it, and
 * leaves forwarding to the normal delivery machinery. The relay is not a
 * special case of the product: once it has stored a foreign emergency, that
 * emergency goes through the same DeliveryManager, the same retry logic and the
 * same backend as one of its own.
 *
 * Adapted from the spike's CentralRole. The notification/CCCD step is dropped
 * (it was an S3 diagnostic), and the ACK is deferred until the packet is
 * genuinely on disk - see [onCollected].
 */
@SuppressLint("MissingPermission")
class BleCentral(
    private val context: Context,
    private val scope: CoroutineScope,
    private val relayId: String,
    private val log: (String) -> Unit = {},
) {

    /**
     * Called once a verified packet has been read.
     *
     * MUST return true only when the emergency is durably stored. The ACK is
     * sent only on true, because an ACK tells the rider its emergency is safe
     * somewhere else - and the rider may stop advertising on the strength of
     * it. Acknowledging before storing would be the one way this design could
     * actually lose an SOS.
     */
    var onCollected: (suspend (EmergencyEvent) -> Boolean)? = null

    /** Reported for the UI; not used for any decision. */
    var onStateChanged: ((String) -> Unit)? = null

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var retryAttempt = 0
    private var targetDevice: BluetoothDevice? = null

    /** event_refs already collected, so we do not reconnect for the same emergency. */
    private val collectedRefs = mutableSetOf<String>()

    @Volatile
    private var pendingRef: String? = null

    @Volatile
    private var discoveredAt: Long = 0L

    val isScanning: Boolean get() = scanning

    // ------------------------------------------------------------------ scan

    /** Returns null on success, or a human-readable reason it could not start. */
    fun start(): String? {
        val capability = BleCapability.probe(context)
        if (!capability.adapterEnabled) return "Bluetooth is off"
        if (!capability.scannerAvailable) return "no LE scanner on this device"
        missingPermission()?.let { return "missing permission: $it" }

        val scanner = manager?.adapter?.bluetoothLeScanner ?: return "scanner unavailable"
        if (scanning) return null

        // Hardware-offloaded filter on the service UUID, which is why the UUID
        // must be in AdvData rather than in the scan response.
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(RoadLinkUuids.SERVICE)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .build()

        return runCatching {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
            log("RELAY scanning for riders in range")
            onStateChanged?.invoke("scanning")
            null
        }.getOrElse { it.message ?: "startScan threw" }
    }

    fun stop() {
        stopScan()
        disconnect()
        onStateChanged?.invoke("stopped")
    }

    private fun stopScan() {
        if (!scanning) return
        runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
    }

    /** Allows the same rider to be collected again, for repeatability runs (T6). */
    fun forgetCollected() {
        collectedRefs.clear()
        log("RELAY dedupe set cleared")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val raw = record.getManufacturerSpecificData(RoadLinkUuids.COMPANY_ID)
            val beacon = Beacon.decode(raw) ?: return

            val ref = beacon.refHex()
            // Dedupe on event_ref, never on MAC: Android randomises the
            // advertising address, so the address is not a stable identity.
            if (!collectedRefs.add(ref)) return

            discoveredAt = SystemClock.elapsedRealtime()
            log(
                "RELAY found emergency ref=$ref rssi=${result.rssi} conf=${beacon.confidence} " +
                    "age=${beacon.ageSeconds}s simulated=${beacon.simulated}"
            )
            pendingRef = ref
            onStateChanged?.invoke("connecting")

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
            log("RELAY scan failed: $reason")
            onStateChanged?.invoke("scan failed: $reason")
        }
    }

    // --------------------------------------------------------------- connect

    private fun connect(device: BluetoothDevice) {
        targetDevice = device
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    retryAttempt = 0
                    log("RELAY connected to ${g.device.address}")
                    // Best effort. Long reads fall back to ATT_READ_BLOB, so
                    // nothing downstream depends on this succeeding.
                    g.requestMtu(517)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    runCatching { g.close() }
                    gatt = null
                    // A drop before we have the packet is retried; the rider
                    // keeps advertising and nothing is lost either way.
                    if (pendingRef != null) {
                        log("RELAY disconnected mid-transfer (status=$status)")
                        scheduleRetry()
                    } else {
                        resumeScanning()
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("RELAY MTU -> $mtu")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("RELAY service discovery failed (status=$status)")
                g.disconnect()
                return
            }
            val characteristic = g.getService(RoadLinkUuids.SERVICE)
                ?.getCharacteristic(RoadLinkUuids.CHAR_SOS_PACKET)
            if (characteristic == null) {
                log("RELAY peer does not expose the RoadLink SOS characteristic")
                g.disconnect()
                return
            }
            g.readCharacteristic(characteristic)
        }

        // API 33+ signature
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = handleRead(g, characteristic, value, status)

        // Pre-33 signature. minSdk is 26, so this is the live path on Android
        // 8-12, not dead legacy code.
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

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != RoadLinkUuids.CHAR_ACK) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("RELAY ACK confirmed by rider; handoff complete")
                onStateChanged?.invoke("collected")
            } else {
                log("RELAY ACK write failed (status=$status)")
            }
            pendingRef = null
            g.disconnect()
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
            log("RELAY packet read failed (status=$status)")
            g.disconnect()
            return
        }

        val elapsed = SystemClock.elapsedRealtime() - discoveredAt
        val event = SosPacketCodec.decode(value)
        if (event == null) {
            log("RELAY packet did not parse; dropping rather than forwarding a guess")
            pendingRef = null
            g.disconnect()
            return
        }

        // Verify before forwarding. Relaying an unverifiable packet would let
        // any nearby device inject emergencies into the backend through an
        // honest relay.
        if (!SosPacketCodec.verify(event)) {
            log("RELAY signature invalid for ${event.eventId.take(8)}; dropped, not forwarded")
            pendingRef = null
            g.disconnect()
            return
        }

        log("RELAY read verified packet ${event.eventId.take(8)} (${value.size} B) in ${elapsed}ms")

        // Store first, acknowledge second. The ACK is a promise that this
        // emergency is safe here.
        scope.launch {
            val stored = runCatching { onCollected?.invoke(event) ?: false }.getOrElse { error ->
                log("RELAY could not store ${event.eventId.take(8)}: ${error.message}")
                false
            }
            if (stored) {
                writeAck(g, event.eventId)
            } else {
                log("RELAY did NOT store ${event.eventId.take(8)}; withholding ACK so the rider keeps trying")
                pendingRef = null
                runCatching { g.disconnect() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeAck(g: BluetoothGatt, eventId: String) {
        val ackChar = g.getService(RoadLinkUuids.SERVICE)
            ?.getCharacteristic(RoadLinkUuids.CHAR_ACK)
        if (ackChar == null) {
            log("RELAY rider does not expose an ACK characteristic")
            pendingRef = null
            return
        }
        val payload = BleAck(eventId, relayId, System.currentTimeMillis()).encode()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ackChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                ackChar.value = payload
                ackChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                g.writeCharacteristic(ackChar)
            }
        }.onFailure { log("RELAY ACK write threw: ${it.message}") }
    }

    // ----------------------------------------------------------------- retry

    /** Exponential backoff 1s, 2s, 4s, 8s, 16s, capped at 30s. Matches the spike. */
    private fun scheduleRetry() {
        val device = targetDevice
        if (device == null || retryAttempt >= MAX_RETRIES) {
            log("RELAY giving up on this peer; resuming scan")
            pendingRef?.let { collectedRefs.remove(it) }  // let it be found again
            pendingRef = null
            resumeScanning()
            return
        }
        val delay = (1_000L shl retryAttempt).coerceAtMost(30_000L)
        retryAttempt += 1
        log("RELAY reconnect attempt $retryAttempt in ${delay}ms")
        handler.postDelayed({ connect(device) }, delay)
    }

    private fun resumeScanning() {
        retryAttempt = 0
        targetDevice = null
        handler.postDelayed({ if (!scanning) start() }, RESUME_SCAN_DELAY_MS)
    }

    private fun disconnect() {
        handler.removeCallbacksAndMessages(null)
        runCatching {
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = null
        pendingRef = null
        retryAttempt = 0
    }

    private fun missingPermission(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // On API <= 30 LE scanning is gated behind fine location.
            return if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) null else "ACCESS_FINE_LOCATION"
        }
        val needed = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        return needed.firstOrNull {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }?.substringAfterLast('.')
    }

    private companion object {
        const val MAX_RETRIES = 5
        const val RESUME_SCAN_DELAY_MS = 1_000L
    }
}
