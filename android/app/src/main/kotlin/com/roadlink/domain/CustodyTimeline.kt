package com.roadlink.domain

/**
 * The life of one emergency, as an ordered list of things that actually
 * happened.
 *
 * Every step below is derived from stored data - the event's own fields and its
 * append-only attempt trail. Nothing here is inferred, smoothed or invented: if
 * a step is shown, a row exists that says so. That is the point. The claim
 * RoadLink makes is about custody, and custody is only worth anything if it can
 * be inspected afterwards.
 *
 * Timestamps come from the device clock, which is the only clock this phone
 * has. The backend records its own `received_at` for ordering, and the
 * responder view shows that instead - two phones in a relay handoff can
 * disagree by minutes.
 */
data class CustodyStep(
    val at: Long,
    val kind: Kind,
    /** Short, upper-case, read at a glance. */
    val title: String,
    /** One line of plain language. Null when the title says everything. */
    val detail: String?,
    /**
     * The fidelity of the transport involved, or null for steps that are not
     * about a transport. Rendered so a simulated hop can never be mistaken for
     * a real one.
     */
    val transport: TransportKind? = null,
) {
    /**
     * Every kind here is produced by [CustodyTimeline.of]. Nothing is declared
     * "for later": an unused constant is a branch every renderer has to handle
     * and no reader can ever see.
     */
    enum class Kind {
        /** Confirmed and written to disk. Always the first step. */
        PERSISTED,

        /** A transport was tried and failed. The emergency was retained. */
        FAILED,

        /** A transport reported itself unusable, so it was never tried. */
        UNAVAILABLE,

        /** An attempt whose outcome was never observed, because the process died. */
        INTERRUPTED,

        /** A relay took custody. Progress, not arrival. */
        RELAYED,

        /** The backend acknowledged it. */
        DELIVERED,

        /** Where the emergency stands now, when it is still outstanding. */
        WAITING,
    }

    val isSimulated: Boolean get() = transport?.isSimulated == true
}

object CustodyTimeline {

    /**
     * Build the timeline for [event] from its [attempts].
     *
     * [attempts] is expected oldest-first, as the store returns it; it is
     * sorted defensively so a caller cannot produce a misleading order.
     */
    fun of(event: EmergencyEvent, attempts: List<DeliveryAttempt>): List<CustodyStep> {
        val steps = mutableListOf<CustodyStep>()

        // The emergency exists and is safe. This step is first because in the
        // code it genuinely is first - persistence precedes any transport call,
        // and DeliveryInvariantTest asserts that ordering.
        steps += CustodyStep(
            at = event.createdAt,
            kind = CustodyStep.Kind.PERSISTED,
            title = "PERSISTED",
            detail = "Confirmed and written to disk before any delivery was attempted" +
                if (event.origin.isSimulated) " (simulated trigger)" else "",
        )

        for (attempt in attempts.sortedBy { it.at }) {
            val label = attempt.transport.label
            steps += when (attempt.outcome) {
                AttemptOutcome.SUCCESS -> CustodyStep(
                    at = attempt.at,
                    kind = CustodyStep.Kind.DELIVERED,
                    title = "DELIVERED via $label",
                    detail = attempt.detail,
                    transport = attempt.transport,
                )

                AttemptOutcome.HANDED_OFF -> CustodyStep(
                    at = attempt.at,
                    kind = CustodyStep.Kind.RELAYED,
                    title = "RELAYED via $label",
                    // Stated on the step itself rather than only in a legend,
                    // because this is the one line a reader is most likely to
                    // misread as success.
                    detail = (attempt.detail?.plus(" — ") ?: "") +
                        "custody only; the backend does not have it yet",
                    transport = attempt.transport,
                )

                AttemptOutcome.FAILED -> CustodyStep(
                    at = attempt.at,
                    kind = CustodyStep.Kind.FAILED,
                    title = "ATTEMPT FAILED via $label",
                    detail = (attempt.detail?.plus(" — ") ?: "") + "emergency retained",
                    transport = attempt.transport,
                )

                AttemptOutcome.UNAVAILABLE -> CustodyStep(
                    at = attempt.at,
                    kind = CustodyStep.Kind.UNAVAILABLE,
                    title = "$label UNAVAILABLE",
                    detail = attempt.detail,
                    transport = attempt.transport,
                )

                AttemptOutcome.INTERRUPTED -> CustodyStep(
                    at = attempt.at,
                    kind = CustodyStep.Kind.INTERRUPTED,
                    title = "ATTEMPT INTERRUPTED via $label",
                    detail = (attempt.detail?.plus(" — ") ?: "") +
                        "outcome unknown; returned to the retry queue",
                    transport = attempt.transport,
                )
            }
        }

        // Where it stands now. Only for an emergency still outstanding: a
        // delivered one ends at its delivery, and adding anything after that
        // would suggest work still in progress.
        if (event.state.isPending) {
            steps += CustodyStep(
                at = event.lastAttemptAt ?: event.createdAt,
                kind = CustodyStep.Kind.WAITING,
                title = "HELD ON THIS PHONE",
                detail = when {
                    event.state == DeliveryState.RELAYED ->
                        "A relay has custody. This phone keeps trying independently, " +
                            "which is safe because the backend accepts the same emergency once."
                    event.lastError != null ->
                        "Retrying automatically. Last reason: ${event.lastError}"
                    else ->
                        "Retrying automatically until a delivery path becomes available."
                },
            )
        }

        return steps
    }
}
