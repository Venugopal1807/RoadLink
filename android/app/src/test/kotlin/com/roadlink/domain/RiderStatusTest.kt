package com.roadlink.domain

import com.roadlink.testEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The six states the rider screen must never confuse.
 *
 * The one that carries the product claim is [RiderStatus.RETAINED_AFTER_FAILURE]:
 * delivery failed, the emergency did not. A screen that showed only "failed"
 * would be describing the attempt correctly and the situation wrongly.
 */
class RiderStatusTest {

    @Test
    fun `a queued emergency that has never been tried reads as queued`() {
        val event = testEvent().copy(state = DeliveryState.QUEUED_OFFLINE)
        assertEquals(RiderStatus.QUEUED, RiderStatus.of(event))
    }

    @Test
    fun `a queued emergency whose attempt failed reads as retained, not as failed`() {
        val event = testEvent().copy(
            state = DeliveryState.QUEUED_OFFLINE,
            attemptCount = 2,
            lastError = "DIRECT NETWORK: upload failed",
        )
        val status = RiderStatus.of(event)

        assertEquals(RiderStatus.RETAINED_AFTER_FAILURE, status)
        assertEquals("FAILED BUT RETAINED", status.label)
    }

    @Test
    fun `relayed is never reported as delivered`() {
        val event = testEvent().copy(state = DeliveryState.RELAYED, relayedTo = "rl_relay_x")
        val status = RiderStatus.of(event)

        assertEquals(RiderStatus.RELAYED, status)
        assertNotEquals(RiderStatus.DELIVERED, status)
        assertEquals(
            "only a backend acknowledgement is terminal",
            false, status.isTerminal,
        )
    }

    @Test
    fun `delivered is the only terminal status`() {
        val terminal = RiderStatus.entries.filter { it.isTerminal }
        assertEquals(listOf(RiderStatus.DELIVERED), terminal)
    }

    @Test
    fun `every status says something specific`() {
        RiderStatus.entries.forEach { status ->
            assertEquals(
                "${status.name} must have a distinct label",
                1, RiderStatus.entries.count { it.label == status.label },
            )
        }
    }

    @Test
    fun `a device holding nothing is ready`() {
        assertEquals(RiderStatus.READY, RiderStatus.ofDevice(emptyList()))
        assertEquals(
            "a delivered emergency leaves the device ready again",
            RiderStatus.READY,
            RiderStatus.ofDevice(listOf(testEvent().copy(state = DeliveryState.DELIVERED))),
        )
    }

    @Test
    fun `a device with one delivered and one queued emergency is not ready`() {
        val events = listOf(
            testEvent(eventId = "a").copy(state = DeliveryState.DELIVERED),
            testEvent(eventId = "b").copy(state = DeliveryState.QUEUED_OFFLINE),
        )
        assertEquals(RiderStatus.QUEUED, RiderStatus.ofDevice(events))
    }

    @Test
    fun `the most urgent outstanding emergency represents the device`() {
        val events = listOf(
            testEvent(eventId = "a").copy(state = DeliveryState.RELAYED),
            testEvent(eventId = "b").copy(
                state = DeliveryState.QUEUED_OFFLINE,
                lastError = "DIRECT NETWORK: upload failed",
            ),
        )
        assertEquals(RiderStatus.RETAINED_AFTER_FAILURE, RiderStatus.ofDevice(events))
    }
}
