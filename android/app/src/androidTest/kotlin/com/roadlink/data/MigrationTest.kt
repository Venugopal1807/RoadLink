package com.roadlink.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Schema migration v1 -> v2.
 *
 * This test exists because the alternative to a real migration is
 * `fallbackToDestructiveMigration`, which drops the table - and for this
 * product that means silently deleting undelivered emergencies on an app
 * update. A broken migration is the same failure wearing a different hat, so
 * the migration is exercised against a genuine v1 database rather than assumed.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "roadlink-migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EmergencyDatabase::class.java,
    )

    @Test
    fun migrate1To2PreservesAnUndeliveredEmergency() {
        // ---- create a v1 database holding a queued emergency ----
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                """
                INSERT INTO emergency_events (
                    eventId, riderId, createdAt, lat, lng, accuracyMetres, confidence,
                    triggers, origin, signature, state, activeTransport, deliveredVia,
                    deliveredAt, attemptCount, lastAttemptAt, lastError
                ) VALUES (
                    'legacy-1', 'rl_legacy', 1755763200123, 17.4401, 78.3489, 12, 82,
                    'peak_g,inactivity', 'MANUAL_TEST', 'sig-legacy', 'QUEUED_OFFLINE',
                    NULL, NULL, NULL, 3, 1755763300000, 'relay vanished'
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO delivery_attempts (eventId, at, transport, outcome, detail)
                VALUES ('legacy-1', 1755763250000, 'SIMULATED_RELAY', 'FAILED', 'scripted')
                """.trimIndent()
            )
            close()
        }

        // ---- migrate ----
        val db = helper.runMigrationsAndValidate(
            dbName, 2, true, EmergencyDatabase.MIGRATION_1_2
        )

        // ---- the emergency and its history must both survive ----
        db.query("SELECT * FROM emergency_events WHERE eventId = 'legacy-1'").use { cursor ->
            assertTrue("the queued emergency must survive the upgrade", cursor.moveToFirst())
            assertEquals("rl_legacy", cursor.getString(cursor.getColumnIndexOrThrow("riderId")))
            assertEquals("QUEUED_OFFLINE", cursor.getString(cursor.getColumnIndexOrThrow("state")))
            assertEquals(3, cursor.getInt(cursor.getColumnIndexOrThrow("attemptCount")))
            assertEquals(
                "relay vanished",
                cursor.getString(cursor.getColumnIndexOrThrow("lastError")),
            )
            // New columns exist and default sensibly.
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("relayedTo")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("relayedAt")))
            assertEquals(
                "a pre-existing event is this device's own, not relay custody",
                0,
                cursor.getInt(cursor.getColumnIndexOrThrow("collectedAsRelay")),
            )
        }

        db.query("SELECT COUNT(*) FROM delivery_attempts WHERE eventId = 'legacy-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("the audit trail must survive too", 1, cursor.getInt(0))
        }
        db.close()
    }

    @Test
    fun migratedDatabaseIsUsableThroughTheNormalStore() {
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(dbName, 2, true, EmergencyDatabase.MIGRATION_1_2).close()

        // Reopening through Room proper must succeed, i.e. the migrated schema
        // genuinely matches what the entities expect.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = androidx.room.Room
            .databaseBuilder(context, EmergencyDatabase::class.java, dbName)
            .addMigrations(EmergencyDatabase.MIGRATION_1_2)
            .build()
        try {
            assertNotNull(room.emergencyDao())
            val all = kotlinx.coroutines.runBlocking { room.emergencyDao().all() }
            assertFalse("schema opened cleanly", all.isNotEmpty() && all.first().eventId.isBlank())
        } finally {
            room.close()
            context.deleteDatabase(dbName)
        }
    }
}
