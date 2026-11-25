package net.de5.phoneagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the agent after boot and after our own package is replaced
 * (SPEC.md section 7).
 *
 * v0.6.3-pad12：这里**不再启动服务**。老板拍板「开机不自启，需要人工点启动」，
 * 所以广播只做三件修手机的事——恢复常亮超时、重建无障碍绑定、重新发现 adb 端口。
 * v0.4.2 曾在这三件事前面加了个 auto-start 判断并提前 return，结果一次更新就把
 * 无障碍绑定留成死的，任何 `tree` 都答「accessibility service not connected」。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val action = intent?.action ?: return
        val packageReplaced = when (action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> true
            // The sibling broadcast fires for *every* package on the phone, so it
            // is only ours when the data URI names us. Flyme sends neither this
            // nor MY_PACKAGE_REPLACED; the process-start check (A11yStartup) is
            // what actually covers that ROM.
            BootPlan.ACTION_PACKAGE_REPLACED ->
                intent.data?.schemeSpecificPart == ctx.packageName
            else -> false
        }
        if (!packageReplaced &&
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != ACTION_QUICKBOOT_POWERON
        ) {
            return
        }
        if (packageReplaced && action == BootPlan.ACTION_PACKAGE_REPLACED) {
            // Normalise: BootPlan only needs to know "this is a replacement".
            // Reported separately so a log reader can tell which broadcast arrived.
            AgentLog.add("boot", "收到 PACKAGE_REPLACED（data=" + intent.data + "）")
        }

        val prefs = Prefs(ctx)
        val steps = BootPlan.steps(
            if (packageReplaced) BootPlan.ACTION_MY_PACKAGE_REPLACED else action,
            prefs.stayOn
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
            }
        }
    }

    companion object {
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
