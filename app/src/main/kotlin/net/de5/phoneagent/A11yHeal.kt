package net.de5.phoneagent

import android.content.Context

/**
 * The package-replacement accessibility rebuild (SPEC.md section 7).
 *
 * Installing a new APK makes Android drop our accessibility entry: the setting
 * survives, the binding does not, and the phone then answers every `tree`,
 * `click` and `confirm` with "accessibility service not connected" — the exact
 * failure that broke the remote install chain on the Meizu.
 *
 * The system rewrites the secure setting a few seconds *after* the broadcast, so
 * one attempt at broadcast time is not enough. Three attempts at 3 s / 10 s / 30 s
 * cover the window without touching an AlarmManager (which would need its own
 * permission and would survive a process death we do not want to survive: if the
 * process is gone, the foreground service start failed too and nothing works).
 *
 * Runs on its own daemon thread: [android.content.BroadcastReceiver.onReceive]
 * must return within seconds or the system kills the app.
 */
internal object A11yHeal {

    /** Cumulative delays from the broadcast, in order. */
    val DELAYS_MS = longArrayOf(3_000L, 10_000L, 30_000L)

    fun schedule(context: Context) {
        val app = context.applicationContext ?: context
        if (!Admin.canWriteSecure(app)) {
            // Silent by design: a phone without the grant is not broken, and a
            // notification about it on every update would be noise.
            AgentLog.add("a11y", "跳过安装后自愈：缺 WRITE_SECURE_SETTINGS")
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
                if (PhoneAccessibilityService.instance != null && Admin.isAccessibilityListed(app)) {
                    AgentLog.add("a11y", "安装后自愈: " + delay + "ms 已就绪，不再重试")
                    return@Thread
                }
                val outcome = Admin.enableAccessibilitySelfHeal(app)
                AgentLog.add("a11y", "安装后自愈(" + delay + "ms): " + outcome)
            }
        }
        thread.isDaemon = true
        thread.name = "rc-a11y-heal"
        try {
            thread.start()
        } catch (t: Throwable) {
            AgentLog.add("a11y", "安装后自愈线程启动失败: " + describe(t))
        }
    }
}
