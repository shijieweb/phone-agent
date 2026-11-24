package net.de5.phoneagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the agent after boot and after our own package is replaced
 * (SPEC.md section 7). Only ever starts the service when it is configured and
 * auto-start is on, so a factory-fresh install stays quiet.
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

        // One pure rule decides this, shared with the start button so the two can
        // never disagree (SPEC.md section 7).
        val prefs = Prefs(ctx)

        // "Keep the panel on" is re-applied *before* the start check and with no
        // relay involved: the ROM rewrites screen_off_timeout on every boot, and
        // the operator's reason for setting it (a phone that must answer at 3
        // a.m.) has nothing to do with whether the relay is configured yet.
        if (prefs.stayOn) {
            val result = ScreenOps.reapplyStayOn(ctx)
            AgentLog.add("boot", "常亮恢复: " + result)
        }

        if (!StartPolicy.shouldAutoStart(prefs.autoStart, prefs.isConfigured())) return

        try {
            ctx.startForegroundService(Intent(ctx, RcAgentService::class.java))
        } catch (t: Throwable) {
            // Background start restrictions: there is nothing to retry here.
        }

        // The wireless-debugging port is new after every boot and nothing stores
        // it, so rediscover it in the background (SPEC.md section 3.4). This
        // returns immediately; the discovery itself runs ~20 s later, once Wi-Fi
        // is actually up.
        BootHeal.schedule(ctx)
    }

    companion object {
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
