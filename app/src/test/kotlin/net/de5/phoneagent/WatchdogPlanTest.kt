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
        val kill = command.indexOf("pkill -9 -f " + WatchdogPlan.MARK)
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
        assertTrue(WatchdogPlan.shouldArm(emptyList(), scriptCurrent = true, serviceRunning = true))
        assertFalse(WatchdogPlan.shouldArm(listOf(1234), scriptCurrent = true, serviceRunning = true))
        assertFalse(WatchdogPlan.shouldArm(emptyList(), scriptCurrent = true, serviceRunning = false))
        assertFalse(WatchdogPlan.shouldArm(listOf(1234), scriptCurrent = true, serviceRunning = false))
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
        // 手动 arm/disarm 必须带错误前缀，否则 Executor 会把它当成功回 exit 0，
        // 控制器就以为守护已经开起来了。
        assertTrue(WatchdogPlan.armUnavailable().startsWith(Admin.ERROR_PREFIX))
        assertTrue(WatchdogPlan.disarmUnavailable().startsWith(Admin.ERROR_PREFIX))
        assertTrue(WatchdogPlan.armUnavailable().contains("shconnect"))
        assertTrue(WatchdogPlan.disarmUnavailable().contains("shconnect"))
    }

    @Test
    fun usageNamesAllThreeVerbs() {
        assertTrue(WatchdogPlan.USAGE.contains("status"))
        assertTrue(WatchdogPlan.USAGE.contains("arm"))
        assertTrue(WatchdogPlan.USAGE.contains("disarm"))
    }

    // ------------------------------------------------- v0.6.3-pad12 守护脚本两个坑

    /**
     * 坑 A：pkill -f（SIGTERM）杀不掉那个 while true; do ...; sleep 60; done 的脚本，
     * 因为 shell 在 wait 子进程时不响应 SIGTERM，要等那一轮 60 秒睡完。
     * 真机现象：报「还有进程 3004」，手动 kill 却回 No such process。
     *
     * 断言三件事：用 -9、重试不止一次、仍然回读进程表。
     */
    @Test
    fun disarmUsesSigkillAndRetries() {
        val command = WatchdogPlan.disarmCommand()
        assertTrue(
            "必须用 SIGKILL，SIGTERM 会卡在 sleep 上: " + command,
            command.contains("pkill -9 -f " + WatchdogPlan.MARK)
        )
        assertFalse("SIGTERM 版不许再出现", command.contains("pkill -f " + WatchdogPlan.MARK))
        val hits = Regex(Regex.escape("pkill -9 -f " + WatchdogPlan.MARK)).findAll(command).count()
        assertEquals("三次 pkill", 3, hits)
        assertTrue("三次之间要有间隔", command.contains("sleep 0.5"))
        assertTrue("最后仍要回读进程表", command.trimEnd().endsWith("|| echo"))
    }

    @Test
    fun disarmKeepsTheMarkerFirstAndHasNoSingleQuote() {
        val command = WatchdogPlan.disarmCommand()
        val marker = command.indexOf("echo off > " + WatchdogPlan.OFF_MARKER)
        val kill = command.indexOf("pkill -9 -f " + WatchdogPlan.MARK)
        assertTrue("停止标记必须先写，否则脚本下一轮又起来了", marker == 0)
        assertTrue(kill > marker)
        // 与 writeCommand 同一条纪律：命令串里出现单引号就会把 echo '行' 截断。
        assertFalse(command.contains("'"))
    }

    /**
     * 坑 B：旧脚本永远不会被换掉。
     *
     * arm 以前「看到进程在跑就说已在运行」，于是真机上一直留着一份旧脚本，
     * 日志封不了顶。现在脚本第二行带版本标记，arm 先数命中行数，对不上就重写。
     */
    @Test
    fun theScriptCarriesItsVersionOnTheSecondLine() {
        val lines = WatchdogPlan.script().trimEnd('\n').split("\n")
        assertEquals("#!/system/bin/sh", lines[0])
        assertEquals(WatchdogPlan.SCRIPT_STAMP_LINE, lines[1])
        assertTrue(lines[1].contains(WatchdogPlan.SCRIPT_VERSION))
        // 写脚本走 echo '行'，所以标记行里也不能有单引号。
        assertFalse(lines[1].contains("'"))
    }

    @Test
    fun theVersionProbeCountsHitsInsteadOfTrustingAnExitCode() {
        val command = WatchdogPlan.stampCommand()
        assertTrue(command.contains("grep -c ver=" + WatchdogPlan.SCRIPT_VERSION))
        assertTrue(command.contains(WatchdogPlan.SCRIPT_PATH))
        // pgrep 那套 || echo 的教训：没命中不能变成非零退出。
        assertTrue(command.contains("|| echo 0"))
    }

    @Test
    fun aPtyEchoCannotFakeACurrentScript() {
        // 真机 PTY 会把命令行原样回显，那行里有字母也有标记，不是纯数字。
        val echoed = "grep -c ver=" + WatchdogPlan.SCRIPT_VERSION + " " +
            WatchdogPlan.SCRIPT_PATH + " 2>/dev/null || echo 0\n0\n"
        assertFalse(WatchdogPlan.parseScriptCurrent(echoed))
        assertTrue(WatchdogPlan.parseScriptCurrent(echoed + "1\n"))
    }

    @Test
    fun parseScriptCurrentReadsTheLastNumberAndDefaultsToStale() {
        assertTrue(WatchdogPlan.parseScriptCurrent("1\n"))
        assertTrue(WatchdogPlan.parseScriptCurrent("0\n1\n"))
        assertFalse(WatchdogPlan.parseScriptCurrent("0\n"))
        assertFalse(WatchdogPlan.parseScriptCurrent(""))
        assertFalse(WatchdogPlan.parseScriptCurrent("No such file or directory\n"))
        assertFalse("噪声里的数字不算", WatchdogPlan.parseScriptCurrent("sh: 1: not found\n"))
    }

    @Test
    fun aStaleScriptIsWorthArmingOver() {
        // 版本旧但进程在跑：仍然要 arm（内部会强杀旧脚本再重写）。
        assertTrue(WatchdogPlan.shouldArm(listOf(1234), scriptCurrent = false, serviceRunning = true))
        // 版本对且进程在跑：什么都不做。
        assertFalse(WatchdogPlan.shouldArm(listOf(1234), scriptCurrent = true, serviceRunning = true))
        // 服务没在跑时任何情况都不 arm（否则守护一起来就去拉界面，等于绕过停止）。
        assertFalse(WatchdogPlan.shouldArm(emptyList(), scriptCurrent = false, serviceRunning = false))
        assertFalse(WatchdogPlan.shouldArm(listOf(1234), scriptCurrent = false, serviceRunning = false))
    }

    @Test
    fun replacingAnOldScriptSaysSoOutLoud() {
        val text = WatchdogPlan.armReplaced(listOf(3004))
        assertTrue(text.contains("pid 3004"))
        assertTrue(text.contains(WatchdogPlan.SCRIPT_VERSION))
        assertTrue("换脚本不是错误", !text.startsWith(Admin.ERROR_PREFIX))
    }

    @Test
    fun aStuckOldScriptIsAnErrorNotASilentSecondCopy() {
        val text = WatchdogPlan.armStuck(listOf(3004))
        assertTrue(text.startsWith(Admin.ERROR_PREFIX))
        assertTrue(text.contains("pid 3004"))
        assertTrue("要给出下一步", text.contains("kill -9"))
    }
}