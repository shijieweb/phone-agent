package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守护进程的纯逻辑（v0.5.5）。
 *
 * 这些断言全部围绕三条纪律：不重复起第二个、停止能真停、状态不许撒谎。
 * 真机上的脚本行为验证在 manualcheck.md，这里锁的是「拼出来的命令是不是我以为的那条」。
 */
class WatchdogPlanTest {

    // ---------------------------------------------------------------- 脚本内容

    @Test
    fun scriptChecksTheServiceAndPullsTheUiUp() {
        val script = WatchdogPlan.script()
        assertTrue(script.startsWith("#!/system/bin/sh"))
        assertTrue(script.contains("dumpsys activity services " + WatchdogPlan.D))
        assertTrue(script.contains("grep -q " + WatchdogPlan.SERVICE_CLASS))
        assertTrue(script.contains("am start -n " + WatchdogPlan.D + "PKG/.MainActivity"))
        assertTrue(script.contains("sleep " + WatchdogPlan.INTERVAL_SECONDS))
    }

    @Test
    fun scriptHonoursTheStopMarker() {
        val script = WatchdogPlan.script()
        // 两处：进来先看一次，循环里每轮再看一次（disarm 与退出之间不留窗口）。
        val hits = Regex(Regex.escape(WatchdogPlan.OFF_MARKER)).findAll(script).count()
        assertEquals(2, hits)
    }

    @Test
    fun scriptHasNoSingleQuoteBecauseItIsWrittenWithEcho() {
        // 写脚本走 `echo '行' >> 文件`，行里出现单引号就会把命令截断。
        assertFalse(WatchdogPlan.script().contains("'"))
    }

    @Test
    fun scriptAppendsToTheLogWithATimestamp() {
        val script = WatchdogPlan.script()
        assertTrue(script.contains("restart >> " + WatchdogPlan.D + "LOG"))
        assertTrue(script.contains("date +%Y-%m-%dT%H:%M:%S"))
    }

    // ---------------------------------------------------------------- 命令拼装

    @Test
    fun writeCommandTruncatesThenAppendsEveryLine() {
        val command = WatchdogPlan.writeCommand()
        assertTrue(command.startsWith(": > " + WatchdogPlan.SCRIPT_PATH))
        assertTrue(command.contains("; echo '#!/system/bin/sh' >> " + WatchdogPlan.SCRIPT_PATH))
        assertTrue(command.endsWith("; chmod 755 " + WatchdogPlan.SCRIPT_PATH))
    }

    @Test
    fun writeCommandIsOneLineBecauseItGoesThroughThePty() {
        // PTY 是交互式 shell：命令里有换行就会被逐行执行，后半截变成独立命令。
        assertFalse(WatchdogPlan.writeCommand().contains("\n"))
    }

    @Test
    fun writeCommandCoversEveryScriptLine() {
        val lines = WatchdogPlan.script().trimEnd('\n').split("\n")
        val command = WatchdogPlan.writeCommand()
        for (line in lines) {
            assertTrue("漏了脚本行: " + line, command.contains("echo '" + line + "'"))
        }
    }

    @Test
    fun armCommandClearsTheMarkerAndDetaches() {
        val command = WatchdogPlan.armCommand()
        assertTrue(command.startsWith("rm -f " + WatchdogPlan.OFF_MARKER))
        assertTrue(command.contains("setsid sh " + WatchdogPlan.SCRIPT_PATH))
        assertTrue(command.trimEnd().endsWith("&"))
    }

    @Test
    fun disarmCommandWritesTheMarkerBeforeKilling() {
        val command = WatchdogPlan.disarmCommand()
        val marker = command.indexOf("echo off > " + WatchdogPlan.OFF_MARKER)
        val kill = command.indexOf("pkill -f " + WatchdogPlan.MARK)
        assertTrue(marker >= 0)
        assertTrue(kill > marker)
    }

    @Test
    fun disarmCommandSwallowsTheEmptyPgrep() {
        // pgrep 没命中返回 1，会让哨兵以为命令失败；`|| echo` 把它压成 0。
        assertTrue(WatchdogPlan.disarmCommand().contains("|| echo"))
    }

    @Test
    fun probeCommandSwallowsTheEmptyPgrepToo() {
        assertEquals("pgrep -f " + WatchdogPlan.MARK + " || echo", WatchdogPlan.probeCommand())
    }

    @Test
    fun statusCommandHasThreeAnchors() {
        val command = WatchdogPlan.statusCommand()
        assertTrue(command.contains("echo PIDS"))
        assertTrue(command.contains("echo LOG"))
        assertTrue(command.contains("echo OFF"))
    }

    // ---------------------------------------------------------------- 解析

    @Test
    fun parseStatusReadsAllThreeSections() {
        val raw = "PIDS\n12345\nLOG\n2026-11-25T16:20:01 restart\nOFF\n0\n"
        val status = WatchdogPlan.parseStatus(raw)
        assertEquals(listOf(12345), status.pids)
        assertEquals("2026-11-25T16:20:01 restart", status.lastRestart)
        assertFalse(status.offMarker)
    }

    @Test
    fun parseStatusHandlesNoProcessAndNoLog() {
        val raw = "PIDS\nLOG\n\nOFF\n0\n"
        val status = WatchdogPlan.parseStatus(raw)
        assertTrue(status.pids.isEmpty())
        assertEquals(null, status.lastRestart)
        assertFalse(status.offMarker)
    }

    @Test
    fun parseStatusSeesTheStopMarker() {
        val status = WatchdogPlan.parseStatus("PIDS\nOFF\n1\n")
        assertTrue(status.offMarker)
    }

    @Test
    fun parseStatusSurvivesCrlf() {
        val status = WatchdogPlan.parseStatus("PIDS\r\n777\r\nLOG\r\nrestart\r\nOFF\r\n0\r\n")
        assertEquals(listOf(777), status.pids)
        assertEquals("restart", status.lastRestart)
    }

    @Test
    fun parsePidsIgnoresNoiseAndZero() {
        assertEquals(listOf(12, 34), WatchdogPlan.parsePids("12\n34\n"))
        assertEquals(emptyList<Int>(), WatchdogPlan.parsePids(""))
        assertEquals(emptyList<Int>(), WatchdogPlan.parsePids("0"))
        assertEquals(listOf(99), WatchdogPlan.parsePids("pid 99"))
    }

    // ---------------------------------------------------------------- 判定

    @Test
    fun shouldArmOnlyWhenNothingRunsAndTheServiceDoes() {
        assertTrue(WatchdogPlan.shouldArm(emptyList(), serviceRunning = true))
        assertFalse(WatchdogPlan.shouldArm(listOf(1234), serviceRunning = true))
        assertFalse(WatchdogPlan.shouldArm(emptyList(), serviceRunning = false))
        assertFalse(WatchdogPlan.shouldArm(listOf(1234), serviceRunning = false))
    }

    @Test
    fun shouldDisarmFollowsTheAutoStartFlagNotTheServiceState() {
        // 服务自己 START_STICKY 重启时 autoStart 仍是 true，那时绝不能停守护；
        // 老板按停止会把 autoStart 清成 false，那时必须停。
        assertTrue(WatchdogPlan.shouldDisarm(autoStart = false))
        assertFalse(WatchdogPlan.shouldDisarm(autoStart = true))
    }

    // ---------------------------------------------------------------- 文案

    @Test
    fun statusSaysRunningWithThePid() {
        val text = WatchdogPlan.status(true, listOf(12345), "2026-11-25T16:20:01 restart", false)
        assertTrue(text.contains("守护: 运行中（pid 12345）"))
        assertTrue(text.contains("最近重启: 2026-11-25T16:20:01 restart"))
        assertTrue(text.contains("自连 ADB: 已连接"))
    }

    @Test
    fun statusSaysUnknownWhenAdbIsDownInsteadOfPretending() {
        val text = WatchdogPlan.status(false, emptyList(), null, false)
        assertTrue(text.contains("守护: 未知（自连 ADB 未连接）"))
        assertFalse(text.contains("运行中"))
    }

    @Test
    fun statusShowsNoRecordWhenTheLogIsEmpty() {
        val text = WatchdogPlan.status(true, emptyList(), null, false)
        assertTrue(text.contains("守护: 未运行"))
        assertTrue(text.contains("最近重启: (无记录)"))
    }

    @Test
    fun statusMentionsTheStopMarker() {
        assertTrue(WatchdogPlan.status(true, emptyList(), null, true).contains("已 disarm"))
        assertFalse(WatchdogPlan.status(true, emptyList(), null, false).contains("已 disarm"))
    }

    @Test
    fun statusListsTheScriptPathAndInterval() {
        val text = WatchdogPlan.status(true, emptyList(), null, false)
        assertTrue(text.contains(WatchdogPlan.SCRIPT_PATH))
        assertTrue(text.contains("间隔: 60s"))
    }

    @Test
    fun failureTextsAreRecognisableAsErrors() {
        // Executor 靠 Admin.ERROR_PREFIX 判退出码，前缀丢了就会把失败当成功。
        assertTrue(WatchdogPlan.armFailed("x").startsWith(Admin.ERROR_PREFIX))
        assertTrue(WatchdogPlan.armNeedsService().startsWith(Admin.ERROR_PREFIX))
        assertTrue(WatchdogPlan.disarmFailed("x").startsWith(Admin.ERROR_PREFIX))
    }

    @Test
    fun successTextsSayWhatHappened() {
        assertTrue(WatchdogPlan.armOk(listOf(12)).contains("pid 12"))
        assertTrue(WatchdogPlan.armAlready(listOf(12)).contains("未重复启动"))
        assertTrue(WatchdogPlan.disarmOk().contains("停止标记"))
        assertEquals("守护未启用：自连 ADB 未连接", WatchdogPlan.adbUnavailable())
    }

    @Test
    fun usageNamesAllThreeVerbs() {
        assertTrue(WatchdogPlan.USAGE.contains("status"))
        assertTrue(WatchdogPlan.USAGE.contains("arm"))
        assertTrue(WatchdogPlan.USAGE.contains("disarm"))
    }
}