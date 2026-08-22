package com.roadlink.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.roadlink.domain.AttemptOutcome
import com.roadlink.domain.DeliveryAttempt
import com.roadlink.domain.DeliveryState
import com.roadlink.domain.EmergencyEvent
import com.roadlink.domain.EventOrigin
import com.roadlink.domain.TransportKind

/**
 * Room entities.
 *
 * Enums are stored as their NAME rather than their ordinal on purpose:
 * reordering an enum must never silently reinterpret already-persisted
 * emergencies. Unknown names fall back conservatively rather than throwing,
 * so a schema drift degrades to a visible-but-intact event instead of an
 * unreadable database.
 */
@Entity(
    tableName = "emergency_events",
    indices = [Index("state"), Index("createdAt")],
)
data class EmergencyEventEntity(
    @PrimaryKey val eventId: String,
    val riderId: String,
    val createdAt: Long,
    val lat: Double?,
    val lng: Double?,
    val accuracyMetres: Int?,
    val confidence: Int,
    /** Comma-joined. Trigger names are simple identifiers, so this is unambiguous. */
    val triggers: String,
    val origin: String,
    val signature: String?,
    val state: String,
    val activeTransport: String?,
    val deliveredVia: String?,
    val deliveredAt: Long?,
    val attemptCount: Int,
    val lastAttemptAt: Long?,
    val lastError: String?,
)

@Entity(
    tableName = "delivery_attempts",
    indices = [Index("eventId")],
)
data class DeliveryAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val eventId: String,
    val at: Long,
    val transport: String,
    val outcome: String,
    val detail: String?,
)

// ------------------------------------------------------------------ mapping

fun EmergencyEvent.toEntity(): EmergencyEventEntity = EmergencyEventEntity(
    eventId = eventId,
    riderId = riderId,
    createdAt = createdAt,
    lat = lat,
    lng = lng,
    accuracyMetres = accuracyMetres,
    confidence = confidence,
    triggers = triggers.joinToString(","),
    origin = origin.name,
    signature = signature,
    state = state.name,
    activeTransport = activeTransport?.name,
    deliveredVia = deliveredVia?.name,
    deliveredAt = deliveredAt,
    attemptCount = attemptCount,
    lastAttemptAt = lastAttemptAt,
    lastError = lastError,
)

fun EmergencyEventEntity.toDomain(): EmergencyEvent = EmergencyEvent(
    eventId = eventId,
    riderId = riderId,
    createdAt = createdAt,
    lat = lat,
    lng = lng,
    accuracyMetres = accuracyMetres,
    confidence = confidence,
    triggers = if (triggers.isBlank()) emptyList() else triggers.split(","),
    // An unrecognised origin is treated as simulated, never as real. Failing
    // in the direction of "this is not evidence" is the safe default.
    origin = enumOrNull<EventOrigin>(origin) ?: EventOrigin.MANUAL_TEST,
    signature = signature,
    state = enumOrNull<DeliveryState>(state) ?: DeliveryState.QUEUED_OFFLINE,
    activeTransport = activeTransport?.let { enumOrNull<TransportKind>(it) },
    deliveredVia = deliveredVia?.let { enumOrNull<TransportKind>(it) },
    deliveredAt = deliveredAt,
    attemptCount = attemptCount,
    lastAttemptAt = lastAttemptAt,
    lastError = lastError,
)

fun DeliveryAttempt.toEntity(): DeliveryAttemptEntity = DeliveryAttemptEntity(
    id = id,
    eventId = eventId,
    at = at,
    transport = transport.name,
    outcome = outcome.name,
    detail = detail,
)

fun DeliveryAttemptEntity.toDomain(): DeliveryAttempt = DeliveryAttempt(
    id = id,
    eventId = eventId,
    at = at,
    transport = enumOrNull<TransportKind>(transport) ?: TransportKind.SIMULATED_RELAY,
    outcome = enumOrNull<AttemptOutcome>(outcome) ?: AttemptOutcome.FAILED,
    detail = detail,
)

private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
    runCatching { enumValueOf<T>(name) }.getOrNull()
