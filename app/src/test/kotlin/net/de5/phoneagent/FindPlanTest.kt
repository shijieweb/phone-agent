package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `files find` (SPEC.md §3.7): what a hit looks like, where the list stops, and
 * which trees are never entered.
 */
class FindPlanTest {

    @Test
    fun keywordIsACaseInsensitiveSubstring() {
        assertTrue(FindPlan.matches("Movie.MP4", "mp4"))
        assertTrue(FindPlan.matches("mp4", "MP4"))
        assertTrue(FindPlan.matches("holiday-2024.jpg", "2024"))
        assertFalse(FindPlan.matches("notes.txt", "mp4"))
    }

    @Test
    fun anEmptyKeywordMatchesNothing() {
        // Otherwise every file on the card would be a "hit", which is a listing
        // pretending to be a search.
        assertFalse(FindPlan.matches("anything.mp4", ""))
    }

    @Test
    fun limitIsClampedNotRejected() {
        assertEquals(1, FindPlan.clampLimit(0))
        assertEquals(1, FindPlan.clampLimit(-7))
        assertEquals(FindPlan.LIMIT_MAX, FindPlan.clampLimit(9999))
        assertEquals(FindPlan.LIMIT_DEFAULT, FindPlan.LIMIT_DEFAULT)
    }

    @Test
    fun theTwoUnreadableTreesAreSkipped() {
        assertTrue(FindPlan.skipped("/sdcard/Android/data"))
        assertTrue(FindPlan.skipped("/sdcard/Android/data/com.tencent.mm/files"))
        assertTrue(FindPlan.skipped("/sdcard/Android/obb"))
        // Every accepted spelling of the root behaves the same.
        assertTrue(FindPlan.skipped("/storage/emulated/0/Android/data"))
        assertFalse(FindPlan.skipped("/sdcard/DCIM"))
        // "data" as a plain directory name is not the same thing.
        assertFalse(FindPlan.skipped("/sdcard/Download/data"))
    }

    @Test
    fun aFileRowCarriesTypeSizeAndFullPath() {
        val row = FindPlan.row(FindPlan.Hit("/sdcard/Movies/a.mp4", false, 2048L))
        assertTrue(row, row.startsWith("- "))
        assertTrue(row, row.contains("2.0 KB"))
        assertTrue(row, row.endsWith(" /sdcard/Movies/a.mp4"))
    }

    @Test
    fun aDirectoryRowHasNoSize() {
        val row = FindPlan.row(FindPlan.Hit("/sdcard/Movies", true, 0L))
        assertTrue(row, row.startsWith("d "))
        assertTrue(row, row.contains(" - "))
        assertTrue(row, row.endsWith(" /sdcard/Movies"))
    }

    @Test
    fun hitsAreOrderedByPathSoTheAnswerIsStable() {
        val ordered = FindPlan.order(
            listOf(
                FindPlan.Hit("/sdcard/b.mp4", false, 1L),
                FindPlan.Hit("/sdcard/A.mp4", false, 1L),
                FindPlan.Hit("/sdcard/c.mp4", false, 1L)
            )
        )
        assertEquals(
            listOf("/sdcard/A.mp4", "/sdcard/b.mp4", "/sdcard/c.mp4"),
            ordered.map { it.path }
        )
    }

    @Test
    fun theHeaderStatesHowManyWereFound() {
        val text = FindPlan.report(
            "/sdcard",
            "mp4",
            listOf(
                FindPlan.Hit("/sdcard/a.mp4", false, 10L),
                FindPlan.Hit("/sdcard/b.mp4", false, 20L)
            ),
            FindPlan.LIMIT_DEFAULT
        )
        assertTrue(text, text.startsWith("查找 /sdcard 关键字=mp4 命中 2 项"))
        assertTrue(text, text.contains("/sdcard/a.mp4"))
    }

    @Test
    fun aTruncatedAnswerSaysHowManyWereWithheld() {
        val hits = (1..5).map { FindPlan.Hit("/sdcard/f" + it + ".mp4", false, 1L) }
        val text = FindPlan.report("/sdcard", "mp4", hits, 2)
        assertTrue(text, text.contains("命中 5 项"))
        assertTrue(text, text.contains("… 还有 3 项未列出"))
    }

    @Test
    fun aCappedScanAdmitsItIsIncomplete() {
        val text = FindPlan.report("/sdcard", "mp4", emptyList(), 50, scanCapped = true)
        assertTrue(text, text.contains("（已达到扫描上限，结果可能不完整）"))
    }

    @Test
    fun noHitsIsAnAnswerNotAnError() {
        assertEquals(
            "查找 /sdcard 关键字=mp4 命中 0 项",
            FindPlan.empty("/sdcard", "mp4")
        )
    }
}
