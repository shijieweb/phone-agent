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

    /**
     * The shared secret. A build made on the operator's machine carries it in
     * [BuildConfig.DEFAULT_RELAY_SECRET]; a plain clone carries "" and the app
     * asks a human. Falling back here (rather than in the UI) means every path —
     * service start, boot receiver, diagnostics — sees the same value.
     */
    var secret: String
        get() = sp.getString(KEY_SECRET, null) ?: BuildConfig.DEFAULT_RELAY_SECRET
        set(value) {
            sp.edit().putString(KEY_SECRET, value).apply()
        }

    var agentId: String
        get() = sanitizeId(sp.getString(KEY_AGENT_ID, null) ?: defaultAgentId())
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

    /**
     * The three animation scales as they were when `anim save` ran.
     *
     * Null means "never saved", which `anim restore` reports as an error rather
     * than silently writing a guess: restoring a wrong scale is worse than
     * refusing, because the phone then animates at a speed its owner never chose.
     */
    var animWindow: String?
        get() = sp.getString(KEY_ANIM_WINDOW, null)
        set(value) {
            sp.edit().putString(KEY_ANIM_WINDOW, value).apply()
        }

    var animTransition: String?
        get() = sp.getString(KEY_ANIM_TRANSITION, null)
        set(value) {
            sp.edit().putString(KEY_ANIM_TRANSITION, value).apply()
        }

    var animAnimator: String?
        get() = sp.getString(KEY_ANIM_ANIMATOR, null)
        set(value) {
            sp.edit().putString(KEY_ANIM_ANIMATOR, value).apply()
        }

    /**
     * "Keep the panel on" (`screen stayon on`).
     *
     * Stored because `Settings.System.SCREEN_OFF_TIMEOUT` does not survive a
     * reboot: the ROM rewrites it from its own default on every boot, so the
     * decision has to live somewhere we control and be re-applied by
     * [BootReceiver].
     */
    var stayOn: Boolean
        get() = sp.getBoolean(KEY_STAY_ON, false)
        set(value) {
            sp.edit().putBoolean(KEY_STAY_ON, value).apply()
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
        const val KEY_ANIM_WINDOW = "anim_window"
        const val KEY_ANIM_TRANSITION = "anim_transition"
        const val KEY_ANIM_ANIMATOR = "anim_animator"
        const val KEY_STAY_ON = "stay_on"

        /**
         * The direct route on the operator's *own paid* domain, no Cloudflare in
         * front. The three names this app used before all lived under
         * `de5.net` — a free domain handed out to anyone who asked, which is
         * exactly why carriers put it on their TLS-SNI block list: the reset
         * arrives 13–29 ms after the ClientHello, far inside one RTT, so it is
         * injected near the client rather than by our server. Probe 10 settled
         * it: same IP, same port, only the SNI changed — a `de5.net` name reset,
         * an unrelated name completed the handshake. `2-c.cc` is registered to
         * the operator alone, and the name is the only thing that was ever
         * blocked; the port was never the problem.
         */
        const val DEFAULT_RELAY_BASE = "https://dsh.2-c.cc:19092"

        /**
         * Addresses this app itself shipped as the default and wrote into
         * storage on first run. A stored copy of any of them is not a human
         * choice, so it must be migrated to [DEFAULT_RELAY_BASE] — otherwise an
         * upgrade reproduces the exact handshake-reset failure the migration
         * exists to fix. Anything not in this list is the operator's own and is
         * kept.
         */
        val RETIRED_RELAY_BASES = listOf(
            "https://dsh.owen1.de5.net:19092", // shipped up to v0.2.0
            "https://mcp.owen1.de5.net", // shipped v0.3.0–v0.6.0
            "https://relay.2-c.cc", // shipped v0.6.1; Cloudflare detour, now retired
        )
        const val DEFAULT_AGENT_ID = "PHONE-MEIZU"
        const val DEFAULT_AUTO_START = true
        const val DEFAULT_LOCATION_INTERVAL = 300

        /**
         * The id this build was made for: the baked one when the operator built
         * it, otherwise the shipped fallback. Sanitised because a hand-edited
         * local.properties can hold anything and the relay rejects the rest.
         */
        fun defaultAgentId(): String {
            val baked = BuildConfig.DEFAULT_AGENT_ID
            return sanitizeId(if (baked.isBlank()) DEFAULT_AGENT_ID else baked)
        }

        /** Same idea as [defaultAgentId]: baked when present, shipped default otherwise. */
        fun defaultRelayBase(): String {
            val baked = BuildConfig.DEFAULT_RELAY_BASE
            return if (baked.isBlank()) DEFAULT_RELAY_BASE else baked
        }

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
