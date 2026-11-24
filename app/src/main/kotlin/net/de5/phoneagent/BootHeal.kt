package net.de5.phoneagent

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Post-boot wireless-debugging self-heal (SPEC.md section 7).
 *
 * A reboot changes the wireless-debugging port and nothing on the device
 * remembers the new one, so without this the operator has to pick the tablet up
 * and open the developer panel before `adb connect` works again. This runs once,
 * a little after boot, and drops the freshly discovered port into the same cache
 * the heartbeat already advertises.
 *
 * Three rules make it safe:
 *
 * - it **only enables** `adb_wifi_enabled` when it reads something other than
 *   `1`; it never bounces a working daemon. Bouncing is `adbheal`'s deliberate
 *   move and it drops a live adb session - boot has no reason to do that;
 * - it never runs on the receiver's thread: `onReceive` has a ~10 s budget while
 *   a discovery can outlast it, so the work goes to its own [HandlerThread] and
 *   the receiver returns immediately;
 * - one heal per process ([started]), because `BOOT_COMPLETED` and
 *   `MY_PACKAGE_REPLACED` can both arrive and the second one would only race the
 *   first.
 */
internal object BootHeal {

    /** Wi-Fi is not up the instant `BOOT_COMPLETED` fires; wait for it. */
    const val DELAY_MS = 20_000L

    /** Whole discovery budget: nobody is waiting on this, so it can be generous. */
    const val BUDGET_MS = 20_000L

    /** Re-entry gate; cleared when the single scheduled run finishes. */
    private val started = AtomicBoolean(false)

    /** Schedule the one post-boot heal. Returns immediately, never throws. */
    fun schedule(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        try {
            val thread = HandlerThread("boot-adbheal")
            thread.start()
            Handler(thread.looper).postDelayed({
                try {
                    val outcome = AdbWifi.refresh(app, enableIfNeeded = true, budgetMs = BUDGET_MS)
                    AgentLog.add("adb", "开机自愈: " + outcome)
                } catch (t: Throwable) {
                    AgentLog.add("adb", Admin.ERROR_PREFIX + "开机自愈异常: " + describe(t))
                } finally {
                    thread.quitSafely()
                    started.set(false)
                }
            }, DELAY_MS)
        } catch (t: Throwable) {
            // A phone that cannot start a thread has bigger problems; the boot
            // path must still finish so the service gets its chance.
            started.set(false)
            AgentLog.add("adb", Admin.ERROR_PREFIX + "开机自愈未能启动: " + describe(t))
        }
    }
}
