package com.roadlink

import android.app.Application
import android.content.Context
import com.roadlink.ble.BleCapability
import com.roadlink.data.EmergencyDatabase
import com.roadlink.data.RoomEmergencyStore
import com.roadlink.delivery.BleRelayTransport
import com.roadlink.delivery.DeliveryManager
import com.roadlink.delivery.DirectNetworkTransport
import com.roadlink.delivery.RelayCoordinator
import com.roadlink.delivery.SimulatedRelayTransport
import com.roadlink.delivery.Transport
import com.roadlink.domain.Clock
import com.roadlink.domain.EmergencyController
import com.roadlink.domain.EmergencyStore
import com.roadlink.domain.TestCrashDetector
import com.roadlink.net.SosApiClient
import com.roadlink.platform.AndroidConnectivity
import com.roadlink.platform.AndroidLocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/**
 * Manual dependency container.
 *
 * A hackathon MVP with one module does not need a DI framework; the object
 * graph is small enough to read top to bottom, which is worth more here than
 * indirection.
 */
class RoadLinkApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Start the retry loop immediately. Any emergency left queued by a
        // previous run - including one the process was killed during - is
        // picked up here without anyone asking.
        container.deliveryManager.start(container.scope)
    }
}

class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val scope = CoroutineScope(SupervisorJob())

    /** Rolling in-app log. Every line states REAL or SIMULATED explicitly. */
    val log = MutableStateFlow<List<String>>(emptyList())

    private fun append(line: String) {
        val stamped = "%tT  %s".format(java.util.Date(), line)
        log.value = (log.value + stamped).takeLast(MAX_LOG_LINES)
        android.util.Log.i(LOG_TAG, line)
    }

    val clock: Clock = Clock.System

    private val database = EmergencyDatabase.get(context)

    val store: EmergencyStore = RoomEmergencyStore(database.emergencyDao())

    val api = SosApiClient(BuildConfig.BACKEND_BASE_URL)

    val connectivity = AndroidConnectivity(context)

    /**
     * Opaque, device-scoped rider identity. Not a name, phone number or email -
     * the packet schema forbids those, and the backend rejects unexpected
     * fields outright.
     */
    val riderId: String = riderIdFor(context)

    // ---- transports, in priority order ------------------------------------
    //
    // Direct network FIRST. When the rider can reach the backend, that is both
    // the fastest and the most certain path, and a relay adds nothing. Its
    // availability check is a cheap connectivity read, so when the rider is
    // offline the cascade falls straight through.
    //
    // BLE relay SECOND: it exists precisely for the case direct upload cannot
    // handle, which is the rider having no connectivity at all.
    //
    // Simulated relay LAST, so it can never pre-empt a real path. It stands in
    // for a second phone during development and nothing more.

    val directNetwork = DirectNetworkTransport(api, connectivity) { append(it) }

    val bleRelay = BleRelayTransport(
        context = context,
        // Stays false until S0-S5 pass on real hardware. See docs/s0-s5-runbook.md.
        enabled = false,
        log = ::append,
    )

    val simulatedRelay = SimulatedRelayTransport(api, clock) { append(it) }

    val transports: List<Transport> = listOf(directNetwork, bleRelay, simulatedRelay)

    val deliveryManager = DeliveryManager(
        store = store,
        transports = transports,
        clock = clock,
        log = ::append,
    )

    val locationProvider = AndroidLocationProvider(context)

    val emergencyController = EmergencyController(
        deliveryManager = deliveryManager,
        locationProvider = locationProvider,
        riderId = riderId,
        clock = clock,
    )

    /** DEVELOPMENT ONLY. Backs the CREATE TEST SOS action. */
    val testCrashDetector = TestCrashDetector()

    /**
     * The relay (Phone B) role. Off by default - a phone only carries other
     * people's emergencies when its owner opts in.
     */
    val relayCoordinator = RelayCoordinator(
        context = context,
        store = store,
        deliveryManager = deliveryManager,
        scope = scope,
        relayId = "rl_relay_" + riderId.removePrefix("rl_").take(6),
        clock = clock,
        log = ::append,
    )

    fun bleCapability(): BleCapability = BleCapability.probe(appContext)

    fun logLine(line: String) = append(line)

    private fun riderIdFor(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_RIDER_ID, null) ?: run {
            val generated = "rl_" + UUID.randomUUID().toString().replace("-", "").take(8)
            prefs.edit().putString(KEY_RIDER_ID, generated).apply()
            generated
        }
    }

    private companion object {
        const val PREFS = "roadlink"
        const val KEY_RIDER_ID = "rider_id"
        const val LOG_TAG = "RLINK"
        const val MAX_LOG_LINES = 300
    }
}
