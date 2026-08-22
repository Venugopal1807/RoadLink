package com.roadlink.domain

import kotlinx.coroutines.flow.Flow

/**
 * Durable storage for emergencies.
 *
 * NOTE WHAT IS MISSING: there is no delete, no purge, no clear and no
 * expiry. That is the enforcement mechanism for the product's core invariant,
 * expressed in the type system rather than in a comment - no caller can drop
 * an emergency because delivery failed, because no such method is offered.
 *
 * [persist] must not return until the event is durably on disk. Everything in
 * the delivery path is built on that guarantee.
 */
interface EmergencyStore {

    /**
     * Write a newly confirmed emergency to disk.
     *
     * Must be durable on return: if the process is killed immediately after
     * this returns, the event must still be present on next launch. This is
     * the single call that the whole product depends on.
     */
    suspend fun persist(event: EmergencyEvent)

    /** Update delivery bookkeeping on an event that already exists. */
    suspend fun update(event: EmergencyEvent)

    suspend fun get(eventId: String): EmergencyEvent?

    /** Every event, newest first. */
    suspend fun all(): List<EmergencyEvent>

    /** Events not yet delivered, oldest first so the longest-waiting goes first. */
    suspend fun pending(): List<EmergencyEvent>

    fun observeAll(): Flow<List<EmergencyEvent>>

    /** Append-only. Attempts are never modified or removed. */
    suspend fun appendAttempt(attempt: DeliveryAttempt)

    suspend fun attempts(eventId: String): List<DeliveryAttempt>

    fun observeAttempts(eventId: String): Flow<List<DeliveryAttempt>>
}
