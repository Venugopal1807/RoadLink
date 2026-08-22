package com.roadlink.delivery

import com.roadlink.RecordingStore
import com.roadlink.ScriptedTransport
import com.roadlink.domain.Clock
import com.roadlink.domain.DeliveryState
import com.roadlink.domain.TransportKind
import com.roadlink.testEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The core invariant.
 *
 * "CONFIRMED_EMERGENCY -> PERSIST LOCALLY -> ONLY THEN ATTEMPT DELIVERY" is
 * the one property the product cannot violate, so it is tested by asserting on
 * the ORDER of operations rather than on their presence. A test that only
 * checked "the event was saved and delivered" would pass on an implementation
 * that transmitted first and saved afterwards.
 */
class DeliveryInvariantTest {

    @Test
    fun `event is persisted before any transport is contacted`() = runTest {
        val journal = mutableListOf<String>()
        val store = RecordingStore(journal)
        val transport = ScriptedTransport.alwaysDelivers(TransportKind.SIMULATED_RELAY, journal)
        val manager = DeliveryManager(store, listOf(transport), Clock.fixed(1_000))

        val queued = manager.submit(testEvent())
        manager.deliver(queued)

        val persistIndex = journal.indexOfFirst { it.startsWith("PERSIST ") }
        val deliverIndex = journal.indexOfFirst { it.startsWith("DELIVER ") }

        assertTrue("the event must be persisted at all", persistIndex >= 0)
        assertTrue("a transport must have been contacted", deliverIndex >= 0)
        assertTrue(
            "PERSIST must happen before DELIVER, journal was $journal",
            persistIndex < deliverIndex,
        )
    }

    @Test
    fun `nothing is transmitted when persistence fails`() = runTest {
        val journal = mutableListOf<String>()
        val store = RecordingStore(journal, failPersistWith = IllegalStateException("disk full"))
        val transport = ScriptedTransport.alwaysDelivers(TransportKind.SIMULATED_RELAY, journal)
        val manager = DeliveryManager(store, listOf(transport), Clock.fixed(1_000))

        val error = runCatching { manager.submit(testEvent()) }.exceptionOrNull()

        assertNotNull("a failed write must surface, not be swallowed", error)
        assertEquals(
            "no transport may be contacted for an event that was never stored",
            0,
            transport.deliverCalls,
        )
    }

    @Test
    fun `a failed delivery never removes the event`() = runTest {
        val journal = mutableListOf<String>()
        val store = RecordingStore(journal)
        val transport = ScriptedTransport.alwaysFails(TransportKind.SIMULATED_RELAY, journal, "relay vanished")
        val manager = DeliveryManager(store, listOf(transport), Clock.fixed(1_000))

        val queued = manager.submit(testEvent())
        val after = manager.deliver(queued)

        assertEquals(DeliveryState.QUEUED_OFFLINE, after.state)
        assertNotNull("the event must still be on disk", store.get("evt-1"))
        assertEquals(1, store.snapshotOnDisk().size)
        assertTrue("the failure reason is kept for the responder", after.lastError!!.contains("relay vanished"))
    }

    @Test
    fun `an event with every transport unavailable stays queued and intact`() = runTest {
        val journal = mutableListOf<String>()
        val store = RecordingStore(journal)
        val ble = ScriptedTransport.unavailable(TransportKind.BLE_RELAY, journal)
        val net = ScriptedTransport.unavailable(TransportKind.DIRECT_NETWORK, journal)
        val manager = DeliveryManager(store, listOf(ble, net), Clock.fixed(1_000))

        val queued = manager.submit(testEvent())
        val after = manager.deliver(queued)

        assertEquals(DeliveryState.QUEUED_OFFLINE, after.state)
        assertEquals(0, ble.deliverCalls)
        assertEquals(0, net.deliverCalls)
        assertNotNull(store.get("evt-1"))
    }

    @Test
    fun `delivery state machine has no path to a terminal failure`() {
        // Enumerate every state and confirm the only terminal one is DELIVERED.
        val terminal = DeliveryState.entries.filter { state ->
            DeliveryState.entries.none { DeliveryState.canMove(state, it) }
        }
        assertEquals(
            "DELIVERED must be the only state with no way out",
            listOf(DeliveryState.DELIVERED),
            terminal,
        )
    }

    @Test
    fun `every non delivered state can reach delivery again`() {
        // No queued emergency may ever be stranded in a state it cannot leave.
        DeliveryState.entries.filter { !it.isTerminal }.forEach { state ->
            val hasExit = DeliveryState.entries.any { DeliveryState.canMove(state, it) }
            assertTrue("$state must have an onward transition", hasExit)
        }
    }

    @Test
    fun `a delivery attempt that fails returns to a retryable state`() {
        val event = testEvent().transitionTo(
            DeliveryState.QUEUED_OFFLINE, at = 1_000,
        ).transitionTo(
            DeliveryState.DELIVERY_ATTEMPT, transport = TransportKind.SIMULATED_RELAY, at = 1_100,
        )
        val failed = event.transitionTo(DeliveryState.QUEUED_OFFLINE, at = 1_200, error = "boom")

        assertTrue("the event must remain deliverable", failed.state.isPending)
        assertNull("no transport should still be marked active", failed.activeTransport)
        assertEquals("boom", failed.lastError)
    }

    @Test
    fun `delivered is terminal and rejects further transitions`() {
        val delivered = testEvent()
            .transitionTo(DeliveryState.QUEUED_OFFLINE, at = 1_000)
            .transitionTo(DeliveryState.DELIVERY_ATTEMPT, transport = TransportKind.DIRECT_NETWORK, at = 1_100)
            .transitionTo(DeliveryState.DELIVERED, at = 1_200)

        assertTrue(delivered.state.isTerminal)
        val error = runCatching {
            delivered.transitionTo(DeliveryState.QUEUED_OFFLINE, at = 1_300)
        }.exceptionOrNull()
        assertTrue(
            "a delivered emergency must not silently reopen",
            error is com.roadlink.domain.IllegalTransitionException,
        )
    }
}
