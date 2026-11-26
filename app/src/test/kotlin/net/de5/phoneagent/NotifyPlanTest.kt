package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `notify post` (v0.8.0-pad12, SPEC.md §3.25).
 *
 * The body keeps its original spacing (it is what the operator will read on the
 * phone), the title is one token, and the shell command has to survive quotes.
 */
class NotifyPlanTest {

    @Test
    fun theBodyKeepsItsInternalSpacing() {
        val request = NotifyPlan.parse("post 标题 这是一段 正文  with  spaces")!!
        assertEquals("标题", request.title)
        assertEquals("这是一段 正文  with  spaces", request.body)
        assertEquals(false, request.apply)
    }

    @Test
    fun applyIsOptionalAndComesBeforeTheTitle() {
        val request = NotifyPlan.parse("post apply 标题 正文")!!
        assertEquals(true, request.apply)
        assertEquals("标题", request.title)
        assertEquals("正文", request.body)
        assertEquals("post apply 标题 正文", NotifyPlan.normalize(request))
        assertEquals("post 标题 正文", NotifyPlan.normalize(NotifyPlan.parse("post 标题 正文")!!))
    }

    /**
     * v0.8.1: `apply` 末尾也认。
     *
     * v0.8.0 只认前置，而 `wm size 900x1600 apply` 是后置——真机上操作员按
     * wm 的直觉把 apply 写在末尾，结果它被当成正文发进通知里。两种写法必须
     * 产生**同一条**命令。
     */
    @Test
    fun applyIsAlsoAcceptedAtTheEndAndMeansTheSameThing() {
        val front = NotifyPlan.parse("post apply 标题 正文")!!
        val back = NotifyPlan.parse("post 标题 正文 apply")!!
        assertEquals(true, front.apply)
        assertEquals(true, back.apply)
        assertEquals(front.title, back.title)
        assertEquals(front.body, back.body)
        assertEquals("正文", back.body)
        assertEquals(
            NotifyPlan.command(front.title, front.body, bigText = true),
            NotifyPlan.command(back.title, back.body, bigText = true)
        )
        assertEquals("post apply 标题 正文", NotifyPlan.normalize(back))
    }

    @Test
    fun aTrailingApplyIsOnlyStrippedWhenATitleAndBodyRemain() {
        // "post t apply" 剥完只剩标题 —— 不剥，正文就是 apply 这个词本身。
        val single = NotifyPlan.parse("post t apply")!!
        assertEquals(false, single.apply)
        assertEquals("t", single.title)
        assertEquals("apply", single.body)

        // 前置已经置位时，末尾那个 apply 是正文的一部分，不许再剥。
        val both = NotifyPlan.parse("post apply 标题 A apply")!!
        assertEquals(true, both.apply)
        assertEquals("标题", both.title)
        assertEquals("A apply", both.body)

        // 正文中间或末尾之外的 apply 不是开关。
        val inner = NotifyPlan.parse("post 标题 请 apply 一下")!!
        assertEquals(false, inner.apply)
        assertEquals("请 apply 一下", inner.body)
        assertEquals(false, NotifyPlan.parse("post 标题 applyx")!!.apply)
    }

    @Test
    fun theHeadMustBePost() {
        assertNull(NotifyPlan.parse(""))
        assertNull(NotifyPlan.parse("send 标题 正文"))
        assertNull(NotifyPlan.parse("post"))
        assertNull(NotifyPlan.parse("post apply"))
    }

    @Test
    fun aTitleWithoutABodyOrAnOversizedFieldIsRefused() {
        assertNull(NotifyPlan.parse("post 只有标题"))
        assertNull(NotifyPlan.parse("post " + "t".repeat(NotifyPlan.TITLE_MAX + 1) + " 正文"))
        assertNull(NotifyPlan.parse("post 标题 " + "b".repeat(NotifyPlan.BODY_MAX + 1)))
    }

    @Test
    fun theLimitsThemselvesAreAccepted() {
        val title = "t".repeat(NotifyPlan.TITLE_MAX)
        val body = "b".repeat(NotifyPlan.BODY_MAX)
        val request = NotifyPlan.parse("post " + title + " " + body)!!
        assertEquals(NotifyPlan.TITLE_MAX, request.title.length)
        assertEquals(NotifyPlan.BODY_MAX, request.body.length)
    }

    @Test
    fun theShellCommandCarriesBigtextAndTheTag() {
        assertEquals(
            "cmd notification post -S bigtext -t \"标题\" phoneagent \"正文\"",
            NotifyPlan.command("标题", "正文", bigText = true)
        )
        assertEquals(
            "cmd notification post -t \"标题\" phoneagent \"正文\"",
            NotifyPlan.command("标题", "正文", bigText = false)
        )
        assertEquals("phoneagent", NotifyPlan.TAG)
    }

    @Test
    fun quotesDollarsAndBackticksInTheBodyAreEscapedForTheShell() {
        val command = NotifyPlan.command("a\"b", "c\"d\$e`f\\g", bigText = false)
        val expected = "cmd notification post -t \"a\\\"b\" phoneagent \"c\\\"d\\\$e\\`f\\\\g\""
        assertEquals(expected, command)
    }

    @Test
    fun theDryRunLineNamesTheBigtextFormFirst() {
        val line = NotifyPlan.dryRunLine(NotifyPlan.command("t", "b", bigText = true))
        assertTrue(line.startsWith("dry-run: "))
        assertTrue(line.contains("-S bigtext"))
    }

    @Test
    fun theRetryLineSaysWhatWasDropped() {
        assertEquals("notify 已发送", NotifyPlan.postedLine())
        val retried = NotifyPlan.retriedLine()
        assertTrue(retried.contains("已发送"))
        assertTrue(retried.contains("-S bigtext"))
    }

    @Test
    fun aMissingShellIsReportedAsShconnect() {
        val text = NotifyPlan.needShell()
        assertTrue(text.startsWith(Admin.ERROR_PREFIX))
        assertTrue(text.contains("shconnect"))
        assertTrue(text.contains("uid 2000"))
    }

    @Test
    fun aFailedPostKeepsTheCodeAndTheDeviceText() {
        val text = NotifyPlan.failedLine(1, "  Error: no service ")
        assertTrue(text.startsWith(Admin.ERROR_PREFIX))
        assertTrue(text.contains("1"))
        assertTrue(text.contains("Error: no service"))
    }

    @Test
    fun aBodyWhoseLastWordIsApplyNeedsTheFrontSwitch() {
        // 末尾那个 apply 会被当开关：这是有意的取舍，不是漏判。
        val eaten = NotifyPlan.parse("post 测试 请 apply")!!
        assertEquals(true, eaten.apply)
        assertEquals("测试", eaten.title)
        assertEquals("请", eaten.body)

        // 真想发这个词就把开关写前面，末尾那个原样留下。
        val literal = NotifyPlan.parse("post apply 测试 请 apply")!!
        assertEquals(true, literal.apply)
        assertEquals("请 apply", literal.body)
    }
}

