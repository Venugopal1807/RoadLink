package com.roadlink.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roadlink.domain.AttemptOutcome
import com.roadlink.domain.DeliveryAttempt
import com.roadlink.domain.DeliveryState
import com.roadlink.domain.EmergencyEvent
import com.roadlink.domain.EventOrigin
import com.roadlink.domain.TransportKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The durability half of the core invariant.
 *
 * A JVM test can prove that persistence is CALLED before transmission. Only a
 * real Android runtime can prove the write actually SURVIVES - that reopening
 * the database returns the emergency, with its delivery state and its full
 * attempt history intact.
 *
 * These tests use an on-disk database, not an in-memory one. An in-memory Room
 * database would pass every assertion below while proving nothing at all about
 * durability, which is the entire point.
 *
 * Run:  ./gradlew :app:connectedDebugAndroidTest   (requires a device)
 */
@RunWith(AndroidJUnit4::class)
class PersistenceDurabilityTest {

    private lateinit var context: Context
    private lateinit var db: EmergencyDatabase
    private lateinit var store: RoomEmergencyStore

    private val dbName = "roadlink-durability-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        db = open()
        store = RoomEmergencyStore(db.emergencyDao())
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun open(): EmergencyDatabase =
        Room.databaseBuilder(context, EmergencyDatabase::class.java, dbName)
            .build()

    /** Closes and reopens the database, which is as close to process death as a test gets. */
    private fun reopen() {
        db.close()
        db = open()
        store = RoomEmergencyStore(db.emergencyDao())
    }

    private fun event(id: String = "durable-1") = EmergencyEvent(
        eventId = id,
        riderId = "rl_durability",
        createdAt = 1_755_763_200_123L,
        lat = 17.4401,
        lng = 78.3489,
        accuracyMetres = 12,
        confidence = 82,
        triggers = listOf("peak_g", "inactivity"),
        origin = EventOrigin.MANUAL_TEST,
        signature = "sig-placeholder",
    )

    @Test
    fun anEmergencySurvivesTheDatabaseBeingClosedAndReopened() = runBlocking {
        store.persist(event())
        reopen()

        val recovered = store.get("durable-1")
        assertNotNull("the emergency must survive a reopen", recovered)
        assertEquals("rl_durability", recovered!!.riderId)
        assertEquals(82, recovered.confidence)
        assertEquals(17.4401, recovered.lat!!, 0.0000001)
        assertEquals(listOf("inactivity", "peak_g"), recovered.triggers.sorted())
        assertEquals(EventOrigin.MANUAL_TEST, recovered.origin)
        assertTrue("the simulated marker must survive", recovered.simulated)
    }

    @Test
    fun anUndeliveredEmergencyIsStillPendingAfterAReopen() = runBlocking {
        val queued = event().transitionTo(DeliveryState.QUEUED_OFFLINE, at = 1_000)
        store.persist(event())
        store.update(queued)
        reopen()

        val pending = store.pending()
        assertEquals("the queued emergency must be picked up again on next launch", 1, pending.size)
        assertEquals(DeliveryState.QUEUED_OFFLINE, pending.first().state)
    }

    @Test
    fun aFailedDeliveryLeavesTheEventOnDiskWithItsErrorAndAttemptCount() = runBlocking {
        store.persist(event())
        val failed = event()
            .transitionTo(DeliveryState.QUEUED_OFFLINE, at = 1_000)
            .transitionTo(DeliveryState.DELIVERY_ATTEMPT, transport = TransportKind.SIMULATED_RELAY, at = 1_100)
            .transitionTo(DeliveryState.QUEUED_OFFLINE, at = 1_200, error = "relay vanished")
        store.update(failed)
        reopen()

        val recovered = store.get("durable-1")!!
        assertEquals(DeliveryState.QUEUED_OFFLINE, recovered.state)
        assertEquals(1, recovered.attemptCount)
        assertEquals("relay vanished", recovered.lastError)
        assertTrue("a failed delivery must leave the event retryable", recovered.state.isPending)
    }

    @Test
    fun deliveryAttemptHistorySurvivesAndStaysInOrder() = runBlocking {
        store.persist(event())
        store.appendAttempt(
            DeliveryAttempt(eventId = "durable-1", at = 1_100, transport = TransportKind.BLE_RELAY, outcome = AttemptOutcome.UNAVAILABLE, detail = "not available")
        )
        store.appendAttempt(
            DeliveryAttempt(eventId = "durable-1", at = 1_200, transport = TransportKind.SIMULATED_RELAY, outcome = AttemptOutcome.FAILED, detail = "scripted failure")
        )
        store.appendAttempt(
            DeliveryAttempt(eventId = "durable-1", at = 1_300, transport = TransportKind.SIMULATED_RELAY, outcome = AttemptOutcome.SUCCESS, detail = "forwarded")
        )
        reopen()

        val attempts = store.attempts("durable-1")
        assertEquals("every attempt is kept, failures included", 3, attempts.size)
        assertEquals(AttemptOutcome.UNAVAILABLE, attempts[0].outcome)
        assertEquals(AttemptOutcome.FAILED, attempts[1].outcome)
        assertEquals(AttemptOutcome.SUCCESS, attempts[2].outcome)
        assertEquals(TransportKind.BLE_RELAY, attempts[0].transport)
        assertTrue("a simulated attempt stays labelled after a reopen", attempts[2].fidelity.let { !it.isEvidence })
    }

    @Test
    fun aDeliveredEmergencyIsRetainedRatherThanRemoved() = runBlocking {
        store.persist(event())
        val delivered = event()
            .transitionTo(DeliveryState.QUEUED_OFFLINE, at = 1_000)
            .transitionTo(DeliveryState.DELIVERY_ATTEMPT, transport = TransportKind.DIRECT_NETWORK, at = 1_100)
            .transitionTo(DeliveryState.DELIVERED, at = 1_200)
        store.update(delivered)
        reopen()

        val recovered = store.get("durable-1")
        assertNotNull("a delivered emergency is kept, not cleaned up", recovered)
        assertEquals(DeliveryState.DELIVERED, recovered!!.state)
        assertEquals(TransportKind.DIRECT_NETWORK, recovered.deliveredVia)
        assertEquals("but it is no longer pending", 0, store.pending().size)
        assertEquals("and it is still listed", 1, store.all().size)
    }

    @Test
    fun manyQueuedEmergenciesAllSurvive() = runBlocking {
        repeat(25) { i -> store.persist(event("durable-bulk-$i")) }
        reopen()
        assertEquals(25, store.all().size)
        assertEquals(25, store.pending().size)
    }
}
