package net.de5.phoneagent

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences wrapper: relay base URL, shared secret, agent id,
 * auto-start and the periodic-location interval.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * The stored value, migrated off the retired direct route by
     * [resolveRelayBase]; see there for why the migration is not optional.
     */
    var relayBase: String
        get() {
            val stored = sp.getString(KEY_RELAY_BASE, null)
            val resolved = resolveRelayBase(stored)
            if (stored != null && stored.trim() != resolved) {
                sp.edit().putString(KEY_RELAY_BASE, resolved).apply()
            }
            return resolved
        }
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

        /**
         * The Cloudflare-fronted endpoint, not the direct `dsh.owen1.de5.net:19092`
         * one: the phone's carrier injects a forged RST into the first TLS flight
         * of the direct route, so that address only ever yields
         * "SocketException: Connection reset" on this device.
         */
        const val DEFAULT_RELAY_BASE = "https://mcp.owen1.de5.net"

        /** Shipped as the default up to v0.2.0; carriers reset its TLS flight. */
        const val LEGACY_RELAY_BASE = "https://dsh.owen1.de5.net:19092"
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
