package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志占用的纯逻辑（v0.5.8-pad12）：量、裁、报。
 *
 * 老板只提了一条「日志不能无限膨胀」。审计下来真正无界的是守护脚本那个
 * /data/local/tmp/phoneagent-guard.log；这里钉住三件事：
 * 1) 量的命令只认数字，不会被 PTY 回显骗（回显里是 `B=$(wc -c ...)`，没有数字）；
 * 2) 裁的命令与守护脚本里那一段是同一套判定，不会互相打脸；
 * 3) 读不到就说读不到，绝不拿 0 字节糊过去——那会让操作员以为日志很干净。
 */
class LogSizesTest {

    @Test
    fun theReadCommandMeasuresTheGuardLogNotSomeOtherFile() {
        val cmd = LogSizes.readCommand()
        assertTrue(cmd.contains(WatchdogPlan.LOG_PATH))
        assertTrue(cmd.contains("wc -c"))
        assertTrue(cmd.contains("MISSING"))
        // 单引号会截断 echo '行' 那种写法，脚本里一个都不许有。
        assertFalse(cmd.contains("'"))
    }

    @Test
    fun theTrimCommandOnlyActsWhenOverTheCap() {
        val cmd = LogSizes.trimCommand()
        assertTrue(cmd.contains(WatchdogPlan.LOG_MAX_BYTES.toString()))
        assertTrue(cmd.contains("tail -n " + WatchdogPlan.LOG_KEEP_LINES))
        assertTrue(cmd.contains("TRIMMED"))
        assertTrue(cmd.contains("UNDER"))
        assertFalse(cmd.contains("'"))
    }

    @Test
    fun theGuardLogCapIsSixtyFourKilobytes() {
        assertEquals(64 * 1024, WatchdogPlan.LOG_MAX_BYTES)
        assertEquals(200, WatchdogPlan.LOG_KEEP_LINES)
    }

    @Test
    fun aMeasurementIsRead() {
        val guard = LogSizes.parseGuard("B=2048 L=17")
        assertEquals(2048L, guard?.bytes)
        assertEquals(17, guard?.lines)
    }

    /**
     * PTY 会把命令行回显一遍，里面写着 `B=$(wc -c < ...)`——带美元括号不带数字。
     * 只认 B=数字 就不会把回显当成结果；同时取最后一组，命令自己打印的历史行
     * 不会顶掉真结果。
     */
    @Test
    fun theEchoedCommandLineIsNotMistakenForAResult() {
        val text = "if [ -f /data/local/tmp/phoneagent-guard.log ]; then " +
            "echo B=$(wc -c < /data/local/tmp/phoneagent-guard.log | tr -d \" \") " +
            "L=$(wc -l < /data/local/tmp/phoneagent-guard.log); fi\n" +
            "B=512 L=3\n"
        val guard = LogSizes.parseGuard(text)
        assertEquals(512L, guard?.bytes)
        assertEquals(3, guard?.lines)
    }

    @Test
    fun theLastMeasurementWins() {
        val guard = LogSizes.parseGuard("B=1 L=1\nB=999 L=42\n")
        assertEquals(999L, guard?.bytes)
        assertEquals(42, guard?.lines)
    }

    @Test
    fun garbageIsNotAMeasurement() {
        assertNull(LogSizes.parseGuard(""))
        assertNull(LogSizes.parseGuard("B= L="))
        assertNull(LogSizes.parseGuard("nothing here"))
        // 只有一半也不算：两个数必须成对，否则报出来的账是错的。
        assertNull(LogSizes.parseGuard("B=100"))
        assertNull(LogSizes.parseGuard("L=100"))
    }

    @Test
    fun trimReceiptsAreToldApart() {
        assertEquals("trimmed", LogSizes.trimStatus("SZ=99999\nTRIMMED"))
        assertEquals("under", LogSizes.trimStatus("UNDER"))
        assertEquals("missing", LogSizes.trimStatus("MISSING"))
        assertNull(LogSizes.trimStatus(""))
    }

    @Test
    fun onlyFilesOlderThanADayCountAsLeftovers() {
        val now = 1_000_000_000_000L
        val fresh = LogSizes.Apk("fresh.apk", 10, now - 1_000L)
        val stale = LogSizes.Apk("stale.apk", 20, now - LogSizes.APK_STALE_MS - 1L)
        val exactlyADay = LogSizes.Apk("edge.apk", 30, now - LogSizes.APK_STALE_MS)
        val kept = LogSizes.stale(listOf(fresh, stale, exactlyADay), now)
        assertEquals(listOf("stale.apk"), kept.map { it.name })
        assertEquals(20L, LogSizes.total(kept))
    }

    @Test
    fun theSizeReportNamesAllThreeLedgers() {
        val report = LogSizes.sizeReport(
            LogSizes.Guard(64L * 1024L, 210),
            null,
            400,
            12,
            listOf(LogSizes.Apk("a.apk", 2L * 1024L * 1024L, 0))
        )
        assertTrue(report.contains("内存日志: 400 行"))
        assertTrue(report.contains("丢弃较早的 12 行"))
        assertTrue(report.contains(AgentLog.MAX_LINES.toString()))
        assertTrue(report.contains("守护日志: 64.0 KB"))
        assertTrue(report.contains("APK 残留: 1 个，共 2.0 MB"))
    }

    /** 读不到守护日志时不许编数字：要说清楚为什么读不到。 */
    @Test
    fun anUnreadableGuardLogSaysWhy() {
        val report = LogSizes.sizeReport(null, "自连 ADB 未连接", 0, 0, emptyList())
        assertTrue(report.contains("不可用"))
        assertTrue(report.contains("自连 ADB 未连接"))
        assertFalse(report.contains("0 B"))
    }

    @Test
    fun aDryRunNeverClaimsItDeletedAnything() {
        val apks = listOf(LogSizes.Apk("old.apk", 1024L, 0))
        val report = LogSizes.trimReport(
            LogSizes.Guard(100L * 1024L, 300),
            null,
            apks,
            0,
            apply = false,
            after = null
        )
        assertTrue(report.contains("dry-run"))
        assertTrue(report.contains("将裁到末 " + WatchdogPlan.LOG_KEEP_LINES + " 行"))
        assertTrue(report.contains("将删"))
        assertFalse(report.contains("已删"))
        assertFalse(report.contains("已按上面执行"))
    }

    @Test
    fun anApplyReportsWhatActuallyHappened() {
        val report = LogSizes.trimReport(
            LogSizes.Guard(100L * 1024L, 300),
            null,
            listOf(LogSizes.Apk("old.apk", 1024L, 0)),
            1,
            apply = true,
            after = LogSizes.Guard(8L * 1024L, 200)
        )
        assertTrue(report.contains("已按上面执行"))
        assertTrue(report.contains("已删 1 个"))
        assertTrue(report.contains("8.0 KB"))
        assertFalse(report.contains("dry-run"))
    }

    /**
     * 真发了裁剪命令却没回读到：只能说「没确认」，不能替裁剪成功吹牛。
     * 这条是给「命令超时了但文件其实裁了」那种模糊状态用的。
     */
    @Test
    fun anApplyWithoutAReadBackDoesNotClaimSuccess() {
        val report = LogSizes.trimReport(
            LogSizes.Guard(100L * 1024L, 300),
            null,
            emptyList(),
            0,
            apply = true,
            after = null
        )
        assertTrue(report.contains("没回读到结果"))
        assertFalse(report.contains("已裁到末"))
    }

    @Test
    fun aSmallGuardLogIsLeftAlone() {
        val report = LogSizes.trimReport(
            LogSizes.Guard(1024L, 5),
            null,
            emptyList(),
            0,
            apply = true,
            after = null
        )
        assertTrue(report.contains("没超上限"))
        assertTrue(report.contains("不动"))
    }

    @Test
    fun theSummaryLineFitsOneLineOfTheDebugScreen() {
        val line = LogSizes.summaryLine(
            400,
            12,
            LogSizes.Guard(3L * 1024L + 204L, 40),
            listOf(LogSizes.Apk("a.apk", 2_600_000L, 0))
        )
        assertFalse(line.contains('\n'))
        assertTrue(line.contains("400 行/丢弃 12"))
        assertTrue(line.contains("3.2 KB"))
        assertTrue(line.contains("1 个"))
    }

    @Test
    fun theSummaryLineSaysSoWhenNothingWasMeasured() {
        val line = LogSizes.summaryLine(0, 0, null, emptyList())
        assertTrue(line.contains("守护日志 未读到"))
        assertTrue(line.contains("APK 残留 0 个"))
    }
}
