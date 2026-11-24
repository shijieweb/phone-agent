package net.de5.phoneagent

import android.content.Context
import android.os.PowerManager
import android.provider.Settings

/**
 * The framework half of `screen` (SPEC.md §3.14). Never throws: every failure is
 * a sentence starting with [Admin.ERROR_PREFIX], so the executor maps it to
 * `exit=6` with one rule.
 */
internal object ScreenOps {

    private const val WAKE_LOCK_TAG = "phoneagent:screen"

    /** Long enough to light the panel, short enough not to fight the timeout. */
    private const val WAKE_LOCK_MS = 1_000L

    /**
     * `screen stayon [on|off]`.
     *
     * @param on null means "report only".
     */
    fun stayOn(context: Context, on: Boolean?): String {
        val prefs = Prefs(context)
        if (on == null) return ScreenPlan.stayOnLine(prefs.stayOn, currentTimeout(context))
        val target = if (on) ScreenPlan.STAY_ON_TIMEOUT else ScreenPlan.RESTORE_TIMEOUT
        val written = write(context, target)
        if (written.startsWith(Admin.ERROR_PREFIX)) return written
        prefs.stayOn = on
        return ScreenPlan.stayOnApplied(on)
    }

    /**
     * `screen timeout MS` — one explicit number, and the sticky flag is cleared:
     * a manual timeout is a newer decision than an earlier `stayon on`, so the
     * next boot must not silently put "永不" back.
     */
    fun setTimeout(context: Context, ms: Int): String {
        val written = write(context, ms)
        if (written.startsWith(Admin.ERROR_PREFIX)) return written
        Prefs(context).stayOn = false
        return ScreenPlan.timeoutApplied(ms)
    }

    /**
     * The boot path: put `stayon on` back without needing the relay, an
     * accessibility service or any user interaction.
     */
    fun reapplyStayOn(context: Context): String =
        write(context, ScreenPlan.STAY_ON_TIMEOUT)

    /** `screen on` — a bright wake lock is the one mechanism a sideloaded app has. */
    fun turnOn(context: Context): String {
        val power = try {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        } catch (t: Throwable) {
            null
        } ?: return ScreenPlan.failure("无法取得 PowerManager")
        try {
            @Suppress("DEPRECATION")
            val lock = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                WAKE_LOCK_TAG
            )
            lock.acquire(WAKE_LOCK_MS)
            if (lock.isHeld) lock.release()
        } catch (t: Throwable) {
            return ScreenPlan.failure(describe(t))
        }
        return ScreenPlan.turnedOn(isInteractive(power))
    }

    /**
     * `screen off`.
     *
     * `PowerManager.goToSleep` needs DEVICE_POWER (signature), so the honest path
     * is the device-admin lock, which turns the panel off as a side effect.
     */
    fun turnOff(context: Context): String {
        val locked = Admin.lockNow(context)
        if (locked.startsWith(Admin.ERROR_PREFIX)) return locked
        return ScreenPlan.turnedOff()
    }

    /** The live `screen_off_timeout`, or null when it cannot be read. */
    fun currentTimeout(context: Context): Long? = try {
        val value = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_OFF_TIMEOUT,
            -1
        )
        if (value < 0) null else value.toLong()
    } catch (t: Throwable) {
        null
    }

    private fun write(context: Context, ms: Int): String =
        Admin.setSetting(context, "system", Settings.System.SCREEN_OFF_TIMEOUT, ms.toString())

    private fun isInteractive(power: PowerManager): Boolean = try {
        power.isInteractive
    } catch (t: Throwable) {
        false
    }
}
