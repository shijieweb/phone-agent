package net.de5.phoneagent

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock

/**
 * The `wake` command (SPEC.md section 3.2).
 *
 * Layered on purpose: no single mechanism turns a screen on reliably across
 * ROMs, and the operator needs to know *which* layer worked so an OS restriction
 * (background-activity-start limits, an aggressive Flyme battery manager) can be
 * told apart from a bug in this app.
 *
 *    1. already interactive        -> report and return
 *    2. screen-bright wake lock    -> hold ~1 s, release, re-check
 *    3. translucent WakeActivity   -> start, wait ~1.5 s, re-check
 *    4. non-secure keyguard        -> requestDismissKeyguard
 *
 * **Nothing here may throw.** Being blocked by a background-start restriction is
 * a reported outcome, not a crash: the result carries `via=none` and `exit=4`.
 */
internal object Wake {

    /** How long to hold the wake lock before giving up on layer 2. */
    const val WAKELOCK_HOLD_MS = 1_000L

    /** How long to wait for the translucent Activity to light the screen. */
    const val ACTIVITY_WAIT_MS = 1_500L

    /** Poll step while waiting for the screen to come on. */
    private const val POLL_STEP_MS = 100L

    private const val WAKE_LOCK_TAG = "phoneagent:wake"

    fun wake(context: Context): ExecutionResult {
        val power = try {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        } catch (t: Throwable) {
            null
        }
        return wakeForTesting(
            power = power,
            keyguard = keyguardState(context),
            screenStaysOff = false,
            startActivity = { startWakeActivity(context) },
            steps = WakeSteps(
                isInteractive = { isInteractive(power) },
                acquireWakeLock = { acquireBrightWakeLock(power) },
                settle = { waitForInteractive(power, it) },
                dismissKeyguard = { dismissKeyguardIfSafe(context) },
                keyguardNow = { keyguardState(context) }
            )
        )
    }

    /**
     * The layered decision, with every framework touch injected.
     *
     * Keeping this seam let the whole layer order — *including* the "the ROM
     * blocked our activity" path — be unit tested on a plain JVM, which the
     * SPEC's "wake must never throw" requirement really needs.
     */
    fun wakeForTesting(
        power: PowerManager?,
        keyguard: String,
        /** When true, `isInteractive` never becomes true: the worst case. */
        screenStaysOff: Boolean,
        startActivity: () -> Boolean,
        steps: WakeSteps = WakeSteps()
    ): ExecutionResult {
        val on = { steps.isInteractive() && !screenStaysOff }

        // Layer 1: already on.
        if (on()) return reportLineResult("already-on", true, keyguard)

        // Layer 2: the (deprecated but functional) screen-bright wake lock.
        steps.acquireWakeLock()
        steps.settle(WAKELOCK_HOLD_MS)
        if (on()) {
            return reportLineResult("wakelock", true, rescueKeyguard(steps, keyguard))
        }

        // Layer 3: a translucent Activity with turnScreenOn + showWhenLocked.
        // It also dismisses a non-secure keyguard, because
        // `KeyguardManager.requestDismissKeyguard` needs an Activity context and
        // this is the only Activity we have.
        val activityStarted = try {
            startActivity()
        } catch (t: Throwable) {
            AgentLog.add("wake", "WakeActivity 被拒绝: " + describe(t))
            false
        }
        steps.settle(ACTIVITY_WAIT_MS)
        if (on()) {
            val via = if (activityStarted) "activity" else "wakelock"
            return reportLineResult(via, true, rescueKeyguard(steps, keyguard))
        }

        // Nothing worked. Report it; do not throw.
        return failureLine(steps.keyguardNow())
    }

    /** Dismiss a non-secure keyguard, then re-read the state for the report. */
    private fun rescueKeyguard(steps: WakeSteps, fallback: String): String {
        try {
            steps.dismissKeyguard()
        } catch (t: Throwable) {
            AgentLog.add("wake", "解锁请求失败: " + describe(t))
        }
        val now = try {
            steps.keyguardNow()
        } catch (t: Throwable) {
            fallback
        }
        return if (now.isEmpty()) fallback else now
    }

    /**
     * The injectable framework operations behind [wakeForTesting]. The defaults
     * model a device that is already interactive and has no lockscreen, so a
     * test only has to override what it cares about.
     */
    class WakeSteps(
        val isInteractive: () -> Boolean = { true },
        val acquireWakeLock: () -> Unit = {},
        val settle: (Long) -> Unit = {},
        val dismissKeyguard: () -> Unit = {},
        val keyguardNow: () -> String = { "none" }
    )

    /** `wake already-on scr=… kg=…` / `wake ok via=… scr=… kg=…`. */
    fun reportLine(via: String, screenOn: Boolean, keyguard: String): String {
        val scr = if (screenOn) "on" else "off"
        return if (via == "already-on") {
            // The spec prints this one without the `ok` token.
            "wake already-on scr=$scr kg=$keyguard"
        } else {
            "wake ok via=$via scr=$scr kg=$keyguard"
        }
    }

    /** `wake failed via=none scr=off kg=…`, `exit=4` (SPEC.md section 3.2). */
    fun failureLine(keyguard: String): ExecutionResult =
        ExecutionResult(EXIT_TIMEOUT, "wake failed via=none scr=off kg=$keyguard", "")

    private fun reportLineResult(via: String, screenOn: Boolean, keyguard: String): ExecutionResult =
        ExecutionResult(EXIT_OK, reportLine(via, screenOn, keyguard), "")

    private fun isInteractive(power: PowerManager?): Boolean = try {
        power?.isInteractive ?: false
    } catch (t: Throwable) {
        false
    }

    /**
     * `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` turns the panel on
     * (deprecated since API 17 but still honoured; `WAKE_LOCK` is declared).
     *
     * Released before returning: holding a bright wake lock would keep the screen
     * on far longer than the operator asked for.
     */
    private fun acquireBrightWakeLock(power: PowerManager?) {
        if (power == null) return
        val lock = try {
            @Suppress("DEPRECATION")
            power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                WAKE_LOCK_TAG
            )
        } catch (t: Throwable) {
            return
        }
        try {
            lock.acquire(WAKELOCK_HOLD_MS)
        } catch (t: Throwable) {
            return
        }
        try {
            if (lock.isHeld) lock.release()
        } catch (t: Throwable) {
            // ignore
        }
    }

    /**
     * Start [WakeActivity]. A `SecurityException` or an `ActivityNotFoundException`
     * here means the ROM refused a background activity start — a result to
     * report, never a crash.
     *
     * @return true when the start was accepted (the activity may still be blocked
     *         from turning the screen on before it runs).
     */
    private fun startWakeActivity(context: Context): Boolean {
        return try {
            val intent = Intent(context, WakeActivity::class.java)
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            AgentLog.add("wake", "WakeActivity 被拒绝: " + describe(t))
            false
        }
    }

    /** Wait up to [timeoutMs] for `isInteractive` to become true. */
    private fun waitForInteractive(power: PowerManager?, timeoutMs: Long) {
        val step = if (timeoutMs < POLL_STEP_MS) timeoutMs else POLL_STEP_MS
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isInteractive(power)) return
            try {
                Thread.sleep(step)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /**
     * Dismiss the keyguard only when it is not secure — a secure one can never
     * be cleared silently, and asking would just show the lock screen.
     *
     * A `Service` has no Activity and `KeyguardManager.requestDismissKeyguard`
     * only accepts one, so the actual request is made by [WakeActivity]. This
     * method records the attempt; `kg=` in the result reports the observed state.
     */
    private fun dismissKeyguardIfSafe(context: Context) {
        try {
            val manager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                ?: return
            if (manager.isKeyguardLocked && !manager.isKeyguardSecure) {
                AgentLog.add("wake", "非安全锁屏：由 WakeActivity 请求解锁")
            }
        } catch (t: Throwable) {
            AgentLog.add("wake", "读取锁屏状态失败: " + describe(t))
        }
    }

    /** `none` (no lockscreen), `locked` (non-secure/temporarily locked), `secure`. */
    private fun keyguardState(context: Context): String {
        return try {
            val manager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                ?: return "none"
            if (!manager.isKeyguardLocked) {
                "none"
            } else if (manager.isKeyguardSecure) {
                "secure"
            } else {
                "locked"
            }
        } catch (t: Throwable) {
            "none"
        }
    }
}
