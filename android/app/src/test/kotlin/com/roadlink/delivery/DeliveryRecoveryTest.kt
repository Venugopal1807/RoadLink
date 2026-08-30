package com.roadlink.delivery

import com.roadlink.RecordingStore
import com.roadlink.ScriptedTransport
import com.roadlink.domain.AttemptOutcome
import com.roadlink.domain.Clock
import com.roadlink.domain.DeliveryState
import com.roadlink.domain.TransportKind
import com.roadlink.testEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recovery from process death that happened PART WAY THROUGH a delivery.
 *
 * The durability tests prove a persisted emergency survives. These prove
 * something narrower and easier to get wrong: that an emergency which was
 * interrupted mid-flight is still deliverable afterwards.
 *
 * This is not a hypothetical. The event is written to disk in
 * DELIVERY_ATTEMPT before the transport is called, and a transport call is
 * where the process spends its time - a network connect timeout is five
 * seconds, a BLE advertising window is twenty. Being killed inside that
 * window is the likely case, not the unlucky one.
 */
class DeliveryRecoveryTest {

    private val journal = mutableListOf<String>()

    @Test
    fun `an emergency interrupted mid-attempt is still delivered after a restart`() = runTest {
        val store = RecordingStore(journal)
        // Exactly what is on disk when the process is killed during a
        // transport call: the attempt was recorded, the outcome never was.
        store.persist(
            testEvent().copy(
                state = DeliveryState.DELIVERY_ATTEMPT,
                activeTransport = TransportKind.DIRECT_NETWORK,
                attemptCount = 1,
                lastAttemptAt = null,
            )
        )

        val network = ScriptedTransport.alwaysDelivers(TransportKind.DIRECT_NETWORK, journal)
        val manager = DeliveryManager(store, listOf(network), Clock.fixed(10_000L))

        manager.runDeliveryPass()

        assertEquals(
            "an interrupted emergency must still reach the backend",
            DeliveryState.DELIVERED,
            store.get("evt-1")!!.state,
        )
    }

    @Test
    fun `an emergency interrupted before it was queued is still delivered`() = runTest {
        val store = RecordingStore(journal)
        // Killed between the durable write and the move to QUEUED_OFFLINE.
        store.persist(testEvent().copy(state = DeliveryState.CONFIRMED_EMERGENCY))

        val network = ScriptedTransport.alwaysDelivers(TransportKind.DIRECT_NETWORK, journal)
        val manager = DeliveryManager(store, listOf(network), Clock.fixed(10_000L))

        manager.runDeliveryPass()

        assertEquals(
            DeliveryState.DELIVERED,
            store.get("evt-1")!!.state,
        )
    }

    @Test
    fun `an interrupted attempt is recorded as interrupted, not as a failure`() = runTest {
        val store = RecordingStore(journal)
        store.persist(
            testEvent().copy(
                state = DeliveryState.DELIVERY_ATTEMPT,
                activeTransport = TransportKind.DIRECT_NETWORK,
                attemptCount = 3,
            )
        )

        val network = ScriptedTransport.alwaysDelivers(TransportKind.DIRECT_NETWORK, journal)
        DeliveryManager(store, listOf(network), Clock.fixed(10_000L)).runDeliveryPass()

        val trail = store.attempts("evt-1")
        val interrupted = trail.filter { it.outcome == AttemptOutcome.INTERRUPTED }
        assertEquals(
            "the interrupted attempt must appear in the trail exactly once",
            1, interrupted.size,
        )
        assertEquals(
            "an unknown outcome must not be reported as a transport failure",
            0, trail.count { it.outcome == AttemptOutcome.FAILED },
        )
        assertEquals(TransportKind.DIRECT_NETWORK, interrupted.single().transport)
    }

    @Test
    fun `recovery does not invent an attempt that never happened`() = runTest {
        val store = RecordingStore(journal)
        store.persist(
            testEvent().copy(
                state = DeliveryState.DELIVERY_ATTEMPT,
                activeTransport = TransportKind.DIRECT_NETWORK,
                attemptCount = 3,
            )
        )

        val network = ScriptedTransport.alwaysDelivers(TransportKind.DIRECT_NETWORK, journal)
        DeliveryManager(store, listOf(network), Clock.fixed(10_000L)).runDeliveryPass()

        // 3 before, plus the one real retry that followed recovery. Recovery
        // itself must not count: nothing new was tried.
        assertEquals(4, store.get("evt-1")!!.attemptCount)
    }

    @Test
    fun `one unrecoverable emergency does not block delivery of the others`() = runTest {
        val store = RecordingStore(journal)
        // Oldest first, so this one is processed before the healthy event.
        store.persist(
            testEvent(eventId = "evt-stuck", createdAt = 1_000L)
                .copy(state = DeliveryState.DELIVERY_ATTEMPT)
        )
        store.persist(testEvent(eventId = "evt-healthy", createdAt = 2_000L))

        val network = ScriptedTransport.alwaysDelivers(TransportKind.DIRECT_NETWORK, journal)
        val manager = DeliveryManager(store, listOf(network), Clock.fixed(10_000L))

        manager.runDeliveryPass()

        assertTrue(
            "a problem with one emergency must never strand another",
            store.get("evt-healthy")!!.state == DeliveryState.DELIVERED,
        )
    }
}
