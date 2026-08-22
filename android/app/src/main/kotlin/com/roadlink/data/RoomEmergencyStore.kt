package com.roadlink.data

import com.roadlink.domain.DeliveryAttempt
import com.roadlink.domain.EmergencyEvent
import com.roadlink.domain.EmergencyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed [EmergencyStore].
 *
 * The mapping is deliberately thin. Everything that decides product behaviour
 * lives in the domain layer; this class only moves rows.
 */
class RoomEmergencyStore(private val dao: EmergencyDao) : EmergencyStore {

    override suspend fun persist(event: EmergencyEvent) {
        // Room's suspend insert completes only once the transaction is
        // committed, and the database runs with synchronous=FULL, so by the
        // time this returns the emergency is on disk.
        dao.insert(event.toEntity())
    }

    override suspend fun update(event: EmergencyEvent) = dao.update(event.toEntity())

    override suspend fun get(eventId: String): EmergencyEvent? = dao.get(eventId)?.toDomain()

    override suspend fun all(): List<EmergencyEvent> = dao.all().map { it.toDomain() }

    override suspend fun pending(): List<EmergencyEvent> = dao.pending().map { it.toDomain() }

    override fun observeAll(): Flow<List<EmergencyEvent>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun appendAttempt(attempt: DeliveryAttempt) =
        dao.insertAttempt(attempt.toEntity())

    override suspend fun attempts(eventId: String): List<DeliveryAttempt> =
        dao.attempts(eventId).map { it.toDomain() }

    override fun observeAttempts(eventId: String): Flow<List<DeliveryAttempt>> =
        dao.observeAttempts(eventId).map { rows -> rows.map { it.toDomain() } }
}
