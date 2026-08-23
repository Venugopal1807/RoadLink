package com.roadlink.delivery

import com.roadlink.FakeSosApi
import com.roadlink.RecordingStore
import com.roadlink.domain.Clock
import com.roadlink.domain.DeliveryState
import com.roadlink.domain.EventOrigin
import com.roadlink.domain.MutableClock
import com.roadlink.domain.TransportKind
import com.roadlink.testEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five delivery scenarios, end to end through the real DeliveryManager,
 * the real SimulatedRelayTransport and the real DirectNetworkTransport.
 *
 * Only two things are doubled: the backend (FakeSosApi, whose idempotency
 * behaviour is separately verified against the real server by
 * backend/tests/test_idempotency.py) and the store. Everything else is the
 * production code path.
 *
 * These are deterministic: latencies are zeroed and the clock is manual, so
 * the same run produces the same result every time.
 */
class DeliveryScenarioTest {

    private lateinit var journal: MutableList<String>
    private lateinit var store: RecordingStore
    private lateinit var api: FakeSosApi
    private lateinit var clock: MutableClock
    private lateinit var relay: SimulatedRelayTransport
    private lateinit var network: DirectNetworkTransport
    private var online = true

    private fun setUp(): DeliveryManager {
        journal = mutableListOf()
        store = RecordingStore(journal)
        api = FakeSosApi(journal)
        clock = Clock.fixed(1_000L)
        online = true

        relay = SimulatedRelayTransport(api, clock).apply {
            // Deterministic: no wall-clock waiting inside the tests.
            discoveryLatencyMs = 0
            transferLatencyMs = 0
        }
        network = DirectNetworkTransport(api, Connectivity { online })

        // Relay first here so the scenarios below can exercise a relay hop
        // deliberately. Production order is direct-network first (see
        // AppContainer); the cascade logic under test is the same either way.
        return DeliveryManager(store, listOf(relay, network), clock)
    }

    // ------------------------------------------------------------ scenario A

    @Test
    fun `scenario A - simulated relay receives and forwards, event delivered`() = runTest {
        val manager = setUp()
        relay.relayInRange = true
        relay.relayHasNetwork = true

        val queued = manager.submit(testEvent())
        val delivered = manager.deliver(queued)

        assertEquals(DeliveryState.DELIVERED, delivered.state)
        assertEquals(TransportKind.SIMULATED_RELAY, delivered.deliveredVia)
        assertEquals(1, api.eventCount)
        assertEquals(listOf("simulated_relay"), api.pathsFor("evt-1"))

        // The marker survives all the way to the wire.
        assertTrue("a simulated delivery must be labelled as such", delivered.deliveredVia!!.isSimulated)
        assertFalse("and must never count as real evidence", delivered.isFullyReal)
    }

    // ------------------------------------------------------------ scenario B

    @Test
    fun `scenario B - no relay, event queues, then direct upload delivers it`() = runTest {
        val manager = setUp()
        relay.relayInRange = false
        online = false

        // Emergency happens with nothing available at all.
        val queued = manager.submit(testEvent())
        val afterFirst = manager.deliver(queued)

        assertEquals("nothing available, so it waits", DeliveryState.QUEUED_OFFLINE, afterFirst.state)
        assertEquals("nothing reached the backend", 0, api.eventCount)
        assertNotNull("but the event is safely stored", store.get("evt-1"))

        // Network comes back. Relay is still absent.
        online = true
        clock.advanceBy(60_000)
        val afterSecond = manager.deliver(store.get("evt-1")!!)

        assertEquals(DeliveryState.DELIVERED, afterSecond.state)
        assertEquals(TransportKind.DIRECT_NETWORK, afterSecond.deliveredVia)
        assertEquals(listOf("direct"), api.pathsFor("evt-1"))
    }

    // ------------------------------------------------------------ scenario C

    @Test
    fun `scenario C - relay fails twice then succeeds, event survives every failure`() = runTest {
        val manager = setUp()
        relay.relayInRange = true
        relay.failuresBeforeSuccess = 2
        online = false // force the relay to be the only path, so retries are visible

        val queued = manager.submit(testEvent())

        val first = manager.deliver(queued)
        assertEquals(DeliveryState.QUEUED_OFFLINE, first.state)
        assertNotNull("failure must never remove the event", store.get("evt-1"))

        clock.advanceBy(60_000)
        val second = manager.deliver(store.get("evt-1")!!)
        assertEquals(DeliveryState.QUEUED_OFFLINE, second.state)
        assertNotNull(store.get("evt-1"))

        clock.advanceBy(60_000)
        val third = manager.deliver(store.get("evt-1")!!)
        assertEquals("the third attempt succeeds", DeliveryState.DELIVERED, third.state)
        assertEquals(TransportKind.SIMULATED_RELAY, third.deliveredVia)

        assertEquals("three relay attempts were made", 3, third.attemptCount)
        val attempts = store.attempts("evt-1").filter { it.transport == TransportKind.SIMULATED_RELAY }
        assertEquals("every attempt is recorded, failures included", 3, attempts.size)
    }

    // ------------------------------------------------------------ scenario D

    @Test
    fun `scenario D - rider and relay both submit, backend keeps one event and two records`() = runTest {
        val manager = setUp()
        relay.relayInRange = true

        val queued = manager.submit(testEvent())
        val delivered = manager.deliver(queued)
        assertEquals(DeliveryState.DELIVERED, delivered.state)

        // The rider's own phone regains connectivity and submits independently.
        // This redundancy is the design, not a bug.
        online = true
        val result = manager.redeliver(delivered, network)

        assertTrue(result is TransportResult.Delivered)
        assertTrue("the backend recognises the repeat", (result as TransportResult.Delivered).duplicate)

        assertEquals("exactly one emergency exists", 1, api.eventCount)
        assertEquals("but both delivery records are kept", 2, api.auditCount)
        assertEquals(listOf("simulated_relay", "direct"), api.pathsFor("evt-1"))
    }

    // ------------------------------------------------------------ scenario E

    @Test
    fun `scenario E - fully offline, event stays queued and is never lost`() = runTest {
        val manager = setUp()
        relay.relayInRange = false
        online = false

        val queued = manager.submit(testEvent())
        // Several passes, as would happen over minutes of no connectivity.
        var current = queued
        repeat(5) {
            clock.advanceBy(60_000)
            current = manager.deliver(store.get("evt-1")!!)
        }

        assertEquals(DeliveryState.QUEUED_OFFLINE, current.state)
        assertEquals("nothing was delivered", 0, api.eventCount)
        assertEquals("the event is still on disk", 1, store.snapshotOnDisk().size)
        assertTrue("and is still deliverable", current.state.isPending)
    }

    // ------------------------------------------------- relay holds but cannot forward

    @Test
    fun `relay accepts the event but has no connectivity, rider keeps its own copy queued`() = runTest {
        val manager = setUp()
        relay.relayInRange = true
        relay.relayHasNetwork = false
        online = false

        val queued = manager.submit(testEvent())
        val after = manager.deliver(queued)

        assertEquals(DeliveryState.QUEUED_OFFLINE, after.state)
        assertEquals("the relay is holding it", 1, relay.heldCount)
        assertEquals("nothing reached the backend yet", 0, api.eventCount)
        assertNotNull("and the rider still has its own copy", store.get("evt-1"))
    }

    // ------------------------------------------------------------- fallback

    @Test
    fun `an unavailable relay falls through to direct upload in a single pass`() = runTest {
        val manager = setUp()
        relay.relayInRange = false
        online = true

        val queued = manager.submit(testEvent())
        val delivered = manager.deliver(queued)

        assertEquals(DeliveryState.DELIVERED, delivered.state)
        assertEquals(
            "the cascade must reach the network transport without a second pass",
            TransportKind.DIRECT_NETWORK,
            delivered.deliveredVia,
        )
    }

    @Test
    fun `backoff grows exponentially and is capped`() {
        val manager = setUp()
        assertEquals(0L, manager.backoffMillis(0))
        assertEquals(1_000L, manager.backoffMillis(1))
        assertEquals(2_000L, manager.backoffMillis(2))
        assertEquals(4_000L, manager.backoffMillis(3))
        assertEquals(8_000L, manager.backoffMillis(4))
        assertEquals(16_000L, manager.backoffMillis(5))
        assertEquals("capped at 30s", 30_000L, manager.backoffMillis(6))
        assertEquals("and stays capped, with no overflow", 30_000L, manager.backoffMillis(99))
    }

    @Test
    fun `a delivery pass respects backoff and does not hammer a failing transport`() = runTest {
        val manager = setUp()
        relay.relayInRange = true
        relay.failuresBeforeSuccess = 99
        online = false

        val queued = manager.submit(testEvent())
        manager.deliver(queued)
        val attemptsAfterFirst = store.get("evt-1")!!.attemptCount

        // Immediately re-running a pass must not attempt again: backoff is 1s.
        manager.runDeliveryPass()
        assertEquals(
            "backoff must suppress an immediate retry",
            attemptsAfterFirst,
            store.get("evt-1")!!.attemptCount,
        )

        clock.advanceBy(5_000)
        manager.runDeliveryPass()
        assertTrue(
            "once backoff elapses the retry proceeds",
            store.get("evt-1")!!.attemptCount > attemptsAfterFirst,
        )
    }

    /**
     * The offline stretch is where the loop spends most of its time, and a
     * transport that reports itself unavailable is never tried - so it does not
     * advance the attempt count and does not consume backoff. The reason must
     * therefore be recorded once rather than on every pass, or the audit trail
     * grows without bound during exactly the scenario RoadLink exists for.
     */
    @Test
    fun `a transport that stays unavailable is recorded once, not on every pass`() = runTest {
        val manager = setUp()
        relay.relayInRange = false
        online = false

        manager.submit(testEvent())
        manager.runDeliveryPass()
        val auditAfterFirstPass = store.attempts("evt-1").size
        assertTrue("the first pass records why nothing was tried", auditAfterFirstPass > 0)

        // Ten more passes, as the loop would run while the rider stays offline.
        repeat(10) { manager.runDeliveryPass() }

        assertEquals(
            "an unchanged unavailable reason must not be appended again",
            auditAfterFirstPass,
            store.attempts("evt-1").size,
        )
        assertEquals("and nothing may be delivered", 0, api.eventCount)
        assertNotNull("and the event is still held", store.get("evt-1"))
    }

    /**
     * Suppressing the repeat must not suppress the recovery: the moment a
     * transport becomes available again the event is delivered, and a later
     * outage is recorded afresh rather than swallowed by the memo.
     */
    @Test
    fun `suppressing repeated unavailability still delivers the moment a transport returns`() = runTest {
        val manager = setUp()
        relay.relayInRange = false
        online = false

        manager.submit(testEvent())
        repeat(5) { manager.runDeliveryPass() }
        assertEquals("still nothing delivered", 0, api.eventCount)

        online = true
        clock.advanceBy(60_000)
        manager.runDeliveryPass()

        val delivered = store.get("evt-1")!!
        assertEquals(DeliveryState.DELIVERED, delivered.state)
        assertEquals(TransportKind.DIRECT_NETWORK, delivered.deliveredVia)
    }

    @Test
    fun `a real sensor event delivered over a simulated relay is not fully real`() = runTest {
        val manager = setUp()
        relay.relayInRange = true

        val queued = manager.submit(testEvent(origin = EventOrigin.REAL_SENSOR))
        val delivered = manager.deliver(queued)

        assertEquals(DeliveryState.DELIVERED, delivered.state)
        assertFalse("a real trigger delivered by a simulated relay is still not evidence", delivered.isFullyReal)
        assertFalse("the packet itself is correctly marked not-simulated", delivered.simulated)
    }
}
