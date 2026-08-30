package com.roadlink.domain

import com.roadlink.testEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the rider and the responder are shown about one emergency.
 *
 * These assertions are about honesty, not about layout. A timeline that
 * described a relay handoff as a delivery, or a simulated hop as a real one,
 * would be a more convincing demo and a false one - and the whole value of
 * this project rests on the difference.
 */
class CustodyTimelineTest {

    private fun attempt(
        at: Long,
        transport: TransportKind,
        outcome: AttemptOutcome,
        detail: String? = null,
    ) = DeliveryAttempt(
        eventId = "evt-1", at = at, transport = transport, outcome = outcome, detail = detail,
    )

    @Test
    fun `the first step is always persistence, because that is what happens first`() {
        val steps = CustodyTimeline.of(testEvent(), emptyList())

        assertEquals(CustodyStep.Kind.PERSISTED, steps.first().kind)
        assertEquals(
            "persistence is timestamped at creation, not later",
            testEvent().createdAt, steps.first().at,
        )
    }

    @Test
    fun `a relay handoff is never described as a delivery`() {
        val event = testEvent().copy(
            state = DeliveryState.RELAYED,
            relayedTo = "rl_relay_x",
            lastAttemptAt = 2_000L,
        )
        val steps = CustodyTimeline.of(
            event,
            listOf(attempt(2_000L, TransportKind.BLE_RELAY, AttemptOutcome.HANDED_OFF)),
        )

        val relayed = steps.single { it.kind == CustodyStep.Kind.RELAYED }
        assertTrue(
            "the relay step must say custody, not arrival",
            relayed.detail!!.contains("backend does not have it"),
        )
        assertFalse(
            "no step may claim delivery for a relayed emergency",
            steps.any { it.kind == CustodyStep.Kind.DELIVERED },
        )
        assertTrue(
            "a relayed emergency is still outstanding",
            steps.any { it.kind == CustodyStep.Kind.WAITING },
        )
    }

    @Test
    fun `a failed attempt is shown together with the fact that the emergency was kept`() {
        val event = testEvent().copy(
            state = DeliveryState.QUEUED_OFFLINE,
            attemptCount = 1,
            lastAttemptAt = 2_000L,
            lastError = "DIRECT NETWORK: upload failed",
        )
        val steps = CustodyTimeline.of(
            event,
            listOf(attempt(2_000L, TransportKind.DIRECT_NETWORK, AttemptOutcome.FAILED, "timeout")),
        )

        val failed = steps.single { it.kind == CustodyStep.Kind.FAILED }
        assertTrue(failed.detail!!.contains("retained"))
        assertTrue(
            "a failed attempt must not end the timeline",
            steps.last().kind == CustodyStep.Kind.WAITING,
        )
    }

    @Test
    fun `an interrupted attempt is shown as unknown, not as a failure`() {
        val steps = CustodyTimeline.of(
            testEvent().copy(state = DeliveryState.QUEUED_OFFLINE, lastAttemptAt = 2_000L),
            listOf(attempt(2_000L, TransportKind.DIRECT_NETWORK, AttemptOutcome.INTERRUPTED)),
        )

        val step = steps.single { it.kind == CustodyStep.Kind.INTERRUPTED }
        assertTrue(step.detail!!.contains("outcome unknown"))
        assertFalse(steps.any { it.kind == CustodyStep.Kind.FAILED })
    }

    @Test
    fun `a simulated hop is marked simulated and a real one is not`() {
        val simulated = CustodyTimeline.of(
            testEvent().copy(state = DeliveryState.DELIVERED, deliveredAt = 2_000L),
            listOf(attempt(2_000L, TransportKind.SIMULATED_RELAY, AttemptOutcome.SUCCESS)),
        ).single { it.kind == CustodyStep.Kind.DELIVERED }

        val real = CustodyTimeline.of(
            testEvent().copy(state = DeliveryState.DELIVERED, deliveredAt = 2_000L),
            listOf(attempt(2_000L, TransportKind.DIRECT_NETWORK, AttemptOutcome.SUCCESS)),
        ).single { it.kind == CustodyStep.Kind.DELIVERED }

        assertTrue("a simulated delivery must be flagged", simulated.isSimulated)
        assertFalse("a real delivery must not be flagged simulated", real.isSimulated)
        assertEquals(TransportKind.SIMULATED_RELAY, simulated.transport)
    }

    @Test
    fun `a delivered emergency ends at its delivery`() {
        val steps = CustodyTimeline.of(
            testEvent().copy(state = DeliveryState.DELIVERED, deliveredAt = 2_000L),
            listOf(attempt(2_000L, TransportKind.DIRECT_NETWORK, AttemptOutcome.SUCCESS)),
        )

        assertEquals(CustodyStep.Kind.DELIVERED, steps.last().kind)
        assertFalse(
            "nothing may suggest work still in progress after delivery",
            steps.any { it.kind == CustodyStep.Kind.WAITING },
        )
    }

    @Test
    fun `steps stay in the order the events happened`() {
        val steps = CustodyTimeline.of(
            testEvent().copy(state = DeliveryState.DELIVERED, deliveredAt = 5_000L),
            // Deliberately out of order, as a careless caller might pass them.
            listOf(
                attempt(5_000L, TransportKind.DIRECT_NETWORK, AttemptOutcome.SUCCESS),
                attempt(2_000L, TransportKind.DIRECT_NETWORK, AttemptOutcome.FAILED),
            ),
        )

        assertEquals(
            listOf(
                CustodyStep.Kind.PERSISTED,
                CustodyStep.Kind.FAILED,
                CustodyStep.Kind.DELIVERED,
            ),
            steps.map { it.kind },
        )
    }
}
