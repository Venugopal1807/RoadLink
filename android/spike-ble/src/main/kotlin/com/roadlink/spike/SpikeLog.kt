package com.roadlink.spike

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timestamped event log for the spike.
 *
 * Every BLE transition goes through here so a run can be reconstructed after
 * the fact. Two clocks are recorded on purpose:
 *
 *   wall  - SimpleDateFormat of System.currentTimeMillis(), for correlating
 *           across the two phones and the backend.
 *   +ms   - SystemClock.elapsedRealtime() delta since the marker was set,
 *           which is monotonic and immune to clock adjustment. This is the
 *           one to trust for latency measurements.
 *
 * Also mirrored to logcat under tag RLSPIKE so a run can be captured with
 *   adb logcat -s RLSPIKE
 */
object SpikeLog {

    const val TAG = "RLSPIKE"
    private const val MAX_LINES = 600

    private val handler = Handler(Looper.getMainLooper())
    private val wallFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()

    private var markerNanos: Long = SystemClock.elapsedRealtime()

    /** Notified on the main thread whenever a line is appended. */
    var listener: ((String) -> Unit)? = null

    /** Reset the monotonic stopwatch. Call at the start of each measured step. */
    @Synchronized
    fun mark(label: String) {
        markerNanos = SystemClock.elapsedRealtime()
        append("=== $label ===")
    }

    @Synchronized
    fun log(message: String) = append(message)

    fun logError(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
        append("ERROR: $message" + (error?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
    }

    @Synchronized
    private fun append(message: String) {
        val elapsed = SystemClock.elapsedRealtime() - markerNanos
        val line = "%s  +%6dms  %s".format(wallFormat.format(Date()), elapsed, message)

        Log.i(TAG, line)
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()

        handler.post { listener?.invoke(line) }
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        markerNanos = SystemClock.elapsedRealtime()
    }

    /** Lowercase hex, space separated. For dumping raw advertisement bytes (test T11). */
    fun hex(bytes: ByteArray?): String =
        bytes?.joinToString(" ") { "%02x".format(it) } ?: "<null>"
}
