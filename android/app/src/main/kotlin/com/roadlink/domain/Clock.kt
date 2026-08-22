package com.roadlink.domain

/**
 * Wall-clock time, injectable so delivery logic is testable without waiting.
 *
 * Device time is used for `created_at` because it is the only clock the rider's
 * phone has. It is never used for ordering - the backend records its own
 * `received_at` for that, because two phones in a relay handoff can easily
 * disagree by minutes.
 */
fun interface Clock {
    fun now(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }

        /** Test clock that only moves when told to. */
        fun fixed(start: Long = 0L): MutableClock = MutableClock(start)
    }
}

class MutableClock(private var current: Long) : Clock {
    override fun now(): Long = current
    fun advanceBy(millis: Long) {
        current += millis
    }

    fun set(millis: Long) {
        current = millis
    }
}
