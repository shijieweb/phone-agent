package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `wm` (v0.8.0-pad12, SPEC.md §3.26).
 *
 * Two things are load-bearing here: a changed display setting must always carry
 * a deadline (so the phone cannot be left in an unusable resolution), and the
 * "original" value must come from the Physical line, never the Override one.
 */
class WmPlanTest {

    @Test
    fun noArgumentsReadsTheCurrentValues() {
        val request = WmPlan.parse("")!!
        assertTrue(request.read)
        assertEquals(false, request.apply)
        assertEquals(WmPlan.SECONDS_DEFAULT, request.seconds)
    }

    @Test
    fun aSizeChangeDefaultsToFiveMinutesAndNeedsApplyToBeReal() {
        val dry = WmPlan.parse("size 900x1600")!!
        assertEquals(WmPlan.KIND_SIZE, dry.kind)
        assertEquals("900x1600", dry.value)
        assertEquals(300, dry.seconds)
        assertEquals(false, dry.apply)

        val real = WmPlan.parse("size 900x1600 apply")!!
        assertEquals(true, real.apply)
        assertEquals("wm size 900x1600", WmPlan.command(real))
    }

    @Test
    fun anExplicitDeadlineIsCarriedThrough() {
        val request = WmPlan.parse("size 900x1600 120 apply")!!
        assertEquals(120, request.seconds)
        assertEquals("size 900x1600 120 apply", WmPlan.normalize(request))
        assertEquals("wm size 900x1600", WmPlan.command(request))
    }

    @Test
    fun theDeadlineBoundsAreEnforcedNotClamped() {
        assertEquals(30, WmPlan.parse("density 400 30 apply")!!.seconds)
        assertEquals(3600, WmPlan.parse("density 400 3600 apply")!!.seconds)
        assertNull(WmPlan.parse("density 400 29 apply"))
        assertNull(WmPlan.parse("density 400 3601 apply"))
        assertNull(WmPlan.parse("density 400 abc apply"))
    }

    @Test
    fun resetTakesNoDeadlineBecauseItIsImmediate() {
        val reset = WmPlan.parse("size reset apply")!!
        assertEquals(WmPlan.RESET, reset.value)
        assertEquals("wm size reset", WmPlan.command(reset))
        assertEquals("size reset apply", WmPlan.normalize(reset))
        assertNull(WmPlan.parse("size reset 120 apply"))
    }

    @Test
    fun outOfRangeOrMalformedValuesAreRefused() {
        assertNull(WmPlan.parse("size 100x1600 apply"))
        assertNull(WmPlan.parse("size 900x6000 apply"))
        assertNull(WmPlan.parse("size 900 apply"))
        assertNull(WmPlan.parse("size big apply"))
        assertNull(WmPlan.parse("density 50 apply"))
        assertNull(WmPlan.parse("density 1001 apply"))
        assertNull(WmPlan.parse("density wide apply"))
        assertNull(WmPlan.parse("brightness 50 apply"))
        assertNull(WmPlan.parse("size 900x1600 120 60 apply"))
    }

    @Test
    fun theOriginalSizePrefersThePhysicalLine() {
        // 保险丝的基准值必须是 reset 之后会回到的那个值，所以这里优先 Physical。
        val text = "Physical size: 1200x2670\nOverride size: 900x1600\n"
        assertEquals("1200x2670", WmPlan.originalSize(text))
        assertEquals("1200x2670", WmPlan.originalSize("Physical size: 1200x2670"))
        assertEquals("900x1600", WmPlan.originalSize("Override size: 900x1600"))
        assertNull(WmPlan.originalSize("nothing here"))
        assertNull(WmPlan.originalSize("Physical size:"))
    }

    @Test
    fun theOriginalDensityPrefersThePhysicalLine() {
        assertEquals("520", WmPlan.originalDensity("Physical density: 520"))
        assertEquals("520", WmPlan.originalDensity("Physical density: 520\nOverride density: 400"))
        assertEquals("400", WmPlan.originalDensity("Override density: 400"))
        assertNull(WmPlan.originalDensity("Physical density:"))
    }

    @Test
    fun theCurrentSizePrefersTheOverrideLine() {
        // v0.8.2 P3：读命令要报当前生效值，改过之后设备真的在用 Override。
        val text = "Physical size: 1200x2670\nOverride size: 900x1600\n"
        assertEquals("900x1600", WmPlan.currentSize(text))
        assertEquals("1200x2670", WmPlan.currentSize("Physical size: 1200x2670"))
        assertEquals("900x1600", WmPlan.currentSize("Override size: 900x1600"))
        assertNull(WmPlan.currentSize("nothing here"))
        assertNull(WmPlan.currentSize("Override size:"))
    }

    @Test
    fun theCurrentDensityPrefersTheOverrideLine() {
        val text = "Physical density: 520\nOverride density: 400\n"
        assertEquals("400", WmPlan.currentDensity(text))
        assertEquals("520", WmPlan.currentDensity("Physical density: 520"))
        assertNull(WmPlan.currentDensity("nothing here"))
    }

    @Test
    fun theReadLineKeepsTheOldShapeWhenNothingIsOverridden() {
        // 老格式逐字节不变，既有脚本的解析不许断。
        assertEquals("wm size 1200x2670 density 520", WmPlan.readLine("1200x2670", "520"))
        assertEquals(
            "wm size 1200x2670 density 520",
            WmPlan.readLine("1200x2670", "520", "1200x2670", "520"),
        )
        assertEquals("wm size", WmPlan.SIZE_READ)
        assertEquals("wm density", WmPlan.DENSITY_READ)
        assertEquals("wm size reset", WmPlan.SIZE_RESET)
        assertEquals("wm density reset", WmPlan.DENSITY_RESET)
    }

    @Test
    fun theReadLineSaysSoWhenTheOverrideIsInCharge() {
        // 真机 P3：改了分辨率之后 wm 仍报 1200x2670，看着像没改成功。
        // 有 Override 时当前值必须是主值，物理值只能作为注解出现。
        val line = WmPlan.readLine("900x1600", "520", "1200x2670", "520")
        assertEquals("wm size 900x1600 density 520 (override; physical 1200x2670/520)", line)
        assertTrue(line.startsWith("wm size 900x1600"))
        // 物理值仍在，但不再冒充主值。
        assertTrue(line.contains("physical 1200x2670/520"))

        // 只改了密度也一样要说。
        assertEquals(
            "wm size 1200x2670 density 400 (override; physical 1200x2670/520)",
            WmPlan.readLine("1200x2670", "400", "1200x2670", "520"),
        )
        // 物理值读不到就别编：用 ? 占位。
        assertEquals(
            "wm size 900x1600 density 520 (override; physical 1200x2670/?)",
            WmPlan.readLine("900x1600", "520", "1200x2670", null),
        )
    }

    @Test
    fun theOriginalLineOnlyMentionsWhatWasActuallyRead() {
        assertEquals("size=1200x2670 density=520", WmPlan.originalLine("1200x2670", "520"))
        assertEquals("size=1200x2670", WmPlan.originalLine("1200x2670", ""))
        assertEquals("density=520", WmPlan.originalLine("", "520"))
        assertEquals("", WmPlan.originalLine("", ""))
    }

    @Test
    fun theFuseBaselineStaysPhysicalWhileTheReadShowsTheOverride() {
        // 修 P3 时最容易顺手把保险丝的基准值也改成 Override —— 那就再也回不去了。
        val sizeText = "Physical size: 1200x2670\nOverride size: 900x1600\n"
        val densityText = "Physical density: 520\nOverride density: 400\n"
        assertEquals(
            "size=1200x2670 density=520",
            WmPlan.originalLine(
                WmPlan.originalSize(sizeText).orEmpty(),
                WmPlan.originalDensity(densityText).orEmpty(),
            ),
        )
        // 同一个输入，读命令看到的是另一套值。
        assertEquals("900x1600", WmPlan.currentSize(sizeText))
        assertEquals("400", WmPlan.currentDensity(densityText))
    }

    @Test
    fun theDeadlineIsNowPlusTheSeconds() {
        assertEquals(1_000_000L + 300_000L, WmPlan.deadlineOf(1_000_000L, 300))
        assertEquals(1_000_000L + 30_000L, WmPlan.deadlineOf(1_000_000L, 30))
    }

    @Test
    fun anUnarmedDeadlineNeverExpires() {
        assertEquals(false, WmPlan.expired(0L, 9_999_999L))
        assertEquals(false, WmPlan.expired(1_000L, 999L))
        assertEquals(true, WmPlan.expired(1_000L, 1_000L))
        assertEquals(true, WmPlan.expired(1_000L, 5_000L))
    }

    @Test
    fun theExpiryNoteIsTheSentenceTheOperatorSees() {
        assertEquals("已自动恢复显示设置（超时）", WmPlan.expiryNote())
    }

    /**
     * v0.8.1 回归钉子：v0.8.0 的 [WmPlan.command] 少了 "wm " 前缀，真机上
     * `wm size 900x1600 apply` 实际跑了 `size 900x1600`，回
     * `/system/bin/sh: size: inaccessible or not found`。
     *
     * 这里不只钉一个用例，而是把**每一条**要发给手机的 wm 命令都过一遍，
     * 包括两个 reset 常量——读和 reset 当时是对的，正是靠它们才没早发现。
     */
    @Test
    fun everyShellCommandStartsWithWm() {
        val commands = ArrayList<String>()
        commands.add(WmPlan.SIZE_READ)
        commands.add(WmPlan.DENSITY_READ)
        commands.add(WmPlan.SIZE_RESET)
        commands.add(WmPlan.DENSITY_RESET)
        for (args in listOf(
            "size 900x1600 apply",
            "size 900x1600 120 apply",
            "size reset apply",
            "density 400 apply",
            "density 400 60 apply",
            "density reset apply"
        )) {
            commands.add(WmPlan.command(WmPlan.parse(args)!!))
        }
        for (command in commands) {
            assertTrue("必须带 wm 前缀，实际是: " + command, command.startsWith("wm "))
        }
    }

    @Test
    fun theDryRunLineIsTheRealCommandNotAFragment() {
        val dry = WmPlan.parse("size 900x1600")!!
        val line = WmPlan.dryRunLine(WmPlan.command(dry))
        assertTrue(line.startsWith("dry-run: "))
        assertTrue("dry-run 行必须整条带 wm: " + line, line.contains("wm size 900x1600"))
        assertEquals("dry-run: would run sh: wm size 900x1600", line)
    }

    @Test
    fun theDryRunAndErrorLinesExplainThemselves() {
        assertTrue(WmPlan.dryRunLine("wm size 900x1600").startsWith("dry-run: "))
        assertTrue(WmPlan.needShell().startsWith(Admin.ERROR_PREFIX))
        assertTrue(WmPlan.needShell().contains("shconnect"))
        assertTrue(WmPlan.unreadable().startsWith(Admin.ERROR_PREFIX))
        assertTrue(WmPlan.unreadable().contains("Physical"))
        val failed = WmPlan.failedLine("wm size 900x1600", 255, "  denied ")
        assertTrue(failed.startsWith(Admin.ERROR_PREFIX))
        assertTrue(failed.contains("255"))
        assertTrue(failed.contains("denied"))
    }

    @Test
    fun theAppliedLinePromisesTheAutoRestore() {
        val line = WmPlan.appliedLine(WmPlan.parse("size 900x1600 120 apply")!!)
        assertTrue(line.contains("900x1600"))
        assertTrue(line.contains("120s"))
        assertTrue(line.contains("自动恢复"))
        assertTrue(WmPlan.resetLine("size").contains("恢复"))
    }
}
