package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `screen` is a *sticky* setting written into a system table the ROM rewrites on
 * every boot, so the value it writes and the sentence it reports are both worth
 * proving without a phone (SPEC.md §3.14).
 */
class ScreenPlanTest {

    @Test
    fun theStayOnSentinelIsTheLargestTimeoutAnIntCanHold() {
        // Settings.System stores an int; anything larger wraps to a negative
        // number and the panel would switch off instantly.
        assertEquals(Int.MAX_VALUE, ScreenPlan.STAY_ON_TIMEOUT)
        assertEquals("2147483647", ScreenPlan.stayOnValue())
    }

    @Test
    fun aShorterTimeoutThanFifteenSecondsIsRefusedByClamping() {
        assertEquals(15_000, ScreenPlan.clampTimeout(0L))
        assertEquals(15_000, ScreenPlan.clampTimeout(-5L))
        assertEquals(15_000, ScreenPlan.clampTimeout(14_999L))
        assertEquals(15_001, ScreenPlan.clampTimeout(15_001L))
    }

    @Test
    fun anOversizedTimeoutSaturatesInsteadOfWrappingNegative() {
        assertEquals(Int.MAX_VALUE, ScreenPlan.clampTimeout(Int.MAX_VALUE.toLong()))
        assertEquals(Int.MAX_VALUE, ScreenPlan.clampTimeout(9_999_999_999L))
    }

    @Test
    fun theRestoreValueIsTheAndroidDefaultOfOneMinute() {
        assertEquals(60_000, ScreenPlan.RESTORE_TIMEOUT)
        assertEquals("60s", ScreenPlan.timeoutText(ScreenPlan.RESTORE_TIMEOUT.toLong()))
    }

    @Test
    fun aTimeoutIsRenderedInTheUnitThatReadsBest() {
        assertEquals("永不", ScreenPlan.timeoutText(ScreenPlan.STAY_ON_TIMEOUT.toLong()))
        assertEquals("60s", ScreenPlan.timeoutText(60_000L))
        assertEquals("30s", ScreenPlan.timeoutText(30_000L))
        assertEquals("15500ms", ScreenPlan.timeoutText(15_500L))
        assertEquals("未知", ScreenPlan.timeoutText(null))
        assertEquals("未知", ScreenPlan.timeoutText(-1L))
    }

    @Test
    fun theReportLineCarriesBothTheDecisionAndTheLiveValue() {
        assertEquals(
            "常亮: 开 当前超时=永不",
            ScreenPlan.stayOnLine(true, ScreenPlan.STAY_ON_TIMEOUT.toLong())
        )
        assertEquals("常亮: 关 当前超时=60s", ScreenPlan.stayOnLine(false, 60_000L))
        // A ROM that refuses the write must not make the report pretend otherwise.
        assertEquals("常亮: 关 当前超时=未知", ScreenPlan.stayOnLine(false, null))
    }

    @Test
    fun theAppliedLinesNameTheValueThatWasWritten() {
        assertEquals("已开启常亮（屏幕超时=永不）", ScreenPlan.stayOnApplied(true))
        assertEquals("已关闭常亮（屏幕超时=60s）", ScreenPlan.stayOnApplied(false))
        assertEquals("已设置屏幕超时=60s", ScreenPlan.timeoutApplied(60_000))
    }

    @Test
    fun aRefusedWakeIsReportedAsSuchRatherThanAsSuccess() {
        // The ROM may accept the intent and still keep the panel dark; the
        // sentence has to say so, because the operator is looking at the phone.
        assertTrue(ScreenPlan.turnedOn(true).contains("已点亮"))
        assertTrue(ScreenPlan.turnedOn(false).contains("仍是熄的"))
        assertTrue(ScreenPlan.failure("x").startsWith("屏幕控制失败: "))
    }

    @Test
    fun theFloorIsFifteenSecondsAndNothingShorter() {
        assertEquals(15_000, ScreenPlan.TIMEOUT_MIN)
        assertEquals(15_000, ScreenPlan.clampTimeout(ScreenPlan.TIMEOUT_MIN.toLong()))
        assertEquals("15s", ScreenPlan.timeoutText(15_000L))
    }

    @Test
    fun aSubSecondTimeoutKeepsItsMillisecondUnit() {
        // 999 ms is absurd but must still round-trip readably instead of
        // collapsing to "0s", which would look like the write failed.
        assertEquals("999ms", ScreenPlan.timeoutText(999L))
        assertEquals("0s", ScreenPlan.timeoutText(0L))
    }

    @Test
    fun everyUsageErrorNamesEveryAcceptedForm() {
        val usage = ScreenPlan.USAGE
        assertTrue(usage.contains("stayon"))
        assertTrue(usage.contains("timeout"))
        assertTrue(usage.contains("on"))
        assertTrue(usage.contains("off"))
    }
}
