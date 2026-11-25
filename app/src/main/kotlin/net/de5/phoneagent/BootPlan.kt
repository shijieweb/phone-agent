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
 * The rule: a broadcast only **repairs the phone**. Restoring the panel timeout,
 * rebuilding accessibility and rediscovering the adb port are fixes to the phone
 * itself; "stop" means "do not run the relay poller", not "leave the phone broken
 * after an update".
 *
 * v0.6.3-pad12 删掉了最后一步 StartService：老板拍板「开机不自启，需要人工点
 * 启动」。所以这里连 autoStart/configured 两个参数都不再需要——没有任何一步会
 * 因为这两个值出现或消失，留着参数只会让人以为还有一条隐藏的自启路径。
 */
internal object BootPlan {

    /** As a plain string, so this file needs no Intent. */
    const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"

    /**
     * The sibling broadcast. `MY_PACKAGE_REPLACED` is the polite one — sent only
     * to the replaced package — while `PACKAGE_REPLACED` is broadcast for every
     * package and needs a `data` scheme filter. ROMs are inconsistent about
     * which of the two they deliver, so both are declared. Flyme delivered
     * neither for v0.4.3 (see [A11yStartup], which does not depend on a
     * broadcast at all).
     */
    const val ACTION_PACKAGE_REPLACED = "android.intent.action.PACKAGE_REPLACED"

    sealed class Step {
        /** Re-apply the "keep the panel on" timeout the ROM just rewrote. */
        object RestoreStayOn : Step()

        /** Re-append our accessibility entry after an update dropped the binding. */
        object RebuildAccessibility : Step()

        /** Rediscover the wireless-debugging port; it changes on every boot. */
        object DiscoverAdbPort : Step()
    }

    fun steps(action: String, stayOn: Boolean): List<Step> {
        val steps = ArrayList<Step>(3)
        if (stayOn) steps.add(Step.RestoreStayOn)
        // Only after a replacement, never after a plain boot: on boot Android
        // restores the entry itself, and writing it then would silently override
        // an operator who turned accessibility off on purpose.
        if (action == ACTION_MY_PACKAGE_REPLACED || action == ACTION_PACKAGE_REPLACED) {
            steps.add(Step.RebuildAccessibility)
        }
        steps.add(Step.DiscoverAdbPort)
        return steps
    }
}
