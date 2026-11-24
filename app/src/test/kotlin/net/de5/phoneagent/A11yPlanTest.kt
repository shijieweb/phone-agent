package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `a11y status` (SPEC.md §3.15): the three facts that fail independently, and
 * the one repair that fits each of them.
 */
class A11yPlanTest {

    @Test
    fun aHealthyPhoneSaysSoWithoutAdvice() {
        // Printing a repair next to "正常" invites someone to run it.
        assertEquals("正常", A11yPlan.verdict(true, true, true))
    }

    @Test
    fun aMissingEntryNamesTheAdbLine() {
        val text = A11yPlan.verdict(true, false, true)
        assertTrue(text, text.startsWith("条目缺失"))
        assertTrue(text, text.contains(A11yPlan.ADB_LINE))
    }

    @Test
    fun aMissingEntryOutranksADeadBinding() {
        // When the entry is gone, "toggle the component" cannot work: there is
        // nothing to toggle.
        assertTrue(A11yPlan.verdict(false, false, false).startsWith("条目缺失"))
    }

    @Test
    fun aClosedMasterSwitchIsItsOwnVerdict() {
        assertEquals("总开关被关闭，执行 a11y heal", A11yPlan.verdict(false, true, true))
    }

    @Test
    fun aListedEntryWithNoBindingAsksForAReconnect() {
        assertEquals(
            "条目在但服务未绑定，执行 a11y heal 强制重连",
            A11yPlan.verdict(true, true, false)
        )
    }

    @Test
    fun theReportPrintsAllThreeFacts() {
        val text = A11yPlan.report(true, true, false)
        assertTrue(text, text.contains("无障碍总开关: 开"))
        assertTrue(text, text.contains("无障碍条目: 已列出"))
        assertTrue(text, text.contains("服务实例: 未连接"))
        assertTrue(text, text.contains("结论: 条目在但服务未绑定"))
    }

    @Test
    fun theOffStateReadsAsOff() {
        val text = A11yPlan.report(false, false, false)
        assertTrue(text, text.contains("无障碍总开关: 关"))
        assertTrue(text, text.contains("无障碍条目: 缺失"))
    }

    @Test
    fun theAdbLineTargetsTheRealComponent() {
        assertEquals(
            "adb shell settings put secure enabled_accessibility_services " +
                "net.de5.phoneagent/net.de5.phoneagent.PhoneAccessibilityService",
            A11yPlan.ADB_LINE
        )
    }
}
