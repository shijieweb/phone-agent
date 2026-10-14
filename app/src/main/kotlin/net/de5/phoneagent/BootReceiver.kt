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

        val prefs = Prefs(ctx)
        if (!prefs.autoStart) return
        if (!prefs.isConfigured()) return

        try {
            ctx.startForegroundService(Intent(ctx, RcAgentService::class.java))
        } catch (t: Throwable) {
            // Background start restrictions: there is nothing to retry here.
        }
    }

    companion object {
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
