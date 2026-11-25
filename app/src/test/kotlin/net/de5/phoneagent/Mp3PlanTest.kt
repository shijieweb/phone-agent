package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「视频转音乐」的纯逻辑（v0.7.0-pad12）。
 *
 * 这些用例挡的是「扫错文件」「覆盖了不该覆盖的东西」「dry-run 说得不清楚」
 * 三类错误——它们都不会崩，只会静默做错事，所以在 JVM 上钉死。
 */
class Mp3PlanTest {

    private fun row(
        name: String,
        hasAudio: Boolean = true,
        targetExists: Boolean = false,
        bytes: Long = 1024L,
        durationMs: Long = 61_000L
    ) = Mp3Plan.Row(name, bytes, durationMs, hasAudio, targetExists)

    @Test
    fun onlyTheTenVideoExtensionsAreAccepted() {
        for (ext in listOf("mp4", "mov", "mkv", "avi", "flv", "m4v", "3gp", "webm", "ts", "mpg")) {
            assertTrue(ext + " 必须被认成视频", Mp3Plan.isVideoName("片子." + ext))
            assertTrue("大写后缀也要认", Mp3Plan.isVideoName("片子." + ext.uppercase()))
        }
        for (name in listOf("歌.mp3", "录音.m4a", "无后缀", ".mp4", "片子.", "片子.mp4.part")) {
            assertFalse(name + " 不许被认成视频", Mp3Plan.isVideoName(name))
        }
    }

    @Test
    fun theTargetNameSwapsOnlyTheLastExtension() {
        assertEquals("生日.mp3", Mp3Plan.targetName("生日.mp4"))
        assertEquals("a.b.mp3", Mp3Plan.targetName("a.b.mkv"))
        assertEquals("生日.mp3", Mp3Plan.targetName("生日"))
    }

    @Test
    fun durationIsReadableAndNeverLiesAboutUnknown() {
        assertEquals("?", Mp3Plan.duration(0L))
        assertEquals("?", Mp3Plan.duration(-5L))
        assertEquals("0:03", Mp3Plan.duration(3_400L))
        assertEquals("3:01", Mp3Plan.duration(181_000L))
        assertEquals("1:02:03", Mp3Plan.duration(3_723_000L))
    }

    @Test
    fun secondsAreAlwaysOneDecimal() {
        assertEquals("0.0s", Mp3Plan.seconds(0L))
        assertEquals("0.0s", Mp3Plan.seconds(-100L))
        assertEquals("3.5s", Mp3Plan.seconds(3_500L))
        assertEquals("12.0s", Mp3Plan.seconds(12_000L))
    }

    @Test
    fun percentIsClampedTo0Through100() {
        assertEquals(0, Mp3Plan.percent(0L, 100L))
        assertEquals(0, Mp3Plan.percent(5L, 0L))
        assertEquals(50, Mp3Plan.percent(5L, 10L))
        assertEquals(100, Mp3Plan.percent(10L, 10L))
        assertEquals(100, Mp3Plan.percent(99L, 10L))
    }

    @Test
    fun theScanReportCountsEachKindOnceAndListsEveryFile() {
        val rows = listOf(
            row("a.mp4"),
            row("b.mov", targetExists = true),
            row("c.mkv", hasAudio = false)
        )
        val text = Mp3Plan.scanReport("/sdcard/视频转音乐专用", rows)
        assertTrue(text.startsWith("目录 /sdcard/视频转音乐专用"))
        assertTrue("三个数必须都在同一行", text.contains("视频 3 个：待转 1，已有 mp3 1，无音轨 1"))
        assertTrue(text.contains("1. a.mp4"))
        assertTrue(text.contains("跳过（已有 mp3）"))
        assertTrue(text.contains("跳过（无音轨）"))
        assertEquals(1, Mp3Plan.pendingCount(rows))
        assertEquals(1, Mp3Plan.existingCount(rows))
        assertEquals(1, Mp3Plan.noAudioCount(rows))
    }

    @Test
    fun anEmptyDirectorySaysWhichExtensionsItLooksFor() {
        val text = Mp3Plan.scanReport("/sdcard/视频转音乐专用", emptyList())
        assertTrue(text.contains("没有视频文件"))
        assertTrue(text.contains("mp4"))
        assertTrue(text.contains("webm"))
    }

    @Test
    fun theDryRunLineSaysHowManyWillBeConvertedAndHowManySkipped() {
        val rows = listOf(
            row("a.mp4"),
            row("b.mp4"),
            row("c.mp4", targetExists = true),
            row("d.mp4", hasAudio = false)
        )
        assertEquals(
            "dry-run: 将转 2 个，跳过 2 个；加 apply 真正开始",
            Mp3Plan.dryRunLine(rows, force = false)
        )
        // force 只救「已有 mp3」，救不了「没有音轨」
        assertEquals(
            "dry-run: 将转 3 个，跳过 1 个（force: 已有 mp3 也重转）；加 apply 真正开始",
            Mp3Plan.dryRunLine(rows, force = true)
        )
    }

    @Test
    fun theSummaryLineCarriesAllFourCounts() {
        assertEquals(
            "完成 2 个，失败 1 个，跳过 1 个，无音轨 1 个，用时 7.5s",
            Mp3Plan.summaryLine(done = 2, failed = 1, skipped = 1, noAudio = 1, elapsedMs = 7_500L)
        )
    }

    @Test
    fun aFailureKeepsTheErrorPrefixSoTheExecutorCanMapIt() {
        val text = Mp3Plan.failure("磁盘满了")
        assertTrue(text.startsWith(Admin.ERROR_PREFIX))
        assertTrue(text.contains("磁盘满了"))
    }

    @Test
    fun theProgressAndResultLinesNameTheFileAndTheNumbers() {
        assertEquals("[2/5] 生日.mp4 …", Mp3Plan.startLine(2, 5, "生日.mp4"))
        assertEquals("[2/5] 生日.mp4 40%", Mp3Plan.progressLine(2, 5, "生日.mp4", 40))
        assertEquals("[2/5] 生日.mp4 0%", Mp3Plan.progressLine(2, 5, "生日.mp4", -3))
        assertTrue(Mp3Plan.resultLine(2, 5, "生日.mp4", 4_200_000L, 3_500L).contains("生日.mp3"))
        assertTrue(Mp3Plan.failedLine(2, 5, "生日.mp4", "解不出来").contains("失败: 解不出来"))
    }

    @Test
    fun theUsageLineNamesBothVerbs() {
        assertTrue(Mp3Plan.USAGE.contains("mp3 scan"))
        assertTrue(Mp3Plan.USAGE.contains("mp3 run"))
        assertTrue(Mp3Plan.USAGE.contains("apply"))
    }
}
