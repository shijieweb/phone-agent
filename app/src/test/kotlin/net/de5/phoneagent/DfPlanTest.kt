package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `files df` (SPEC.md §3.7): the sentence an operator compares against the
 * settings screen, so the rounding is pinned here.
 */
class DfPlanTest {

    @Test
    fun theReportIsOneLineWithAllThreeNumbers() {
        assertEquals(
            "总 2.0 KB 已用 1.0 KB 可用 1.0 KB (50%)",
            DfPlan.report(2048L, 1024L, 1024L)
        )
    }

    @Test
    fun percentRoundsToTheNearestWholeNumber() {
        assertEquals(50, DfPlan.percent(50L, 100L))
        assertEquals(33, DfPlan.percent(1L, 3L))
        assertEquals(67, DfPlan.percent(2L, 3L))
        assertEquals(100, DfPlan.percent(100L, 100L))
    }

    @Test
    fun anEmptyOrUnknownVolumeIsZeroPercentNotNaN() {
        assertEquals(0, DfPlan.percent(0L, 100L))
        assertEquals(0, DfPlan.percent(10L, 0L))
        assertEquals(0, DfPlan.percent(10L, -5L))
    }

    @Test
    fun negativeInputsCannotProduceANegativeSize() {
        val text = DfPlan.report(-1L, -1L, -1L)
        assertTrue(text, text.startsWith("总 0 B 已用 0 B 可用 0 B"))
    }

    @Test
    fun aFailureKeepsTheErrorPrefixSoTheExecutorCanMapIt() {
        assertTrue(DfPlan.failure("boom").startsWith(Admin.ERROR_PREFIX))
        assertTrue(DfPlan.failure("boom").contains("读取容量失败: boom"))
    }
}
