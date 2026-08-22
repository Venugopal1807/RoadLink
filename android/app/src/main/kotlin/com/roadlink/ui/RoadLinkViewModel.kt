package com.roadlink.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roadlink.AppContainer
import com.roadlink.delivery.BleRelayTransport
import com.roadlink.domain.DeliveryAttempt
import com.roadlink.domain.EmergencyEvent
import com.roadlink.net.ResponderEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * UI state holder.
 *
 * The rider view reads local state - what this phone knows and is holding.
 * The responder view reads the BACKEND, because the claim being demonstrated
 * is that the SOS arrived, and only the server can attest to that.
 */
class RoadLinkViewModel(private val container: AppContainer) : ViewModel() {

    /** Everything this device is holding, delivered or not. */
    val events: StateFlow<List<EmergencyEvent>> =
        container.store.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val log: StateFlow<List<String>> = container.log.asStateFlow()

    private val _responderEvents = MutableStateFlow<List<ResponderEvent>>(emptyList())
    val responderEvents: StateFlow<List<ResponderEvent>> = _responderEvents.asStateFlow()

    private val _backendReachable = MutableStateFlow<Boolean?>(null)
    val backendReachable: StateFlow<Boolean?> = _backendReachable.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _attempts = MutableStateFlow<Map<String, List<DeliveryAttempt>>>(emptyMap())
    val attempts: StateFlow<Map<String, List<DeliveryAttempt>>> = _attempts.asStateFlow()

    private val _bleCapability = MutableStateFlow<BleRelayTransport.Capability?>(null)
    val bleCapability: StateFlow<BleRelayTransport.Capability?> = _bleCapability.asStateFlow()

    // ---- development switches, surfaced directly in the UI -----------------

    private val _relayInRange = MutableStateFlow(container.simulatedRelay.relayInRange)
    val relayInRange: StateFlow<Boolean> = _relayInRange.asStateFlow()

    private val _relayHasNetwork = MutableStateFlow(container.simulatedRelay.relayHasNetwork)
    val relayHasNetwork: StateFlow<Boolean> = _relayHasNetwork.asStateFlow()

    private val _forcedOffline = MutableStateFlow(container.connectivity.forcedOffline)
    val forcedOffline: StateFlow<Boolean> = _forcedOffline.asStateFlow()

    private val _scriptedFailures = MutableStateFlow(container.simulatedRelay.failuresBeforeSuccess)
    val scriptedFailures: StateFlow<Int> = _scriptedFailures.asStateFlow()

    val riderId: String get() = container.riderId
    val backendUrl: String get() = com.roadlink.BuildConfig.BACKEND_BASE_URL

    init {
        // Keep the responder view and the attempt history fresh without the
        // user having to pull anything.
        viewModelScope.launch {
            while (isActive) {
                refreshResponder()
                refreshAttempts()
                delay(3_000)
            }
        }
    }

    // ------------------------------------------------------------- actions

    /**
     * CREATE TEST SOS.
     *
     * Goes through the identical pipeline a real sensor trigger will use:
     * signed, persisted, queued, then delivered by whichever transport is
     * available. Only the trigger is simulated, and that fact is stamped into
     * the signed packet.
     */
    fun createTestSos() = launchBusy {
        container.logLine("CREATE TEST SOS pressed - trigger is SIMULATED, pipeline is real")
        val event = container.emergencyController.confirmFrom(container.testCrashDetector)
        if (event != null) {
            container.logLine("event ${event.eventId.take(8)} confirmed and persisted, now ${event.statusLabel}")
            container.deliveryManager.runDeliveryPass()
        }
        refreshResponder()
    }

    /** Force an immediate delivery pass rather than waiting for the loop. */
    fun deliverNow() = launchBusy {
        val result = container.deliveryManager.runDeliveryPass()
        container.logLine(
            "delivery pass: considered=${result.considered} delivered=${result.delivered} stillQueued=${result.stillQueued}"
        )
        refreshResponder()
    }

    /**
     * Deliberately submit an already-delivered event a second time over the
     * direct network path, demonstrating backend idempotency: one emergency,
     * two delivery records.
     */
    fun redeliverDirect(event: EmergencyEvent) = launchBusy {
        container.deliveryManager.redeliver(event, container.directNetwork)
        refreshResponder()
    }

    fun setRelayInRange(value: Boolean) {
        container.simulatedRelay.relayInRange = value
        _relayInRange.value = value
        container.logLine("SIMULATED relay in range = $value")
    }

    fun setRelayHasNetwork(value: Boolean) {
        container.simulatedRelay.relayHasNetwork = value
        _relayHasNetwork.value = value
        container.logLine("SIMULATED relay has connectivity = $value")
    }

    fun setForcedOffline(value: Boolean) {
        container.connectivity.forcedOffline = value
        _forcedOffline.value = value
        container.logLine("rider network forced offline = $value")
    }

    fun setScriptedFailures(value: Int) {
        val clamped = value.coerceIn(0, 5)
        container.simulatedRelay.failuresBeforeSuccess = clamped
        _scriptedFailures.value = clamped
        container.logLine("SIMULATED relay scripted failures = $clamped")
    }

    fun resetSimulator() {
        container.simulatedRelay.reset()
        container.logLine("simulator script reset")
    }

    fun probeBle() = launchBusy {
        val capability = container.bleRelay.probe()
        _bleCapability.value = capability
        container.logLine(
            "BLE probe on ${capability.deviceModel} (API ${capability.apiLevel}): " +
                "canAdvertise=${capability.canAdvertise} " +
                (capability.blocker?.let { "blocker=$it" } ?: "peripheral role available")
        )
    }

    suspend fun refreshResponder() {
        _backendReachable.value = runCatching { container.api.health() }.getOrDefault(false)
        _responderEvents.value = runCatching { container.api.activeEmergencies() }.getOrDefault(emptyList())
    }

    private suspend fun refreshAttempts() {
        val current = events.value
        if (current.isEmpty()) return
        _attempts.value = current.associate { it.eventId to container.store.attempts(it.eventId) }
    }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { block() }.onFailure {
                container.logLine("ERROR: ${it.message ?: it.javaClass.simpleName}")
            }
            _busy.value = false
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RoadLinkViewModel(container) as T
    }
}
