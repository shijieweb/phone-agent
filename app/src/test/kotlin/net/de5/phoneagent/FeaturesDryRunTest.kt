package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three new dry runs (v0.8.0-pad12, SPEC.md §3.24–§3.26 and §10.28).
 *
 * "Changes a system setting" is only honest if the command refuses to change it
 * until `apply` is typed, and if the receipt says so in the one shape the rest of
 * this repo already uses (`dry-run: `).
 *
 * These run the real module executors through a context with no phone behind it:
 * none of the three touches the device before the dry run returns, and if one
 * ever starts to, the fake Context throws instead of quietly passing.
 */
class FeaturesDryRunTest {

    private val ctx = FakeFeatureContext()

    @Test
    fun batteryAllowIsADryRunThatNamesTheExactShellCommand() {
        val result = FeatBattery.execute(ctx, "allow")
        assertEquals(EXIT_OK, result.exit)
        assertTrue(result.stdout.startsWith("dry-run: "))
        assertTrue(result.stdout.contains("cmd deviceidle whitelist +net.de5.phoneagent"))
        assertEquals("", result.stderr)
    }

    @Test
    fun batteryDenyIsADryRunToo() {
        val result = FeatBattery.execute(ctx, "deny")
        assertTrue(result.stdout.startsWith("dry-run: "))
        assertTrue(result.stdout.contains("cmd deviceidle whitelist -net.de5.phoneagent"))
    }

    @Test
    fun notifyPostIsADryRunUntilApply() {
        val result = FeatNotify.execute(ctx, "post 标题 正文")
        assertEquals(EXIT_OK, result.exit)
        assertTrue(result.stdout.startsWith("dry-run: "))
        assertTrue(result.stdout.contains("-S bigtext"))
        assertTrue(result.stdout.contains("标题"))
    }

    @Test
    fun wmSizeIsADryRunUntilApply() {
        val result = FeatWm.execute(ctx, "size 900x1600")
        assertEquals(EXIT_OK, result.exit)
        // v0.8.1: 整条带 wm 前缀——v0.8.0 这里漏了，真机上真跑就废。
        assertEquals("dry-run: would run sh: wm size 900x1600", result.stdout)
    }

    @Test
    fun wmDensityIsADryRunUntilApply() {
        val result = FeatWm.execute(ctx, "density 400")
        assertEquals("dry-run: would run sh: wm density 400", result.stdout)
    }

    @Test
    fun theTwoApplyPositionsBehaveIdentically() {
        // 语法等价已经在 NotifyPlanTest 里逐字钉过；这里钉的是执行侧：
        // 两种写法都不再被当成正文，都在没有 self-ADB 时回 exit 6 + shconnect。
        val front = FeatNotify.execute(ctx, "post apply 标题 正文")
        val back = FeatNotify.execute(ctx, "post 标题 正文 apply")
        assertEquals(EXIT_PERMISSION, front.exit)
        assertEquals(EXIT_PERMISSION, back.exit)
        assertEquals(front.stderr, back.stderr)
        assertTrue(back.stderr.contains("shconnect"))
    }

    @Test
    fun aBodyThatMerelyContainsApplyStillGoesOutAsWritten() {
        val result = FeatNotify.execute(ctx, "post 标题 请 apply 一下")
        assertEquals(EXIT_OK, result.exit)
        assertTrue(result.stdout.startsWith("dry-run: "))
        assertTrue(result.stdout.contains("请 apply 一下"))
    }

    @Test
    fun aDryRunNeverTouchesTheShell() {
        // FakeFeatureContext returns null for every shell call, which is what
        // "no self-ADB" looks like; a dry run must not care.
        for (args in listOf("allow", "deny")) {
            assertEquals(EXIT_OK, FeatBattery.execute(ctx, args).exit)
        }
        assertEquals(EXIT_OK, FeatNotify.execute(ctx, "post t b").exit)
        assertEquals(EXIT_OK, FeatWm.execute(ctx, "size 900x1600").exit)
    }

    @Test
    fun applyWithoutSelfAdbSaysShconnectRatherThanPretending() {
        val battery = FeatBattery.execute(ctx, "allow apply")
        assertEquals(EXIT_PERMISSION, battery.exit)
        assertTrue(battery.stderr.contains("shconnect"))

        val notify = FeatNotify.execute(ctx, "post apply 标题 正文")
        assertEquals(EXIT_PERMISSION, notify.exit)
        assertTrue(notify.stderr.contains("shconnect"))

        val wm = FeatWm.execute(ctx, "size 900x1600 apply")
        assertEquals(EXIT_PERMISSION, wm.exit)
        assertTrue(wm.stderr.contains("shconnect"))
    }

    @Test
    fun wmReadWithoutSelfAdbIsAlsoShconnect() {
        val result = FeatWm.execute(ctx, "")
        assertEquals(EXIT_PERMISSION, result.exit)
        assertTrue(result.stderr.contains("shconnect"))
    }

    @Test
    fun aRefusedParseIsExitTwoWithAUsageLine() {
        assertEquals(EXIT_USAGE, FeatWm.execute(ctx, "brightness 50").exit)
        assertEquals(EXIT_USAGE, FeatBattery.execute(ctx, "maybe").exit)
        assertEquals(EXIT_USAGE, FeatNotify.execute(ctx, "post onlytitle").exit)
        assertEquals(EXIT_USAGE, FeatMedia.execute(ctx, "rewind").exit)
    }

    @Test
    fun theTwoCommandsThatNeedAPhoneOnlyRefuseAtParseTime() {
        // `autowake` reads Prefs and `where` reads the accessibility service, so
        // their executors genuinely need a Context; the grammar is what a JVM test
        // can prove, and the phone-side behaviour is in manualcheck.md.
        assertTrue(FeatAutowake.parse("sometimes") is Parsed.Bad)
        assertTrue(FeatAutowake.parse("on") is Parsed.Ok)
        assertTrue(FeatWhere.parse("now") is Parsed.Bad)
        assertTrue(FeatWhere.parse("") is Parsed.Ok)
    }
}
