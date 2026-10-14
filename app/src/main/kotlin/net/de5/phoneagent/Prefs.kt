package net.de5.phoneagent

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences wrapper: relay base URL, shared secret, agent id,
 * auto-start and the periodic-location interval.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var relayBase: String
        get() = sp.getString(KEY_RELAY_BASE, DEFAULT_RELAY_BASE) ?: DEFAULT_RELAY_BASE
        set(value) {
            sp.edit().putString(KEY_RELAY_BASE, value).apply()
        }

    var secret: String
        get() = sp.getString(KEY_SECRET, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_SECRET, value).apply()
        }

    var agentId: String
        get() = sanitizeId(sp.getString(KEY_AGENT_ID, DEFAULT_AGENT_ID) ?: DEFAULT_AGENT_ID)
        set(value) {
            sp.edit().putString(KEY_AGENT_ID, sanitizeId(value)).apply()
        }

    var autoStart: Boolean
        get() = sp.getBoolean(KEY_AUTO_START, true)
        set(value) {
            sp.edit().putBoolean(KEY_AUTO_START, value).apply()
        }

    /** Interval of the periodic location reporter in seconds. */
    var locationInterval: Int
        get() = sp.getInt(KEY_LOCATION_INTERVAL, DEFAULT_LOCATION_INTERVAL)
        set(value) {
            sp.edit().putInt(KEY_LOCATION_INTERVAL, value).apply()
        }

    /** The agent cannot dial out without both of these. */
    fun isConfigured(): Boolean = relayBase.isNotBlank() && secret.isNotBlank()

    companion object {
        const val FILE = "phoneagent"
        const val KEY_RELAY_BASE = "relay_base"
        const val KEY_SECRET = "secret"
        const val KEY_AGENT_ID = "agent_id"
        const val KEY_AUTO_START = "auto_start"
        const val KEY_LOCATION_INTERVAL = "location_interval"

        const val DEFAULT_RELAY_BASE = "https://dsh.owen1.de5.net:19092"
        const val DEFAULT_AGENT_ID = "PHONE-MEIZU"
        const val DEFAULT_AUTO_START = true
        const val DEFAULT_LOCATION_INTERVAL = 300

        /** The relay rejects anything else: `^[A-Za-z0-9_.-]{1,64}$`. */
        fun sanitizeId(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return DEFAULT_AGENT_ID
            var i = 0
            while (i < trimmed.length) {
                val c = trimmed[i]
                val ok = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
                    c == '_' || c == '.' || c == '-'
                if (!ok) return DEFAULT_AGENT_ID
                i++
            }
            return if (trimmed.length > 64) trimmed.substring(0, 64) else trimmed
        }
    }
}
