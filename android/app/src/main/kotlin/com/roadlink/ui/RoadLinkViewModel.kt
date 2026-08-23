package com.roadlink.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roadlink.AppContainer
import com.roadlink.ble.BleCapability
import com.roadlink.domain.DeliveryAttempt
import com.roadlink.domain.EmergencyEvent
import com.roadlink.domain.TransportKind
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

    private val _bleCapability = MutableStateFlow<BleCapability?>(null)
    val bleCapability: StateFlow<BleCapability?> = _bleCapability.asStateFlow()

    /** Relay (Phone B) role state. */
    val relayActive: StateFlow<Boolean> = container.relayCoordinator.active
    val relayStatus: StateFlow<String> = container.relayCoordinator.status
    val relayCollected: StateFlow<Int> = container.relayCoordinator.collectedCount

    private val _bleEnabled = MutableStateFlow(container.bleRelay.enabled)
    val bleEnabled: StateFlow<Boolean> = _bleEnabled.asStateFlow()

    /** DEMO ONLY. null = normal product behaviour, every transport in priority order. */
    private val _forcedTransport = MutableStateFlow<TransportKind?>(null)
    val forcedTransport: StateFlow<TransportKind?> = _forcedTransport.asStateFlow()

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

    private val _backendUrl = MutableStateFlow(container.backendConfig.url)
    val backendUrl: StateFlow<String> = _backendUrl.asStateFlow()

    val backendIsCustom: Boolean get() = container.backendConfig.isCustom

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
            // `busy` covers confirming and persisting, which is this button's
            // actual job and is fast. Delivery runs detached: a transport timing
            // out against an unreachable backend must never freeze the UI, and
            // the event is already safely on disk by this point.
            deliverInBackground()
        }
    }

    /** Run a delivery pass without holding [busy]. Safe: the pass is mutex-guarded. */
    private fun deliverInBackground() {
        viewModelScope.launch {
            runCatching { container.deliveryManager.runDeliveryPass() }
                .onFailure { container.logLine("delivery pass error: ${it.message}") }
            runCatching { refreshResponder() }
        }
    }

    /** Force an immediate delivery pass rather than waiting for the loop. */
    /**
     * Force an immediate delivery pass rather than waiting for the loop.
     *
     * Not gated on [busy]: against an unreachable backend a pass can spend tens
     * of seconds in connect timeouts, and disabling the UI for that long makes
     * the app look hung. The pass is mutex-guarded, so a repeated tap is
     * harmless.
     */
    fun deliverNow() {
        viewModelScope.launch {
            val result = runCatching { container.deliveryManager.runDeliveryPass() }.getOrNull()
            if (result != null) {
                container.logLine(
                    "delivery pass: considered=${result.considered} delivered=${result.delivered} stillQueued=${result.stillQueued}"
                )
            }
            runCatching { refreshResponder() }
        }
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

    /**
     * The S0 capability probe, in the product.
     *
     * Logs the full report verbatim so the result can be copied straight into
     * docs/verification-log.md rather than retyped from memory.
     */
    fun probeBle() = launchBusy {
        val capability = container.bleCapability()
        _bleCapability.value = capability
        container.logLine("S0 capability probe:\n${capability.render()}")
    }

    /**
     * Arm the physical BLE transport. Intended to be turned on only after S0
     * reports CAN ADVERTISE on this device.
     */
    fun setBleEnabled(value: Boolean) {
        container.bleRelay.enabled = value
        _bleEnabled.value = value
        container.logLine(
            if (value) "PHYSICAL BLE transport ARMED - results from here are physical, not simulated"
            else "PHYSICAL BLE transport disarmed"
        )
    }

    /** Turn this phone into a relay for riders nearby. */
    fun setRelayMode(value: Boolean) {
        if (value) {
            val error = container.relayCoordinator.start()
            if (error != null) container.logLine("relay mode could not start: $error")
        } else {
            container.relayCoordinator.stop()
        }
    }

    fun forgetRelayCollected() = container.relayCoordinator.forgetCollected()

    /**
     * DEMO ONLY. Pin delivery to a single transport so a live demonstration is
     * deterministic. null restores normal product behaviour.
     */
    fun setForcedTransport(kind: TransportKind?) {
        container.deliveryManager.forcedTransport = kind
        _forcedTransport.value = kind
        container.logLine(
            kind?.let { "delivery pinned to ${it.label} (${it.fidelity}) for demo determinism" }
                ?: "delivery restored to automatic transport selection"
        )
    }

    /**
     * Point this install at a different backend. Takes effect on the next
     * request; no restart, and nothing already queued is lost.
     */
    fun setBackendUrl(value: String) {
        val cleaned = com.roadlink.platform.BackendConfig.normalise(value)
        if (cleaned == null) {
            container.logLine("backend address ignored: \"$value\" is not a usable URL")
            return
        }
        // Written synchronously, and deliberately NOT inside launchBusy. The
        // reachability check that follows can take seconds against a wrong
        // address, and holding `busy` across it used to disable the very card
        // this came from. The address must be applied the instant it is typed.
        container.backendConfig.url = cleaned
        _backendUrl.value = container.backendConfig.url
        container.logLine("backend address set to ${container.backendConfig.url}")
        viewModelScope.launch { runCatching { refreshResponder() } }
    }

    fun resetBackendUrl() {
        container.backendConfig.reset()
        _backendUrl.value = container.backendConfig.url
        container.logLine("backend address reset to the build default ${container.backendConfig.url}")
        viewModelScope.launch { runCatching { refreshResponder() } }
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
