package com.roadlink.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data access for emergencies.
 *
 * There is no @Delete anywhere in this interface, and there is no DELETE
 * statement in any @Query. An emergency that has been confirmed is never
 * removed from this device by application code.
 */
@Dao
interface EmergencyDao {

    /**
     * ABORT rather than REPLACE on conflict.
     *
     * REPLACE would let a second insert silently overwrite an already-stored
     * emergency, which is exactly the data loss this product exists to
     * prevent. The same first-write-wins rule the backend enforces.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: EmergencyEventEntity)

    @Update
    suspend fun update(event: EmergencyEventEntity)

    @Query("SELECT * FROM emergency_events WHERE eventId = :eventId")
    suspend fun get(eventId: String): EmergencyEventEntity?

    @Query("SELECT * FROM emergency_events ORDER BY createdAt DESC")
    suspend fun all(): List<EmergencyEventEntity>

    /** Oldest first: the emergency that has been waiting longest is delivered first. */
    @Query("SELECT * FROM emergency_events WHERE state != 'DELIVERED' ORDER BY createdAt ASC")
    suspend fun pending(): List<EmergencyEventEntity>

    @Query("SELECT * FROM emergency_events ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<EmergencyEventEntity>>

    @Insert
    suspend fun insertAttempt(attempt: DeliveryAttemptEntity)

    @Query("SELECT * FROM delivery_attempts WHERE eventId = :eventId ORDER BY id ASC")
    suspend fun attempts(eventId: String): List<DeliveryAttemptEntity>

    @Query("SELECT * FROM delivery_attempts WHERE eventId = :eventId ORDER BY id ASC")
    fun observeAttempts(eventId: String): Flow<List<DeliveryAttemptEntity>>
}
