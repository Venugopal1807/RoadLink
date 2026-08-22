package com.roadlink.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.roadlink.domain.EmergencyEvent
import com.roadlink.net.SosPacketCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phone A - the rider. BLE peripheral: advertiser plus GATT server.
 *
 * This is the role that differs from a conventional phone-as-central design:
 * the phone in distress announces itself and the helper reaches out. It works
 * only where `getBluetoothLeAdvertiser()` is non-null, which is a per-chipset
 * fact rather than an OS guarantee - see [BleCapability].
 *
 * Adapted from the S0-S5 spike's PeripheralRole, with two changes. The served
 * payload is the real signed SOS packet rather than a test string, and the
 * notification/CCCD step is dropped: it was an S3 diagnostic, and removing it
 * takes a failure mode out of the delivery path without changing the protocol.
 */
@SuppressLint("MissingPermission")
class BlePeripheral(
    private val context: Context,
    private val log: (String) -> Unit = {},
) {

    /** The result of offering one emergency to any relay in range. */
    sealed interface Outcome {
        /** A relay took the packet and confirmed it. The only success. */
        data class Acked(
            val relayId: String,
            val ackAtMillis: Long,
            val elapsedMs: Long,
        ) : Outcome

        /** Advertised, but nobody collected it before the timeout. Retryable. */
        data class NotCollected(val waitedMs: Long) : Outcome

        /** This device cannot take the peripheral role at all. */
        data class Unavailable(val reason: String) : Outcome

        /** Something went wrong while advertising or serving. Retryable. */
        data class Failed(val reason: String) : Outcome
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private var gattServer: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null

    /** The packet currently being served. Replaced per offered event. */
    @Volatile
    private var servedPayload: ByteArray = ByteArray(0)

    /** The event we are currently advertising, so a stray ACK cannot mark the wrong one. */
    @Volatile
    private var currentEventId: String? = null

    @Volatile
    private var ackSignal: CompletableDeferred<BleAck>? = null

    @Volatile
    var isAdvertising: Boolean = false
        private set

    /**
     * Advertise [event] and wait for a relay to collect and acknowledge it.
     *
     * Bounded by [timeoutMs] rather than advertising indefinitely: the caller
     * is a delivery pass that must be free to try other transports. A timeout
     * is not a failure of the emergency - the event stays queued and is
     * offered again on the next pass.
     */
    suspend fun offerEvent(event: EmergencyEvent, timeoutMs: Long): Outcome {
        val capability = BleCapability.probe(context)
        capability.peripheralBlocker?.let { return Outcome.Unavailable(it) }
        missingPermission()?.let { return Outcome.Unavailable("missing permission: $it") }

        val payload = SosPacketCodec.encodeBytes(event)
        if (payload.size > SosPacketCodec.MAX_PACKET_BYTES) {
            // A GATT characteristic value cannot exceed 512 bytes. Better to
            // report this plainly than to truncate an emergency.
            return Outcome.Failed("packet is ${payload.size} B, over the ${SosPacketCodec.MAX_PACKET_BYTES} B GATT limit")
        }

        val started = SystemClock.elapsedRealtime()
        val signal = CompletableDeferred<BleAck>()

        servedPayload = payload
        currentEventId = event.eventId
        ackSignal = signal

        try {
            if (!openGattServer()) return Outcome.Failed("could not open GATT server")

            val beacon = Beacon.forEvent(event, System.currentTimeMillis())
            val advertiseError = startAdvertising(beacon)
            if (advertiseError != null) return Outcome.Failed(advertiseError)

            log("BLE advertising ${event.eventId.take(8)} ref=${beacon.refHex()} simulated=${beacon.simulated}; waiting up to ${timeoutMs}ms for a relay")

            val ack = withTimeoutOrNull(timeoutMs) { signal.await() }
            val elapsed = SystemClock.elapsedRealtime() - started

            return if (ack == null) {
                Outcome.NotCollected(elapsed)
            } else {
                log("BLE ACK for ${event.eventId.take(8)} from relay ${ack.relayId} after ${elapsed}ms")
                Outcome.Acked(relayId = ack.relayId, ackAtMillis = ack.atMillis, elapsedMs = elapsed)
            }
        } catch (error: Throwable) {
            return Outcome.Failed(error.message ?: error.javaClass.simpleName)
        } finally {
            stop()
            ackSignal = null
            currentEventId = null
            servedPayload = ByteArray(0)
        }
    }

    // ------------------------------------------------------------ GATT server

    private fun openGattServer(): Boolean {
        val server = manager?.openGattServer(context, serverCallback) ?: return false
        gattServer = server

        val service = BluetoothGattService(
            RoadLinkUuids.SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                RoadLinkUuids.CHAR_SOS_PACKET,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                RoadLinkUuids.CHAR_ACK,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                RoadLinkUuids.CHAR_META,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        )
        return server.addService(service)
    }

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED ->
                    log("BLE relay connected: ${device.address}")
                BluetoothProfile.STATE_DISCONNECTED ->
                    log("BLE relay disconnected: ${device.address} (status=$status)")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value: ByteArray = when (characteristic.uuid) {
                RoadLinkUuids.CHAR_SOS_PACKET -> servedPayload
                RoadLinkUuids.CHAR_META ->
                    "${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}"
                        .toByteArray(Charsets.UTF_8)
                else -> ByteArray(0)
            }
            // Honouring `offset` is what makes a value larger than the MTU
            // transfer correctly via ATT_READ_BLOB, so correctness never
            // depends on the MTU negotiation having succeeded.
            val slice = if (offset >= value.size) ByteArray(0) else value.copyOfRange(offset, value.size)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)

            if (characteristic.uuid == RoadLinkUuids.CHAR_SOS_PACKET && offset == 0) {
                log("BLE packet read by ${device.address} (${value.size} B)")
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
                val ack = BleAck.decode(value)
                val expected = currentEventId
                when {
                    ack == null ->
                        log("BLE malformed ACK from ${device.address}; ignored")
                    // A relay that acknowledges a different emergency must not
                    // mark this one delivered.
                    expected != null && ack.eventId != expected ->
                        log("BLE ACK for ${ack.eventId.take(8)} does not match the advertised ${expected.take(8)}; ignored")
                    else -> ackSignal?.complete(ack)
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    // ------------------------------------------------------------ advertising

    /** Returns null on success, or a human-readable reason. */
    private suspend fun startAdvertising(beacon: Beacon): String? {
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser
            ?: return "no LE advertiser on this device"

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // The service UUID must live in AdvData so a scanner can filter for it
        // in hardware. Device name is never included - it is rider-identifying.
        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(RoadLinkUuids.SERVICE))
            .build()

        // The 13-byte beacon rides in the scan response, which has its own
        // separate 31-byte budget. No identity, no coordinates, no event_id.
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(RoadLinkUuids.COMPANY_ID, beacon.encode())
            .build()

        val result = CompletableDeferred<String?>()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                isAdvertising = true
                result.complete(null)
            }

            override fun onStartFailure(errorCode: Int) {
                isAdvertising = false
                result.complete("advertising failed: ${describeAdvertiseError(errorCode)}")
            }
        }
        advertiseCallback = callback

        return runCatching {
            advertiser.startAdvertising(settings, advData, scanResponse, callback)
            // If the stack never calls back, treat it as a failure rather than
            // hanging the whole delivery pass.
            withTimeoutOrNull(ADVERTISE_START_TIMEOUT_MS) { result.await() }
                ?: "advertising did not start within ${ADVERTISE_START_TIMEOUT_MS}ms"
        }.getOrElse { it.message ?: "startAdvertising threw" }
    }

    fun stop() {
        advertiseCallback?.let { callback ->
            runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
            advertiseCallback = null
        }
        isAdvertising = false
        runCatching { gattServer?.close() }
        gattServer = null
    }

    private fun missingPermission(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val needed = listOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        return needed.firstOrNull {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }?.substringAfterLast('.')
    }

    companion object {
        private const val ADVERTISE_START_TIMEOUT_MS = 5_000L

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
