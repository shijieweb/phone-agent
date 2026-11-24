package net.de5.phoneagent

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The accessibility repairs (SPEC.md section 7).
 *
 * Installing a new APK makes Android drop our accessibility entry: the setting
 * survives, the binding does not, and the phone then answers every `tree`,
 * `click` and `confirm` with "accessibility service not connected" — the exact
 * failure that broke the remote install chain on the Meizu.
 *
 * Two entry points, because the platform only guarantees one of them:
 *
 *  - [checkNow] — runs the moment our own process starts (the foreground service
 *    and the settings Activity both call it). A process start cannot be skipped:
 *    without the process the agent does not work at all.
 *  - [schedule] — the `ACTION_MY_PACKAGE_REPLACED` / `PACKAGE_REPLACED` path.
 *    Kept for ROMs that do deliver it, but **not relied upon**: Flyme never sent
 *    it (v0.4.3 shipped with zero `[boot]` lines on a clean reinstall).
 *
 * The system rewrites the secure setting a few seconds *after* a replacement, so
 * one attempt at broadcast time is not enough; [schedule] therefore retries at
 * 3 s / 10 s / 30 s. [checkNow] does not retry — the heartbeat covers the rest.
 *
 * Both run on their own daemon thread: [android.content.BroadcastReceiver.onReceive]
 * and [android.app.Service.onCreate] must return within seconds or the system
 * kills the app.
 */
internal object A11yHeal {

    /** Cumulative delays from the broadcast, in order. */
    val DELAYS_MS = longArrayOf(3_000L, 10_000L, 30_000L)

    /** Marks a line as produced by the process-start check (`a11y status` labels it 启动). */
    const val SOURCE_STARTUP = "启动自检"

    /** Marks a line as produced by the broadcast path (`a11y status` labels it 装包). */
    const val SOURCE_INSTALL = "安装后自愈"

    /**
     * One repair at a time. The service and the Activity can start together after
     * an update, and the rescue sequence deliberately sleeps ~2 s between
     * releasing and re-adding the component — two of those interleaved would
     * fight over the same secure settings and could leave the entry deleted.
     */
    private val busy = AtomicBoolean(false)

    /**
     * Repair the binding right now, on a background thread.
     *
     * Called from [RcAgentService.onCreate] and [MainActivity.onCreate]. Cheap on
     * a healthy phone: it reads two settings and logs one line, so
     * `a11y status` can always prove the check ran.
     */
    fun checkNow(context: Context) {
        val app = context.applicationContext ?: context
        val thread = Thread {
            try {
                val live = PhoneAccessibilityService.instance != null
                val listed = Admin.isAccessibilityListed(app)
                val plan = A11yStartup.plan(live, listed, Admin.canWriteSecure(app))
                val outcome = when (plan) {
                    is A11yStartup.Plan.Nothing -> "无障碍已连接，无需修复"
                    is A11yStartup.Plan.SkipNoPermission ->
                        "跳过，缺 WRITE_SECURE_SETTINGS（instance=" + live +
                            " listed=" + listed + "）"
                    else -> repairExclusively(app, listed, live)
                }
                AgentLog.add("a11y", SOURCE_STARTUP + ": " + outcome)
            } catch (t: Throwable) {
                AgentLog.add("a11y", SOURCE_STARTUP + "异常: " + describe(t))
            }
        }
        thread.isDaemon = true
        thread.name = "rc-a11y-startup"
        try {
            thread.start()
        } catch (t: Throwable) {
            AgentLog.add("a11y", SOURCE_STARTUP + "线程启动失败: " + describe(t))
        }
    }

    fun schedule(context: Context) {
        val app = context.applicationContext ?: context
        if (!Admin.canWriteSecure(app)) {
            // Silent by design: a phone without the grant is not broken, and a
            // notification about it on every update would be noise.
            AgentLog.add("a11y", SOURCE_INSTALL + ": 跳过，缺 WRITE_SECURE_SETTINGS")
            return
        }
        val thread = Thread {
            var waited = 0L
            for (delay in DELAYS_MS) {
                try {
                    Thread.sleep(delay - waited)
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
                waited = delay
                val live = PhoneAccessibilityService.instance != null
                val listed = Admin.isAccessibilityListed(app)
                if (live && listed) {
                    AgentLog.add("a11y", SOURCE_INSTALL + ": " + delay + "ms 已就绪，不再重试")
                    return@Thread
                }
                // "The entry is there but nothing is bound" is the state an update
                // actually leaves behind, and appending an entry that already
                // exists does nothing. Pick the repair from the state instead.
                val outcome = repairExclusively(app, listed, live)
                AgentLog.add("a11y", SOURCE_INSTALL + "(" + delay + "ms): " + outcome)
            }
        }
        thread.isDaemon = true
        thread.name = "rc-a11y-heal"
        try {
            thread.start()
        } catch (t: Throwable) {
            AgentLog.add("a11y", SOURCE_INSTALL + "线程启动失败: " + describe(t))
        }
    }

    /** [Admin.repairAccessibility] behind the single-repair lock. */
    private fun repairExclusively(app: Context, listed: Boolean, live: Boolean): String {
        if (!busy.compareAndSet(false, true)) return "跳过，另一次修复正在进行"
        try {
            return Admin.repairAccessibility(app, listed, live)
        } finally {
            busy.set(false)
        }
    }
}
