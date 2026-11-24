package net.de5.phoneagent

/**
 * What a boot / package-replacement broadcast should do, in order (SPEC.md §7).
 *
 * Pure so the ordering can be asserted without a BroadcastReceiver. The order is
 * the whole bug this file exists for: v0.4.2 put the accessibility rebuild
 * *after* the "should the agent start?" check, so an operator who had pressed
 * 停止 (which clears auto-start) never got a rebuild — the update installed, the
 * binding was dead, and nothing said so.
 *
 * The rule: only the **service start** is gated on auto-start. Restoring the
 * panel timeout, rebuilding accessibility and rediscovering the adb port are
 * repairs of the phone itself; "stop" means "do not run the relay poller", not
 * "leave the phone broken after an update".
 */
internal object BootPlan {

    /** As a plain string, so this file needs no Intent. */
    const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"

    sealed class Step {
        /** Re-apply the "keep the panel on" timeout the ROM just rewrote. */
        object RestoreStayOn : Step()

        /** Re-append our accessibility entry after an update dropped the binding. */
        object RebuildAccessibility : Step()

        /** Rediscover the wireless-debugging port; it changes on every boot. */
        object DiscoverAdbPort : Step()

        /** Start the relay poller — the only step auto-start gates. */
        object StartService : Step()
    }

    fun steps(
        action: String,
        stayOn: Boolean,
        autoStart: Boolean,
        configured: Boolean
    ): List<Step> {
        val steps = ArrayList<Step>(4)
        if (stayOn) steps.add(Step.RestoreStayOn)
        // Only after a replacement, never after a plain boot: on boot Android
        // restores the entry itself, and writing it then would silently override
        // an operator who turned accessibility off on purpose.
        if (action == ACTION_MY_PACKAGE_REPLACED) steps.add(Step.RebuildAccessibility)
        steps.add(Step.DiscoverAdbPort)
        if (StartPolicy.shouldAutoStart(autoStart, configured)) steps.add(Step.StartService)
        return steps
    }
}
