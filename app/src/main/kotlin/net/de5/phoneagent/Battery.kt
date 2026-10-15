package net.de5.phoneagent

import android.content.Context
import android.os.PowerManager

/**
 * Doze / App-Standby exemption.
 *
 * ## Why this matters
 *
 * The agent lives on an outbound long poll. When the screen goes off, an
 * un-exempted app is frozen by Doze and the poll stops being serviced, which the
 * relay can only interpret as "offline" (it judges liveness by a 90 s heartbeat).
 * Measured on the operator's phone before this existed: heartbeat gaps of 138 s
 * and 151 s, plus a whole-night gap of 9705 s.
 *
 * Holding a partial `WakeLock` (which the service already does) keeps the CPU
 * awake while the app is scheduled, but it does **not** exempt the app from
 * Doze. Only the battery-optimisation whitelist does that, and it must be
 * requested by the user.
 *
 * The permission is declared in the manifest. Play policy forbids
 * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for distributed apps; this app
 * is sideloaded, so it applies.
 */
internal object Battery {

    /**
     * True when the OS will let us run while the screen is off. Never throws:
     * a status line must render even if the framework call misbehaves.
     */
    fun isExempt(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        manager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    } catch (t: Throwable) {
        false
    }

    /** `on` / `off`, for the `status` JSON and the `env` field. */
    fun flag(context: Context): String = if (isExempt(context)) "on" else "off"
}
