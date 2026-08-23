package com.roadlink.platform

import android.content.Context

/**
 * The backend address, changeable on the device.
 *
 * The build property `roadlink.backendUrl` supplies the default, but a build
 * time default alone would make the APK useless to anyone who is not on the
 * network it was built against: an installed copy would point at a machine the
 * new owner cannot reach, with no way to correct it short of recompiling.
 *
 * So the address is stored per install and read on every request. The build
 * default is only the starting value, and [reset] returns to it.
 */
class BackendConfig(
    context: Context,
    private val buildDefault: String,
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val default: String get() = buildDefault

    /** Current address. Never blank. */
    var url: String
        get() = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: buildDefault
        set(value) {
            val cleaned = normalise(value)
            if (cleaned == null) return
            prefs.edit().putString(KEY_URL, cleaned).apply()
        }

    val isCustom: Boolean get() = url != buildDefault

    fun reset() = prefs.edit().remove(KEY_URL).apply()

    companion object {
        private const val PREFS = "roadlink"
        private const val KEY_URL = "backend_url"

        /**
         * Tidy user input into something [java.net.URL] will accept, or null if
         * it cannot be salvaged. A bare host or host:port is assumed to be
         * http, because the prototype backend has no certificate.
         */
        fun normalise(raw: String): String? {
            val text = raw.trim()
            if (text.isEmpty()) return null

            // Detect the scheme BEFORE trimming slashes. Trimming first turns a
            // scheme-only "http://" into "http:", which then looks like a
            // hostname and yields "http://http:".
            val scheme = when {
                text.startsWith("http://", ignoreCase = true) -> "http://"
                text.startsWith("https://", ignoreCase = true) -> "https://"
                else -> null
            }

            val body = (if (scheme != null) text.substring(scheme.length) else text)
                .trimEnd('/')
            if (body.isEmpty() || body.startsWith(":") || body.startsWith("/")) return null

            // Everything up to the first ':' or '/' must be a non-empty host.
            if (body.substringBefore('/').substringBefore(':').isEmpty()) return null

            return (scheme ?: "http://") + body
        }
    }
}
