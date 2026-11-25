package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PTY 通道的解析测试。
 *
 * 这些用例全部是纯函数，不需要设备：真机上跑一条命令的代价是十几秒，
 * 而解析错一次就是「退出码 0 但输出是脏的」，比崩溃更难发现。
 */
class PtyParseTest {

    // ---------------------------------------------------------- 哨兵行格式

    @Test
    fun sentinelEchoIsQuotedSoItPrintsOneCleanLine() {
        // 裸写 echo __RC=$? 打出来是裸的 0，和命令输出分不开；带引号才是一整行。
        assertEquals("echo \"__RC=$?\"", SelfAdbPlan.SENTINEL_ECHO)
    }

    @Test
    fun ptyWritePutsCommandThenSentinelOnItsOwnLine() {
        assertEquals("id\n" + SelfAdbPlan.SENTINEL_ECHO + "\n", SelfAdbPlan.ptyWrite("id"))
    }

    @Test
    fun ptyWriteKeepsMultiLineCommandIntact() {
        // 用换行而不是分号拼接：块语法（for/if）用分号会被压平。
        val command = "for i in 1 2; do\n  echo \$i\ndone"
        assertEquals(command + "\n" + SelfAdbPlan.SENTINEL_ECHO + "\n", SelfAdbPlan.ptyWrite(command))
    }

    // ---------------------------------------------------------- 标准路径

    @Test
    fun parsesEchoOutputAndZeroExit() {
        val raw = "id\r\nuid=2000(shell) gid=2000(shell)\r\n" +
            "echo \"__RC=$?\"\r\n__RC=0\r\n"
        val exit = SelfAdbPlan.parsePty(raw, "id")
        assertEquals(0, exit.code)
        assertEquals("uid=2000(shell) gid=2000(shell)", exit.text)
    }

    @Test
    fun parsesNonZeroExit() {
        val raw = "ls /nope\r\nls: /nope: No such file or directory\r\n__RC=1\r\n"
        val exit = SelfAdbPlan.parsePty(raw, "ls /nope")
        assertEquals(1, exit.code)
        assertEquals("ls: /nope: No such file or directory", exit.text)
    }

    @Test
    fun parses127ForMissingCommand() {
        val raw = "nosuchcmd\r\n/system/bin/sh: nosuchcmd: not found\r\n__RC=127\r\n"
        val exit = SelfAdbPlan.parsePty(raw, "nosuchcmd")
        assertEquals(127, exit.code)
        assertEquals("/system/bin/sh: nosuchcmd: not found", exit.text)
    }

    @Test
    fun parsesEmptyOutput() {
        val raw = "true\r\n__RC=0\r\n"
        val exit = SelfAdbPlan.parsePty(raw, "true")
        assertEquals(0, exit.code)
        assertEquals("", exit.text)
    }

    @Test
    fun parsesWithoutEchoLine() {
        // 有些 ROM 的 PTY 不开回显，不能因此认为「没有输出」。
        val exit = SelfAdbPlan.parsePty("uid=2000(shell)\n__RC=0\n", "id")
        assertEquals(0, exit.code)
        assertEquals("uid=2000(shell)", exit.text)
    }

    @Test
    fun keepsMultiLineOutput() {
        val raw = "ls\r\na.txt\r\nb.txt\r\n__RC=0\r\n"
        val exit = SelfAdbPlan.parsePty(raw, "ls")
        assertEquals("a.txt\nb.txt", exit.text)
    }

    @Test
    fun handlesLfOnlyLineEndings() {
        val exit = SelfAdbPlan.parsePty("echo hi\nhi\n__RC=0\n", "echo hi")
        assertEquals(0, exit.code)
        assertEquals("hi", exit.text)
    }

    // ---------------------------------------------------------- ANSI 与提示符

    @Test
    fun stripsAnsiColourSequences() {
        val raw = "\u001b[1;32mid\u001b[0m\r\n\u001b[32muid=2000(shell)\u001b[0m\r\n__RC=0\r\n"
        val exit = SelfAdbPlan.parsePty(raw, "id")
        assertEquals(0, exit.code)
        assertEquals("uid=2000(shell)", exit.text)
    }

    @Test
    fun stripsPromptAtStartOfOutput() {
        // 提示符会黏在第一条输出前面，不剥就变成 Meizu22:/ $ uid=2000。
        val raw = "Meizu22:/ $ id\r\nMeizu22:/ $ uid=2000(shell)\r\n__RC=0\r\n"
        val exit = SelfAdbPlan.parsePty(raw, "id")
        assertEquals(0, exit.code)
        assertEquals("uid=2000(shell)", exit.text)
    }

    @Test
    fun stripsRootPrompt() {
        val raw = "box:/ # id\r\nbox:/ # uid=0(root)\r\n__RC=0\r\n"
        val exit = SelfAdbPlan.parsePty(raw, "id")
        assertEquals("uid=0(root)", exit.text)
    }

    @Test
    fun stripsFlymeExitCodePrefixedPrompt() {
        // 真机 Meizu22 的提示符带上一条的退出码：127|Meizu22:/ $ 。
        val raw = "127|Meizu22:/ $ echo \"__RC=$?\"\r\n__RC=0\r\n"
        val exit = SelfAdbPlan.parsePty(raw, "true")
        assertEquals(0, exit.code)
        assertEquals("", exit.text)
    }

    @Test
    fun stripsBracketedExitCodePrompt() {
        val raw = "[1]box:/ # id\r\n[1]box:/ # uid=0(root)\r\n__RC=0\r\n"
        val exit = SelfAdbPlan.parsePty(raw, "id")
        assertEquals("uid=0(root)", exit.text)
    }

    @Test
    fun looksLikePromptAcceptsDollarAndHash() {
        assertTrue(SelfAdbPlan.looksLikePrompt("Meizu22:/ $ "))
        assertTrue(SelfAdbPlan.looksLikePrompt("Meizu22:/ # "))
        assertTrue(SelfAdbPlan.looksLikePrompt("\u001b[0mMeizu22:/ $ "))
        assertFalse(SelfAdbPlan.looksLikePrompt("Meizu22:/ $"))
        assertFalse(SelfAdbPlan.looksLikePrompt(""))
    }

    // ---------------------------------------------------------- 命令自带 __RC=

    @Test
    fun dataLineMentioningSentinelIsKeptAsData() {
        // 行内出现 __RC=（不是独立成行）只是数据，必须原样留在输出里。
        val raw = "printf x\r\nlog __RC=9 here\r\n__RC=0\r\n"
        val exit = SelfAdbPlan.parsePty(raw, "printf x")
        assertEquals(0, exit.code)
        assertEquals("log __RC=9 here", exit.text)
    }

    @Test
    fun bareSentinelLookingDataLineIsIndistinguishable() {
        // 诚实的限制：命令自己打印一行 __RC=9 时，和哨兵行逐字相同，无法区分。
        // 这是哨兵法的固有代价（协议层不回退出码）；好在哨兵永远在最后，
        // 所以取「最后一次」拿到的仍然是真实退出码。
        val raw = "echo \"__RC=9\"\r\n__RC=9\r\n__RC=0\r\n"
        val exit = SelfAdbPlan.parsePty(raw, "echo \"__RC=9\"")
        assertEquals(0, exit.code)
        assertEquals("", exit.text)
    }

    @Test
    fun takesLastSentinelWhenCommandPrintsOne() {
        val raw = "printf x\r\n__RC=7\r\n__RC=3\r\n"
        assertEquals(3, SelfAdbPlan.parsePty(raw, "printf x").code)
    }

    @Test
    fun sentinelWithTrailingTextIsNotExitCode() {
        val raw = "printf x\r\n__RC=7 extra\r\n"
        assertNull(SelfAdbPlan.parsePty(raw, "printf x").code)
    }

    @Test
    fun dropsEchoedSentinelCommandLine() {
        // PTY 回显的 echo "__RC=$?" 里只有 $? 没有数字，但也必须整行丢掉。
        val raw = "true\r\necho \"__RC=$?\"\r\n__RC=0\r\n"
        val exit = SelfAdbPlan.parsePty(raw, "true")
        assertEquals(0, exit.code)
        assertEquals("", exit.text)
    }

    // ---------------------------------------------------------- 超时与截断

    @Test
    fun noSentinelMeansUnknownCodeButKeepsPartialOutput() {
        // 命令跑到一半超时：退出码未知，但收到的内容不能丢。
        val exit = SelfAdbPlan.parsePty("sleep 100\r\npartial line\r\n", "sleep 100")
        assertNull(exit.code)
        assertEquals("partial line", exit.text)
    }

    @Test
    fun hasSentinelDetectsOnlyCompleteLines() {
        assertTrue(SelfAdbPlan.hasSentinel("a\n__RC=0\n"))
        assertTrue(SelfAdbPlan.hasSentinel("Meizu22:/ $ __RC=12\r\n"))
        assertFalse(SelfAdbPlan.hasSentinel("__RC="))
        assertFalse(SelfAdbPlan.hasSentinel("__RC=0 extra"))
        assertFalse(SelfAdbPlan.hasSentinel("nothing here"))
    }

    @Test
    fun hasSentinelSeesThroughAnsi() {
        assertTrue(SelfAdbPlan.hasSentinel("\u001b[0m__RC=0\r\n"))
    }

    // ---------------------------------------------------------- 通道与 DEST

    @Test
    fun ptyDestinationIsTheBareShellService() {
        // 关键：空命令 shell: 走 kPty；带命令的 shell:cmd 在这版 ROM 上必挂。
        assertEquals("shell:", SelfAdbPlan.DEST_PTY)
    }

    @Test
    fun channelLineDistinguishesPtyFromRaw() {
        assertTrue(SelfAdbPlan.channelLine(true).contains("PTY"))
        assertTrue(SelfAdbPlan.channelLine(false).contains("RAW"))
    }

    @Test
    fun statusShowsChannel() {
        val text = SelfAdbPlan.status(true, "SHA256:abc", true, null, true)
        assertTrue(text.contains("通道: PTY"))
        assertTrue(SelfAdbPlan.status(false, null, false, null, false).contains("通道: RAW"))
    }

    @Test
    fun usageMentionsShdestAndShkeyVerbs() {
        assertTrue(SelfAdbPlan.USAGE.contains("shdest"))
        assertTrue(SelfAdbPlan.USAGE.contains("shkey import"))
    }
}
