package com.roadlink.delivery

import com.roadlink.FakeSosApi
import com.roadlink.RecordingStore
import com.roadlink.ScriptedTransport
import com.roadlink.domain.AttemptOutcome
import com.roadlink.domain.Clock
import com.roadlink.domain.DeliveryState
import com.roadlink.domain.TransportKind
import com.roadlink.testEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a BLE acknowledgement does and does not mean.
 *
 * The failure this guards against is a tempting one: treating a relay's ACK as
 * delivery. It is not. The relay may never regain connectivity, so an ACK is
 * custody, not arrival. Getting this wrong would make the product claim false
 * in exactly the situation the product exists for.
 */
class RelayHandoffTest {

    private val journal = mutableListOf<String>()

    private fun manager(vararg transports: ScriptedTransport): Pair<DeliveryManager, RecordingStore> {
        val store = RecordingStore(journal)
        return DeliveryManager(store, transports.toList(), Clock.fixed(1_000)) to store
    }

    @Test
    fun `a relay handoff is not reported as delivered`() = runTest {
        val ble = ScriptedTransport.handsOff(TransportKind.BLE_RELAY, journal, "rl_relay_b")
        val (mgr, store) = manager(ble)

        val queued = mgr.submit(testEvent())
        val after = mgr.deliver(queued)

        assertEquals("custody is not arrival", DeliveryState.RELAYED, after.state)
        assertTrue("the emergency is still pending", after.state.isPending)
        assertFalse("and it is certainly not terminal", after.state.isTerminal)
        assertEquals("rl_relay_b", after.relayedTo)
        assertNull("nothing has been delivered", after.deliveredVia)
        assertEquals("RELAYED", after.statusLabel)
        assertNotNull(store.get("evt-1"))
    }

    @Test
    fun `a relayed emergency is still picked up by later delivery passes`() = runTest {
        val ble = ScriptedTransport.handsOff(TransportKind.BLE_RELAY, journal)
        val (mgr, store) = manager(ble)

        val queued = mgr.submit(testEvent())
        mgr.deliver(queued)

        // The rider must keep trying: the relay might never get online.
        assertEquals(
            "a relayed emergency must remain in the pending set",
            1,
            store.pending().size,
        )
    }

    @Test
    fun `the rider still delivers directly after a handoff, and the backend dedupes`() = runTest {
        val ble = ScriptedTransport.handsOff(TransportKind.BLE_RELAY, journal)
        val net = ScriptedTransport.alwaysDelivers(TransportKind.DIRECT_NETWORK, journal)
        val (mgr, store) = manager(ble, net)

        val queued = mgr.submit(testEvent())
        val relayed = mgr.deliver(queued)
        assertEquals(DeliveryState.RELAYED, relayed.state)

        // Rider's own network comes back on a later pass.
        ble.available = false
        val delivered = mgr.deliver(store.get("evt-1")!!)

        assertEquals(DeliveryState.DELIVERED, delivered.state)
        assertEquals(TransportKind.DIRECT_NETWORK, delivered.deliveredVia)
        // Two independent holders both submitting is the intended design; the
        // backend's idempotency is what makes it safe.
        assertEquals("rl_relay_x", delivered.relayedTo)
    }

    @Test
    fun `a handoff stops the cascade rather than also trying the network`() = runTest {
        val ble = ScriptedTransport.handsOff(TransportKind.BLE_RELAY, journal)
        val net = ScriptedTransport.alwaysDelivers(TransportKind.DIRECT_NETWORK, journal)
        val (mgr, _) = manager(ble, net)

        val queued = mgr.submit(testEvent())
        mgr.deliver(queued)

        assertEquals("BLE was tried", 1, ble.deliverCalls)
        assertEquals("the pass stopped at real progress", 0, net.deliverCalls)
    }

    @Test
    fun `a handoff is recorded distinctly from a delivery in the audit trail`() = runTest {
        val ble = ScriptedTransport.handsOff(TransportKind.BLE_RELAY, journal, "rl_relay_q")
        val (mgr, store) = manager(ble)

        val queued = mgr.submit(testEvent())
        mgr.deliver(queued)

        val attempts = store.attempts("evt-1")
        assertEquals(1, attempts.size)
        assertEquals(
            "a handoff must not be logged as SUCCESS",
            AttemptOutcome.HANDED_OFF,
            attempts.first().outcome,
        )
        assertEquals(TransportKind.BLE_RELAY, attempts.first().transport)
    }

    @Test
    fun `BLE handoff over a real radio still counts as real evidence`() = runTest {
        val ble = ScriptedTransport.handsOff(TransportKind.BLE_RELAY, journal)
        val (mgr, _) = manager(ble)

        val relayed = mgr.deliver(mgr.submit(testEvent()))
        // The trigger was simulated, so the event is not fully real - but the
        // transport itself is, and the two axes stay independent.
        assertFalse(TransportKind.BLE_RELAY.isSimulated)
        assertTrue(relayed.simulated)
    }

    @Test
    fun `a forced transport narrows the cascade without changing the state machine`() = runTest {
        val ble = ScriptedTransport.alwaysFails(TransportKind.BLE_RELAY, journal, "no relay")
        val net = ScriptedTransport.alwaysDelivers(TransportKind.DIRECT_NETWORK, journal)
        val (mgr, store) = manager(ble, net)

        mgr.forcedTransport = TransportKind.BLE_RELAY
        val after = mgr.deliver(mgr.submit(testEvent()))

        assertEquals("pinned to BLE, so the network was never tried", 0, net.deliverCalls)
        assertEquals(1, ble.deliverCalls)
        assertEquals("and the event is still safe and retryable", DeliveryState.QUEUED_OFFLINE, after.state)
        assertNotNull(store.get("evt-1"))

        // Releasing the pin restores normal behaviour.
        mgr.forcedTransport = null
        val delivered = mgr.deliver(store.get("evt-1")!!)
        assertEquals(DeliveryState.DELIVERED, delivered.state)
        assertEquals(TransportKind.DIRECT_NETWORK, delivered.deliveredVia)
    }

    /**
     * A disarmed BLE transport must not be able to manufacture success.
     *
     * `BleRelayTransport.enabled` is false until the hardware ladder passes, so
     * the transport reports itself unavailable and is skipped by the pre-check
     * rather than being called. Nothing downstream may then present a BLE
     * result: `deliveredVia` stays null, no audit row claims a BLE success, and
     * the status the UI renders never becomes a BLE one.
     */
    @Test
    fun `a disarmed BLE transport cannot produce a BLE success`() = runTest {
        val ble = ScriptedTransport.unavailable(TransportKind.BLE_RELAY, journal)
        val (mgr, store) = manager(ble)

        mgr.forcedTransport = TransportKind.BLE_RELAY
        val after = mgr.deliver(mgr.submit(testEvent()))

        assertEquals("a disarmed transport is never even called", 0, ble.deliverCalls)
        assertEquals(DeliveryState.QUEUED_OFFLINE, after.state)
        assertNull("nothing may claim to have delivered it", after.deliveredVia)
        assertNull("and no relay may be credited with custody", after.relayedTo)
        assertEquals("the UI shows it as waiting, not as relaying", "QUEUED", after.statusLabel)
        assertNotNull("and the emergency is still on disk", store.get("evt-1"))

        val bleAttempts = store.attempts("evt-1").filter { it.transport == TransportKind.BLE_RELAY }
        assertTrue("the skip is recorded", bleAttempts.isNotEmpty())
        assertTrue(
            "but never as a success or a handoff",
            bleAttempts.all { it.outcome == AttemptOutcome.UNAVAILABLE },
        )
    }

    /**
     * The disarmed BLE path must not block the transport that does work. This
     * is the fallback the demo relies on when BLE is unvalidated.
     */
    @Test
    fun `an unavailable BLE transport falls through to the network in the same pass`() = runTest {
        val ble = ScriptedTransport.unavailable(TransportKind.BLE_RELAY, journal)
        val net = ScriptedTransport.alwaysDelivers(TransportKind.DIRECT_NETWORK, journal)
        val (mgr, _) = manager(ble, net)

        val delivered = mgr.deliver(mgr.submit(testEvent()))

        assertEquals(DeliveryState.DELIVERED, delivered.state)
        assertEquals(TransportKind.DIRECT_NETWORK, delivered.deliveredVia)
        assertEquals("BLE was skipped, not attempted", 0, ble.deliverCalls)
    }

    @Test
    fun `relayed state has an onward path and is not a dead end`() {
        assertTrue(
            DeliveryState.canMove(DeliveryState.RELAYED, DeliveryState.DELIVERY_ATTEMPT)
        )
        assertTrue(
            "handoff must be reachable from an active attempt",
            DeliveryState.canMove(DeliveryState.DELIVERY_ATTEMPT, DeliveryState.RELAYED)
        )
    }
}
