package com.roadlink.domain

/**
 * The RoadLink domain model.
 *
 * Nothing in this file imports an Android BLE class, a Room class, or an HTTP
 * class. That is deliberate and load-bearing: the emergency event must be able
 * to exist, persist and survive with no reference to how it will eventually be
 * delivered. BLE is an opportunistic delivery mechanism, not the product.
 */

// ---------------------------------------------------------------- fidelity

/**
 * How much a given signal is worth as evidence.
 *
 * This exists so that a simulated result can never be quietly reported as a
 * physical one. Every transport and every event trigger carries a fidelity,
 * it is persisted, it is sent to the backend, and it is rendered in the UI.
 */
enum class Fidelity {
    /** Produced by real hardware doing the real thing. The only kind that counts as proof. */
    REAL,

    /** Produced by a deterministic in-process stand-in. Development only. */
    SIMULATED,

    /** Produced by a hand-written canned response. Development only. */
    MOCKED,

    /** Produced by an emulator rather than a physical device. Not device-verified. */
    EMULATED,
    ;

    val isEvidence: Boolean get() = this == REAL
}

// ------------------------------------------------------------------ origin

/** What caused this emergency to be created. */
enum class EventOrigin(val fidelity: Fidelity, val label: String) {
    /** A future SensorCrashDetector observed an actual impact. */
    REAL_SENSOR(Fidelity.REAL, "REAL"),

    /** Someone pressed CREATE TEST SOS. Same pipeline, simulated trigger. */
    MANUAL_TEST(Fidelity.SIMULATED, "SIMULATED"),
    ;

    /** Value sent to the backend's `simulated` field. Never omitted. */
    val isSimulated: Boolean get() = fidelity != Fidelity.REAL
}

// --------------------------------------------------------------- transport

/**
 * The ways an emergency can reach the backend.
 *
 * `wireName` is what the backend records as the delivery path. SIMULATED_RELAY
 * has its own wire name on purpose: a simulated delivery must never be recorded
 * as `ble_relay`, because that would put fabricated BLE evidence in the audit
 * trail we intend to quote.
 */
enum class TransportKind(
    val wireName: String,
    val fidelity: Fidelity,
    val label: String,
) {
    /** Phone-to-phone BLE relay. NOT yet validated on physical hardware. */
    BLE_RELAY("ble_relay", Fidelity.REAL, "BLE RELAY"),

    /** The rider's own phone reaching the backend over IP. */
    DIRECT_NETWORK("direct", Fidelity.REAL, "DIRECT NETWORK"),

    /** Deterministic in-process relay used while no second phone is available. */
    SIMULATED_RELAY("simulated_relay", Fidelity.SIMULATED, "SIMULATED RELAY"),
    ;

    val isSimulated: Boolean get() = fidelity != Fidelity.REAL
}

// ------------------------------------------------------------------- state

/**
 * Per-event delivery state. Persisted.
 *
 * The single most important property of this enum is what it does NOT contain:
 * there is no terminal failure state and no deleted state. A delivery failure
 * always returns the event to [QUEUED_OFFLINE], which is retryable. An
 * emergency is never discarded because we could not deliver it.
 */
enum class DeliveryState {
    /** Confirmed and durably on disk. No delivery attempted yet. */
    CONFIRMED_EMERGENCY,

    /** Waiting for any transport to become available. The safe resting state. */
    QUEUED_OFFLINE,

    /** A transport is actively working on this event right now. */
    DELIVERY_ATTEMPT,

    /** The backend has acknowledged the event. Terminal, and the only terminal state. */
    DELIVERED,
    ;

    val isTerminal: Boolean get() = this == DELIVERED

    /** Whether the event may still be picked up by a delivery pass. */
    val isPending: Boolean get() = !isTerminal

    companion object {
        /**
         * Legal transitions. Enforced by [EmergencyEvent.transitionTo] and
         * covered by unit tests, so an illegal move fails loudly rather than
         * corrupting an emergency's history.
         */
        private val allowed: Map<DeliveryState, Set<DeliveryState>> = mapOf(
            CONFIRMED_EMERGENCY to setOf(QUEUED_OFFLINE),
            // DELIVERY_ATTEMPT -> QUEUED_OFFLINE is the failure path, and it is
            // the reason no failure is ever terminal.
            QUEUED_OFFLINE to setOf(DELIVERY_ATTEMPT),
            DELIVERY_ATTEMPT to setOf(DELIVERED, QUEUED_OFFLINE),
            DELIVERED to emptySet(),
        )

        fun canMove(from: DeliveryState, to: DeliveryState): Boolean =
            to in (allowed[from] ?: emptySet())
    }
}

/** Raised when code attempts a transition the state machine forbids. */
class IllegalTransitionException(from: DeliveryState, to: DeliveryState) :
    IllegalStateException("illegal delivery transition: $from -> $to")

// ------------------------------------------------------------------- event

/**
 * A confirmed emergency.
 *
 * The fields above `state` are the signed packet: they are fixed at creation
 * and never change, so the byte-identical packet can be submitted by the rider
 * and by any number of relays without breaking the signature. The fields from
 * `state` down are local delivery bookkeeping and are deliberately outside the
 * signature.
 */
data class EmergencyEvent(
    // ---- signed packet (immutable after creation) ----
    val eventId: String,
    val riderId: String,
    val createdAt: Long,
    val lat: Double?,
    val lng: Double?,
    val accuracyMetres: Int?,
    val confidence: Int,
    val triggers: List<String>,
    val origin: EventOrigin,
    val signature: String?,

    // ---- local delivery bookkeeping (outside the signature) ----
    val state: DeliveryState = DeliveryState.CONFIRMED_EMERGENCY,
    val activeTransport: TransportKind? = null,
    val deliveredVia: TransportKind? = null,
    val deliveredAt: Long? = null,
    val attemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
) {
    init {
        require(confidence in 0..100) { "confidence out of range: $confidence" }
        require(eventId.isNotBlank()) { "eventId must not be blank" }
    }

    /** Value for the backend's `simulated` field. Never omitted, never inferred downstream. */
    val simulated: Boolean get() = origin.isSimulated

    val hasLocation: Boolean get() = lat != null && lng != null

    /**
     * True when nothing about this event constitutes real-world evidence.
     * A REAL_SENSOR event delivered over SIMULATED_RELAY is still partly
     * simulated, which is why this considers both axes.
     */
    val isFullyReal: Boolean
        get() = origin.fidelity.isEvidence && (deliveredVia?.fidelity?.isEvidence ?: true)

    /**
     * The vocabulary the responder UI and the logs use. Derived from the pair
     * (state, activeTransport) rather than stored, so adding a transport never
     * requires adding a state.
     */
    val statusLabel: String
        get() = when (state) {
            DeliveryState.CONFIRMED_EMERGENCY -> "CONFIRMED"
            DeliveryState.QUEUED_OFFLINE -> if (attemptCount > 0) "RETRYING" else "QUEUED"
            DeliveryState.DELIVERY_ATTEMPT -> when (activeTransport) {
                TransportKind.BLE_RELAY -> "BLE_RELAYING"
                TransportKind.DIRECT_NETWORK -> "DIRECT_UPLOAD"
                TransportKind.SIMULATED_RELAY -> "SIMULATED_RELAYING"
                null -> "DELIVERING"
            }
            DeliveryState.DELIVERED -> "DELIVERED"
        }

    fun ageSeconds(now: Long): Int =
        ((now - createdAt).coerceAtLeast(0L) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /**
     * Move to [target], or throw. Delivery bookkeeping is updated here so the
     * state and the fields that explain it can never drift apart.
     */
    fun transitionTo(
        target: DeliveryState,
        transport: TransportKind? = null,
        at: Long,
        error: String? = null,
    ): EmergencyEvent {
        if (!DeliveryState.canMove(state, target)) {
            throw IllegalTransitionException(state, target)
        }
        return when (target) {
            DeliveryState.DELIVERY_ATTEMPT -> copy(
                state = target,
                activeTransport = transport,
                attemptCount = attemptCount + 1,
                lastAttemptAt = at,
                lastError = null,
            )
            DeliveryState.DELIVERED -> copy(
                state = target,
                activeTransport = null,
                deliveredVia = transport ?: activeTransport,
                deliveredAt = at,
                lastError = null,
            )
            DeliveryState.QUEUED_OFFLINE -> copy(
                state = target,
                activeTransport = null,
                lastError = error ?: lastError,
            )
            DeliveryState.CONFIRMED_EMERGENCY -> copy(state = target)
        }
    }
}

// ----------------------------------------------------------------- attempt

/** Why a delivery attempt ended. */
enum class AttemptOutcome { SUCCESS, FAILED, UNAVAILABLE }

/**
 * One delivery attempt, appended and never modified or removed.
 *
 * This is the device-side mirror of the backend's audit table, and it is what
 * lets the demo show honestly how many attempts a delivery actually took.
 */
data class DeliveryAttempt(
    val id: Long = 0L,
    val eventId: String,
    val at: Long,
    val transport: TransportKind,
    val outcome: AttemptOutcome,
    val detail: String? = null,
) {
    /** Simulated attempts are labelled as such wherever they are displayed. */
    val fidelity: Fidelity get() = transport.fidelity
}
