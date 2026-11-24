package net.de5.phoneagent

/**
 * `screen` — keep the panel awake, and turn it on or off (SPEC.md §3.14).
 *
 * The "stay on" switch is the reason this command exists: the operator drives
 * this phone from a chat window and needs it to answer at 3 a.m. without the
 * panel having gone dark, which also stops the ROM from throttling the poll
 * loop. It has to survive a reboot, so it is stored in prefs as well as written
 * into Settings.System — the ROM resets `screen_off_timeout` on every boot.
 *
 * Everything provable (the clamps, the wording, the value mapping) lives here;
 * the framework calls live in [ScreenOps].
 */
internal object ScreenPlan {

    /** `screen_off_timeout` takes milliseconds in an int: this is "never". */
    const val STAY_ON_TIMEOUT = Int.MAX_VALUE

    /** What `stayon off` restores: Android's own one-minute default. */
    const val RESTORE_TIMEOUT = 60_000

    /** Anything shorter than this turns the panel off while the operator reads. */
    const val TIMEOUT_MIN = 15_000

    const val USAGE = "usage: screen stayon [on|off] | screen timeout MS | screen on | screen off"

    /** Clamp a human number into the range Settings.System will accept. */
    fun clampTimeout(ms: Long): Int =
        ms.coerceIn(TIMEOUT_MIN.toLong(), STAY_ON_TIMEOUT.toLong()).toInt()

    /** The value written for `stayon on`, as a string (Settings stores strings). */
    fun stayOnValue(): String = STAY_ON_TIMEOUT.toString()

    /**
     * A timeout as a human reads it: `永不` for the sentinel, whole seconds when
     * it is a whole number of seconds, raw milliseconds otherwise.
     */
    fun timeoutText(ms: Long?): String = when {
        ms == null || ms < 0L -> "未知"
        ms >= STAY_ON_TIMEOUT.toLong() -> "永不"
        ms % 1000L == 0L -> (ms / 1000L).toString() + "s"
        else -> ms.toString() + "ms"
    }

    /** `screen stayon` with no argument: the stored decision and the live value. */
    fun stayOnLine(on: Boolean, current: Long?): String =
        "常亮: " + (if (on) "开" else "关") + " 当前超时=" + timeoutText(current)

    fun stayOnApplied(on: Boolean): String =
        if (on) {
            "已开启常亮（屏幕超时=永不）"
        } else {
            "已关闭常亮（屏幕超时=" + timeoutText(RESTORE_TIMEOUT.toLong()) + "）"
        }

    fun timeoutApplied(ms: Int): String = "已设置屏幕超时=" + timeoutText(ms.toLong())

    fun turnedOn(screenOn: Boolean): String =
        if (screenOn) "已点亮屏幕" else "已请求亮屏，但屏幕仍是熄的（ROM 拒绝了后台亮屏）"

    /** The only door a sideloaded app has for turning the panel off is locking. */
    fun turnedOff(): String = "已熄屏（同时锁屏，需设备管理器已激活）"

    fun failure(reason: String): String = "屏幕控制失败: " + reason
}
