package com.roadlink.domain

/**
 * What the rider is told, in the rider's words.
 *
 * [EmergencyEvent.statusLabel] is the engineering vocabulary and stays as it
 * is - the logs, the audit trail and the tests are written against it. This is
 * the separate, deliberately smaller vocabulary shown on screen, where the only
 * question worth answering is "is my emergency safe, and has anyone got it
 * yet?".
 *
 * Two distinctions are load-bearing and must never be collapsed:
 *
 *  - [RELAYED] is not [DELIVERED]. A relay taking custody means another phone
 *    has the emergency on disk; that phone may never regain connectivity.
 *  - [RETAINED_AFTER_FAILURE] is not a failure state. Delivery failed; the
 *    emergency did not. Saying only "failed" would describe the attempt
 *    correctly and the situation wrongly, which is the exact confusion this
 *    product exists to remove.
 */
enum class RiderStatus(
    /** Shown as the status chip. Upper case, because it is read at a glance. */
    val label: String,
    /** One sentence under the chip. Says what is true right now, not what is hoped. */
    val meaning: String,
) {
    /** No emergency is outstanding on this device. */
    READY(
        "READY",
        "No emergency on this device. RoadLink is holding nothing.",
    ),

    /** Confirmed and durably on disk, before any delivery was attempted. */
    PERSISTED(
        "EMERGENCY PERSISTED",
        "Written to this phone's storage. It survives the app closing, the " +
            "process being killed and the battery dying.",
    ),

    /** Nothing could be tried yet. The ordinary offline resting state. */
    QUEUED(
        "OFFLINE / QUEUED",
        "No delivery path is available. The emergency is held here and retried " +
            "automatically.",
    ),

    /** A transport was tried and did not work. The emergency is untouched. */
    RETAINED_AFTER_FAILURE(
        "FAILED BUT RETAINED",
        "A delivery attempt failed. The emergency is still on disk and is still " +
            "being retried - a failed attempt never discards it.",
    ),

    /** A transport is working on it right now. */
    DELIVERING(
        "DELIVERING",
        "A delivery attempt is in progress.",
    ),

    /** A relay has custody. NOT delivered. */
    RELAYED(
        "RELAYED",
        "A nearby phone has taken custody. The backend does NOT have it yet, so " +
            "this phone keeps trying as well.",
    ),

    /** The backend acknowledged it. The only terminal state. */
    DELIVERED(
        "DELIVERED",
        "The backend acknowledged this emergency. A responder can see it.",
    ),
    ;

    val isTerminal: Boolean get() = this == DELIVERED

    companion object {

        /** What to show for one emergency. */
        fun of(event: EmergencyEvent): RiderStatus = when (event.state) {
            DeliveryState.DELIVERED -> DELIVERED
            DeliveryState.RELAYED -> RELAYED
            DeliveryState.DELIVERY_ATTEMPT -> DELIVERING
            DeliveryState.CONFIRMED_EMERGENCY -> PERSISTED
            // The distinction that matters: an emergency waiting because
            // nothing was available yet, versus one waiting because an attempt
            // was made and did not work. Both are safe; only one has a reason
            // the rider should be shown.
            DeliveryState.QUEUED_OFFLINE ->
                if (event.lastError != null) RETAINED_AFTER_FAILURE else QUEUED
        }

        /**
         * What to show for the device as a whole.
         *
         * The most urgent outstanding emergency wins, because a rider with one
         * queued and one delivered emergency is not in a delivered state.
         * READY only when nothing is outstanding at all.
         */
        fun ofDevice(events: List<EmergencyEvent>): RiderStatus {
            val pending = events.filter { it.state.isPending }
            if (pending.isEmpty()) return READY
            return pending.map { of(it) }.minByOrNull { urgency(it) } ?: READY
        }

        /** Lower is more urgent. Used only to pick which status represents the device. */
        private fun urgency(status: RiderStatus): Int = when (status) {
            RETAINED_AFTER_FAILURE -> 0
            QUEUED -> 1
            PERSISTED -> 2
            DELIVERING -> 3
            RELAYED -> 4
            DELIVERED -> 5
            READY -> 6
        }
    }
}
