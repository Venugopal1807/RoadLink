package com.roadlink.delivery

import android.content.Context
import com.roadlink.ble.BleCapability
import com.roadlink.ble.BleCentral
import com.roadlink.domain.Clock
import com.roadlink.domain.DeliveryState
import com.roadlink.domain.EmergencyEvent
import com.roadlink.domain.EmergencyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phone B - the relay role.
 *
 * Listens for riders in range, takes custody of their emergencies, and stores
 * them. It deliberately does NOT contain any forwarding logic: once a foreign
 * emergency is in the store it is picked up by the ordinary [DeliveryManager]
 * loop and uploaded by the ordinary [DirectNetworkTransport], exactly like one
 * of this device's own. That is what makes "the relay forwards it when it gets
 * connectivity" true without a second delivery implementation existing.
 *
 * The relay is a role a phone plays, not a separate mode of the product.
 */
class RelayCoordinator(
    private val context: Context,
    private val store: EmergencyStore,
    private val deliveryManager: DeliveryManager,
    private val scope: CoroutineScope,
    private val relayId: String,
    private val clock: Clock = Clock.System,
    private val log: (String) -> Unit = {},
) {

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _status = MutableStateFlow("idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _collectedCount = MutableStateFlow(0)
    val collectedCount: StateFlow<Int> = _collectedCount.asStateFlow()

    private val central: BleCentral by lazy {
        BleCentral(context, scope, relayId, log).apply {
            onStateChanged = { _status.value = it }
            onCollected = ::storeCollectedEmergency
        }
    }

    fun capability(): BleCapability = BleCapability.probe(context)

    /** Returns null on success, or a human-readable reason it could not start. */
    fun start(): String? {
        if (_active.value) return null
        val error = central.start()
        if (error != null) {
            _status.value = error
            log("RELAY could not start: $error")
            return error
        }
        _active.value = true
        _status.value = "scanning"
        log("RELAY mode ON - this phone will carry emergencies for riders nearby")
        return null
    }

    fun stop() {
        if (!_active.value) return
        central.stop()
        _active.value = false
        _status.value = "idle"
        log("RELAY mode OFF")
    }

    /** Lets the same rider be collected again during a repeatability run (T6). */
    fun forgetCollected() = central.forgetCollected()

    /**
     * Store an emergency collected from another phone.
     *
     * Returns true only once it is durably on disk, because [BleCentral] sends
     * the ACK on the strength of this return value and the rider may stop
     * advertising once acknowledged. Returning true without a successful write
     * is the one way this design could actually lose an SOS.
     *
     * An emergency already held here is treated as stored: the rider re-offered
     * something we already have, so acknowledging is both honest and useful.
     */
    private suspend fun storeCollectedEmergency(event: EmergencyEvent): Boolean {
        val existing = store.get(event.eventId)
        if (existing != null) {
            log("RELAY already holding ${event.eventId.take(8)}; acknowledging again")
            return true
        }

        // Marked as someone else's emergency and queued for the normal delivery
        // loop. The signature and every signed field are preserved exactly as
        // the rider produced them - a relay never re-signs.
        val custody = event.copy(
            state = DeliveryState.CONFIRMED_EMERGENCY,
            collectedAsRelay = true,
            attemptCount = 0,
            lastAttemptAt = null,
            lastError = null,
        )

        return runCatching {
            deliveryManager.submit(custody)
            _collectedCount.value = _collectedCount.value + 1
            log("RELAY took custody of ${event.eventId.take(8)} from rider ${event.riderId}; stored and queued for upload")
            true
        }.getOrElse { error ->
            log("RELAY failed to store ${event.eventId.take(8)}: ${error.message}")
            false
        }
    }
}
