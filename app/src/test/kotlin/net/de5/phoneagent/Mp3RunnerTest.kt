package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量循环的判据（v0.7.0-pad12）。
 *
 * 只测「不碰解码器」的那部分：真转要真容器，普通 JVM 上没有 MediaExtractor，
 * 编码内核由 [LameMp3EncoderTest] 单独验证。这里钉的是最容易出错、也最伤人的
 * 一层——到底转谁、跳过谁、force 算不算已有 mp3。这条判据有三个人在看
 * （dry-run 文案、批量循环、界面上的待转数），所以它只有一份定义
 * （[Mp3Plan.work] / [Mp3Plan.skippedTotal] / [Mp3Plan.skippedExisting]），可以不开解码器就验。
 */
class Mp3RunnerTest {

    private fun row(name: String, hasAudio: Boolean, targetExists: Boolean) =
        Mp3Plan.Row(
            name = name,
            bytes = 1_000L,
            durationMs = 60_000L,
            hasAudio = hasAudio,
            targetExists = targetExists
        )

    @Test
    fun noAudioIsNeverWork() {
        val rows = listOf(row("silent.mp4", hasAudio = false, targetExists = false))
        assertTrue(Mp3Plan.work(rows, force = false).isEmpty())
        assertTrue("force 也不能让无音轨的文件凭空变出音轨", Mp3Plan.work(rows, force = true).isEmpty())
    }

    @Test
    fun anExistingTargetIsSkippedUnlessForced() {
        val rows = listOf(row("done.mp4", hasAudio = true, targetExists = true))
        assertTrue(Mp3Plan.work(rows, force = false).isEmpty())
        assertEquals(1, Mp3Plan.work(rows, force = true).size)
    }

    @Test
    fun forceMakesExistingTargetsCountAsSkippedOnlyForNoAudio() {
        // force 下「已有 mp3」不再是跳过理由，只剩无音轨的算跳过。
        // 这条必须钉死：force 的整个意义就是让那些文件从跳过变成待转。
        val rows = listOf(
            row("a.mp4", hasAudio = true, targetExists = true),
            row("b.mp4", hasAudio = false, targetExists = false)
        )
        // 汇总行口径：只数「因为已有 mp3 才跳过」的，无音轨另有自己的一栏。
        assertEquals("不加 force：已有 mp3 的那个算跳过", 1, Mp3Plan.skippedExisting(rows, force = false))
        assertEquals("加 force：它变成待转，跳过就只剩 0", 0, Mp3Plan.skippedExisting(rows, force = true))
        assertEquals("无音轨单独计数，任何时候都不算跳过", 1, Mp3Plan.noAudioCount(rows))
        // dry-run 口径：只有「将转 / 跳过」两栏，所以无音轨也必须算进跳过，
        // 否则两数相加不等于文件总数，读的人会以为少了一个。
        assertEquals("dry-run 口径把无音轨也算进跳过", 2, Mp3Plan.skippedTotal(rows, force = false))
        assertEquals("force 下只剩无音轨那一项", 1, Mp3Plan.skippedTotal(rows, force = true))
    }

    @Test
    fun aFreshFolderHasNothingToSkip() {
        val rows = listOf(
            row("a.mp4", hasAudio = true, targetExists = false),
            row("b.mkv", hasAudio = true, targetExists = false)
        )
        assertEquals(2, Mp3Plan.work(rows, force = false).size)
        assertEquals(0, Mp3Plan.skippedExisting(rows, force = false))
    }

    @Test
    fun nothingToDoReturnsWithoutTouchingFiles() {
        val rows = listOf(
            row("a.mp4", hasAudio = false, targetExists = false),
            row("b.mp4", hasAudio = true, targetExists = true)
        )
        val outcome = Mp3Runner.run("/sdcard/nowhere", rows, force = false)
        assertEquals(0, outcome.done)
        assertEquals(0, outcome.failed)
        assertEquals(1, outcome.skipped)
        assertEquals(1, outcome.noAudio)
        assertTrue("一个文件都不转时不该产生任何输出行", outcome.lines.isEmpty())
    }

    @Test
    fun emptyFolderIsQuietlyEmpty() {
        val outcome = Mp3Runner.run("/sdcard/nowhere", emptyList(), force = false)
        assertEquals(0, outcome.done)
        assertEquals(0, outcome.failed)
        assertEquals(0, outcome.skipped)
        assertEquals(0, outcome.noAudio)
        assertTrue(outcome.lines.isEmpty())
    }
}
