package net.de5.phoneagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the agent after boot and after our own package is replaced
 * (SPEC.md section 7).
 *
 * Only the service start is gated on auto-start. The other repairs — the panel
 * timeout, the accessibility binding, the adb port — are fixes to the phone, not
 * to the relay, so they run regardless of whether the operator pressed 停止.
 * v0.4.2 returned early on that check and silently skipped the accessibility
 * rebuild, which is how an update left the phone unable to answer any `tree`.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != ACTION_QUICKBOOT_POWERON
        ) {
            return
        }

        val prefs = Prefs(ctx)
        val steps = BootPlan.steps(
            action,
            prefs.stayOn,
            prefs.autoStart,
            prefs.isConfigured()
        )

        for (step in steps) {
            when (step) {
                is BootPlan.Step.RestoreStayOn -> {
                    // The ROM rewrites screen_off_timeout on every boot, and the
                    // reason to set it (a phone that must answer at 3 a.m.) has
                    // nothing to do with whether the relay is configured.
                    AgentLog.add("boot", "常亮恢复: " + ScreenOps.reapplyStayOn(ctx))
                }
                is BootPlan.Step.RebuildAccessibility -> {
                    // Installing a new APK drops the binding: the setting
                    // survives, the binding does not, and every tree/click then
                    // answers "accessibility service not connected". Android
                    // rewrites the setting a few seconds after this broadcast, so
                    // the rebuild is retried at 3 s / 10 s / 30 s on its own
                    // thread (A11yHeal).
                    AgentLog.add("boot", "收到换包广播，安排无障碍重建")
                    A11yHeal.schedule(ctx)
                }
                is BootPlan.Step.DiscoverAdbPort -> {
                    // The port is new after every boot and nothing stores it; the
                    // discovery itself runs ~20 s later, once Wi-Fi is up.
                    BootHeal.schedule(ctx)
                }
                is BootPlan.Step.StartService -> {
                    try {
                        ctx.startForegroundService(Intent(ctx, RcAgentService::class.java))
                    } catch (t: Throwable) {
                        // Background start restrictions: nothing to retry here.
                        AgentLog.add("boot", "启动服务被拒: " + describe(t))
                    }
                }
            }
        }
    }

    companion object {
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
