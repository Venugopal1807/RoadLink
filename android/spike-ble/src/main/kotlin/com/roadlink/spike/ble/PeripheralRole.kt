package com.roadlink.spike.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.roadlink.spike.SpikeLog

/**
 * Phone A - the rider. BLE peripheral: advertiser + GATT server.
 *
 * This is the role that differs from a conventional phone-as-central design.
 * The phone in distress announces itself; the helper reaches out. Everything
 * here depends on the S0 probe having reported canAdvertise = true.
 *
 * S0-S5 scope: the served payload is a plain test string. The real signed
 * SosPacket arrives at S6, once this transport is proven on hardware.
 */
@SuppressLint("MissingPermission")
class PeripheralRole(private val context: Context) {

    interface Events {
        fun onAdvertisingStarted(beacon: Beacon)
        fun onAdvertisingFailed(reason: String)
        fun onCentralConnected(address: String)
        fun onCentralDisconnected(address: String)
        fun onPacketRead(address: String)
        fun onAckReceived(address: String, ack: String)
        fun onNotificationsEnabled(address: String, enabled: Boolean)
    }

    private val manager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = manager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var sosCharacteristic: BluetoothGattCharacteristic? = null

    private val subscribers = mutableSetOf<BluetoothDevice>()

    var events: Events? = null
    var isAdvertising: Boolean = false
        private set

    /** The payload served over GATT. Replaced by the real SosPacket at S6. */
    var servedPayload: ByteArray = "ROADLINK-S3-TEST-PAYLOAD".toByteArray(Charsets.UTF_8)

    // ------------------------------------------------------------ GATT server

    fun start(beacon: Beacon): Boolean {
        if (adapter?.isEnabled != true) {
            events?.onAdvertisingFailed("Bluetooth is off")
            return false
        }
        if (adapter?.bluetoothLeAdvertiser == null) {
            events?.onAdvertisingFailed("This device cannot advertise (no LE peripheral role)")
            return false
        }
        return openGattServer() && startAdvertising(beacon)
    }

    private fun openGattServer(): Boolean {
        SpikeLog.log("S2: opening GATT server")
        val server = manager.openGattServer(context, serverCallback)
        if (server == null) {
            SpikeLog.logError("openGattServer returned null")
            return false
        }
        gattServer = server

        val service = BluetoothGattService(
            RoadLinkUuids.SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        val sos = BluetoothGattCharacteristic(
            RoadLinkUuids.CHAR_SOS_PACKET,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    RoadLinkUuids.CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE,
                )
            )
        }
        sosCharacteristic = sos

        val ack = BluetoothGattCharacteristic(
            RoadLinkUuids.CHAR_ACK,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val meta = BluetoothGattCharacteristic(
            RoadLinkUuids.CHAR_SPIKE_META,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )

        service.addCharacteristic(sos)
        service.addCharacteristic(ack)
        service.addCharacteristic(meta)

        val added = server.addService(service)
        SpikeLog.log("S2: addService -> $added")
        return added
    }

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    SpikeLog.log("S2: central CONNECTED ${device.address} (status=$status)")
                    events?.onCentralConnected(device.address)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    SpikeLog.log("S5: central DISCONNECTED ${device.address} (status=$status)")
                    subscribers.remove(device)
                    events?.onCentralDisconnected(device.address)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            // Recorded, not required. Long reads fall back to ATT_READ_BLOB,
            // so correctness must never depend on the MTU request succeeding.
            SpikeLog.log("S3: MTU negotiated with ${device.address} -> $mtu")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value: ByteArray = when (characteristic.uuid) {
                RoadLinkUuids.CHAR_SOS_PACKET -> servedPayload
                RoadLinkUuids.CHAR_SPIKE_META ->
                    "${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}"
                        .toByteArray(Charsets.UTF_8)
                else -> ByteArray(0)
            }

            // offset handling is what makes a >MTU read work via ATT_READ_BLOB
            val slice = if (offset >= value.size) ByteArray(0)
            else value.copyOfRange(offset, value.size)

            SpikeLog.log(
                "S3: read request ${characteristic.uuid.toString().take(8)} " +
                    "from ${device.address} offset=$offset -> ${slice.size} bytes"
            )
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)

            if (characteristic.uuid == RoadLinkUuids.CHAR_SOS_PACKET && offset == 0) {
                events?.onPacketRead(device.address)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == RoadLinkUuids.CHAR_ACK) {
                val ack = String(value, Charsets.UTF_8)
                SpikeLog.log("S4: ACK received from ${device.address}: \"$ack\"")
                events?.onAckReceived(device.address, ack)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value
                )
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == RoadLinkUuids.CCCD) {
                val enabled = value.contentEquals(
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
                if (enabled) subscribers.add(device) else subscribers.remove(device)
                SpikeLog.log("S3: notifications ${if (enabled) "ENABLED" else "disabled"} by ${device.address}")
                events?.onNotificationsEnabled(device.address, enabled)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value
                )
            }
        }
    }

    /** S3: push a value to every subscribed central. */
    @Suppress("DEPRECATION")
    fun notifySubscribers(message: String) {
        val characteristic = sosCharacteristic ?: return
        val server = gattServer ?: return
        val payload = message.toByteArray(Charsets.UTF_8)

        if (subscribers.isEmpty()) {
            SpikeLog.log("S3: notify skipped, no subscribers")
            return
        }
        subscribers.toList().forEach { device ->
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, characteristic, false, payload)
                    .let { it == BluetoothStatusCodesCompat.SUCCESS }
            } else {
                characteristic.value = payload
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
            SpikeLog.log("S3: notified ${device.address} -> $result")
        }
    }

    // ------------------------------------------------------------ advertising

    private fun startAdvertising(beacon: Beacon): Boolean {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return false

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)   // required: the central must be able to connect
            .setTimeout(0)          // 0 = advertise until explicitly stopped
            .build()

        // AdvData: Flags (3, added by the stack) + 128-bit service UUID (18) = 21 of 31.
        // The service UUID must be HERE, not in the scan response, so a scanner
        // can filter on it in hardware.
        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)    // privacy: never leak the device name
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(RoadLinkUuids.SERVICE))
            .build()

        // ScanResponse: a separate 31 bytes, no Flags needed. The 13-byte beacon
        // lives here because AdvData has only ~10 bytes left after the UUID.
        val encoded = beacon.encode()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(RoadLinkUuids.COMPANY_ID, encoded)
            .build()

        SpikeLog.log("S1: beacon payload (${encoded.size} B) = ${SpikeLog.hex(encoded)}")
        SpikeLog.log("S1: eventRef = ${beacon.refHex()}")

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                isAdvertising = true
                SpikeLog.log("S1: ADVERTISING started (mode=${settingsInEffect?.mode}, txPower=${settingsInEffect?.txPowerLevel})")
                events?.onAdvertisingStarted(beacon)
            }

            override fun onStartFailure(errorCode: Int) {
                isAdvertising = false
                val reason = RadioCapabilities.describeAdvertiseError(errorCode)
                SpikeLog.logError("S1: advertising FAILED - $reason")
                events?.onAdvertisingFailed(reason)
            }
        }
        advertiseCallback = callback

        return runCatching {
            advertiser.startAdvertising(settings, advData, scanResponse, callback)
            true
        }.onFailure {
            SpikeLog.logError("S1: startAdvertising threw", it)
        }.getOrDefault(false)
    }

    fun stop() {
        advertiseCallback?.let { cb ->
            runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) }
            advertiseCallback = null
        }
        isAdvertising = false
        subscribers.clear()
        runCatching {
            gattServer?.close()
        }
        gattServer = null
        sosCharacteristic = null
        SpikeLog.log("S1/S2: peripheral stopped (advertising off, GATT server closed)")
    }
}

/** BluetoothStatusCodes.SUCCESS without requiring API 33 at compile time. */
internal object BluetoothStatusCodesCompat {
    const val SUCCESS = 0
}
