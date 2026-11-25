package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boot/update broadcast order (SPEC.md §7).
 *
 * Two regressions are pinned here:
 * - v0.4.2 returned early when auto-start was off, so an operator who had pressed
 *   停止 got an update with a dead accessibility binding and no rebuild — the
 *   phone then answered every command with "accessibility service not connected".
 * - v0.6.3-pad12 removed the service start from this list entirely: the owner
 *   decided the phone must never start the agent by itself (开机不自启，需要人工点
 *   启动). So a broadcast may only *repair* the phone.
 */
class BootPlanTest {

    @Test
    fun aReplacementRebuildsTheAccessibilityBinding() {
        val steps = BootPlan.steps(BootPlan.ACTION_MY_PACKAGE_REPLACED, stayOn = false)
        assertTrue(steps.toString(), steps.contains(BootPlan.Step.RebuildAccessibility))
    }

    @Test
    fun theSiblingPackageReplacedBroadcastAlsoRebuilds() {
        // MY_PACKAGE_REPLACED and PACKAGE_REPLACED are delivered inconsistently
        // across ROMs; either one must lead to a rebuild.
        assertTrue(
            BootPlan.steps(BootPlan.ACTION_PACKAGE_REPLACED, stayOn = false)
                .contains(BootPlan.Step.RebuildAccessibility)
        )
    }

    @Test
    fun aPlainBootDoesNotRewriteTheAccessibilitySetting() {
        // On boot Android restores the entry itself; writing it then would
        // silently override an operator who turned accessibility off on purpose.
        val steps = BootPlan.steps("android.intent.action.BOOT_COMPLETED", stayOn = false)
        assertFalse(steps.toString(), steps.contains(BootPlan.Step.RebuildAccessibility))
    }

    @Test
    fun thePanelTimeoutIsRestoredOnlyWhenStayOnIsSet() {
        val on = BootPlan.steps("android.intent.action.BOOT_COMPLETED", stayOn = true)
        assertTrue(on.toString(), on.contains(BootPlan.Step.RestoreStayOn))
        val off = BootPlan.steps("android.intent.action.BOOT_COMPLETED", stayOn = false)
        assertFalse(off.toString(), off.contains(BootPlan.Step.RestoreStayOn))
    }

    @Test
    fun theAdbPortIsRediscoveredOnEveryBroadcast() {
        for (action in listOf(
            BootPlan.ACTION_MY_PACKAGE_REPLACED,
            BootPlan.ACTION_PACKAGE_REPLACED,
            "android.intent.action.BOOT_COMPLETED"
        )) {
            for (stayOn in listOf(true, false)) {
                val steps = BootPlan.steps(action, stayOn)
                assertTrue(action + " stayOn=" + stayOn, steps.contains(BootPlan.Step.DiscoverAdbPort))
            }
        }
    }

    @Test
    fun aPlainBootOnlyRediscoversTheAdbPort() {
        // 最强的「开机不自启」断言：一次普通开机**只**做端口发现这一件事。
        // 以前这里还有 StartService；老板拍板去掉之后，任何一步溜回来都会红。
        assertEquals(
            listOf(BootPlan.Step.DiscoverAdbPort),
            BootPlan.steps("android.intent.action.BOOT_COMPLETED", stayOn = false)
        )
    }

    @Test
    fun noBroadcastEverProducesMoreThanThreeSteps() {
        // Step 只剩三支（常亮/无障碍/端口）。多出第四支说明有人又把「起服务」塞回来了。
        val known = setOf(
            BootPlan.Step.RestoreStayOn,
            BootPlan.Step.RebuildAccessibility,
            BootPlan.Step.DiscoverAdbPort
        )
        for (action in listOf(
            BootPlan.ACTION_MY_PACKAGE_REPLACED,
            BootPlan.ACTION_PACKAGE_REPLACED,
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.SOMETHING_ELSE"
        )) {
            for (stayOn in listOf(true, false)) {
                val steps = BootPlan.steps(action, stayOn)
                assertTrue(action + " -> " + steps, steps.size <= 3)
                assertTrue(action + " -> " + steps, known.containsAll(steps))
            }
        }
    }

    @Test
    fun theRepairOrderPutsTheRebuildBeforeThePortDiscovery() {
        val steps = BootPlan.steps(BootPlan.ACTION_MY_PACKAGE_REPLACED, stayOn = true)
        assertEquals(
            listOf(
                BootPlan.Step.RestoreStayOn,
                BootPlan.Step.RebuildAccessibility,
                BootPlan.Step.DiscoverAdbPort
            ),
            steps
        )
    }

    @Test
    fun anUnknownActionStillRediscoversThePort() {
        // 兜底：不认识的 action 不该抛异常，也不该做多余的事。
        assertEquals(
            listOf(BootPlan.Step.DiscoverAdbPort),
            BootPlan.steps("android.intent.action.SOMETHING_ELSE", stayOn = false)
        )
    }
}
