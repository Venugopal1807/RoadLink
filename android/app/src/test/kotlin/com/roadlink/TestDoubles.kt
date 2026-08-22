package com.roadlink

import com.roadlink.delivery.Transport
import com.roadlink.delivery.TransportResult
import com.roadlink.domain.DeliveryAttempt
import com.roadlink.domain.EmergencyEvent
import com.roadlink.domain.EmergencyStore
import com.roadlink.domain.EventOrigin
import com.roadlink.domain.TransportKind
import com.roadlink.net.IngestResponse
import com.roadlink.net.SosApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Shared test doubles.
 *
 * Every double writes to a common [journal] so tests can assert on the ORDER
 * of operations across components - which is the only way to prove
 * persist-before-transmit rather than merely assuming it.
 */

class RecordingStore(
    val journal: MutableList<String> = mutableListOf(),
    /** When set, [persist] throws. Used to prove nothing is transmitted after a failed write. */
    var failPersistWith: Throwable? = null,
) : EmergencyStore {

    private val events = MutableStateFlow<Map<String, EmergencyEvent>>(emptyMap())
    private val attemptLog = MutableStateFlow<List<DeliveryAttempt>>(emptyList())

    /** Counts how many times an event was written for the first time. */
    var persistCount: Int = 0
        private set

    override suspend fun persist(event: EmergencyEvent) {
        failPersistWith?.let {
            journal += "PERSIST_FAILED ${event.eventId}"
            throw it
        }
        check(!events.value.containsKey(event.eventId)) { "duplicate persist of ${event.eventId}" }
        persistCount++
        journal += "PERSIST ${event.eventId}"
        events.value = events.value + (event.eventId to event)
    }

    override suspend fun update(event: EmergencyEvent) {
        journal += "UPDATE ${event.eventId} -> ${event.state}"
        events.value = events.value + (event.eventId to event)
    }

    override suspend fun get(eventId: String): EmergencyEvent? = events.value[eventId]

    override suspend fun all(): List<EmergencyEvent> =
        events.value.values.sortedByDescending { it.createdAt }

    override suspend fun pending(): List<EmergencyEvent> =
        events.value.values.filter { it.state.isPending }.sortedBy { it.createdAt }

    override fun observeAll(): Flow<List<EmergencyEvent>> =
        events.map { it.values.sortedByDescending { e -> e.createdAt } }

    override suspend fun appendAttempt(attempt: DeliveryAttempt) {
        attemptLog.value = attemptLog.value + attempt.copy(id = attemptLog.value.size + 1L)
    }

    override suspend fun attempts(eventId: String): List<DeliveryAttempt> =
        attemptLog.value.filter { it.eventId == eventId }

    override fun observeAttempts(eventId: String): Flow<List<DeliveryAttempt>> =
        attemptLog.map { list -> list.filter { it.eventId == eventId } }

    /** Simulates process death: everything not written here would be gone. */
    fun snapshotOnDisk(): List<EmergencyEvent> = events.value.values.toList()
}

/**
 * A transport whose behaviour is fully scripted.
 *
 * [results] is consumed one entry per [deliver] call; the last entry repeats
 * once exhausted, so a test can say "fail twice then succeed forever".
 */
class ScriptedTransport(
    override val kind: TransportKind,
    private val journal: MutableList<String>,
    var available: Boolean = true,
    private val results: MutableList<TransportResult> = mutableListOf(TransportResult.Delivered()),
) : Transport {

    var deliverCalls: Int = 0
        private set

    override suspend fun isAvailable(): Boolean = available

    override suspend fun deliver(event: EmergencyEvent): TransportResult {
        deliverCalls++
        journal += "DELIVER ${event.eventId} via $kind"
        val result = if (results.size > 1) results.removeAt(0) else results.first()
        journal += "RESULT ${result::class.simpleName} via $kind"
        return result
    }

    companion object {
        fun alwaysDelivers(kind: TransportKind, journal: MutableList<String>) =
            ScriptedTransport(kind, journal, results = mutableListOf(TransportResult.Delivered()))

        fun alwaysFails(kind: TransportKind, journal: MutableList<String>, reason: String = "scripted failure") =
            ScriptedTransport(kind, journal, results = mutableListOf(TransportResult.Failed(reason)))

        fun unavailable(kind: TransportKind, journal: MutableList<String>) =
            ScriptedTransport(kind, journal, available = false)

        /** Fails [times] times, then delivers forever after. */
        fun failsThenDelivers(kind: TransportKind, journal: MutableList<String>, times: Int) =
            ScriptedTransport(
                kind, journal,
                results = (List(times) { TransportResult.Failed("scripted failure ${it + 1}") }
                    + TransportResult.Delivered()).toMutableList(),
            )
    }
}

/**
 * In-memory stand-in for the ingestion backend.
 *
 * Reproduces the two behaviours the device actually depends on: idempotency on
 * event_id, and an append-only audit trail. Those are verified against the real
 * server by backend/tests/test_idempotency.py; this double exists so delivery
 * logic can be tested without a running uvicorn.
 */
class FakeSosApi(
    private val journal: MutableList<String> = mutableListOf(),
) : SosApi {

    var online: Boolean = true
    var failNextSubmitWith: Throwable? = null
    var rejectWithStatus: Int? = null

    /** event_id -> the delivery paths that submitted it, in order. */
    val submissions = linkedMapOf<String, MutableList<String>>()

    override suspend fun health(): Boolean = online

    override suspend fun submit(
        event: EmergencyEvent,
        via: TransportKind,
        relayId: String?,
        relayReceivedAt: Long?,
    ): IngestResponse {
        failNextSubmitWith?.let { failNextSubmitWith = null; throw it }
        rejectWithStatus?.let {
            return IngestResponse(it, false, false, null, null, "rejected")
        }

        val paths = submissions.getOrPut(event.eventId) { mutableListOf() }
        val duplicate = paths.isNotEmpty()
        paths += via.wireName
        journal += "BACKEND ${event.eventId} via ${via.wireName} duplicate=$duplicate"

        return IngestResponse(
            httpStatus = if (duplicate) 200 else 201,
            duplicate = duplicate,
            sigValid = true,
            state = "RECEIVED",
            auditEntries = paths.size,
            raw = """{"duplicate":$duplicate}""",
        )
    }

    /** How many distinct emergencies the backend holds. */
    val eventCount: Int get() = submissions.size

    /** Total submissions across all events - the audit-row equivalent. */
    val auditCount: Int get() = submissions.values.sumOf { it.size }

    fun pathsFor(eventId: String): List<String> = submissions[eventId].orEmpty()
}

fun testEvent(
    eventId: String = "evt-1",
    createdAt: Long = 1_000L,
    origin: EventOrigin = EventOrigin.MANUAL_TEST,
): EmergencyEvent = EmergencyEvent(
    eventId = eventId,
    riderId = "rl_test",
    createdAt = createdAt,
    lat = 17.4401,
    lng = 78.3489,
    accuracyMetres = 12,
    confidence = 82,
    triggers = listOf("peak_g", "inactivity"),
    origin = origin,
    signature = "test-signature",
)
