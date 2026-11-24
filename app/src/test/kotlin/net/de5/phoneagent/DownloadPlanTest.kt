package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Name cleaning is a security boundary, not cosmetics: whatever survives here is
 * turned into a file inside /sdcard/Movies, and nothing else may ever be
 * reachable (SPEC.md section 3.6).
 */
class DownloadPlanTest {

    @Test
    fun traversalCannotEscapeTheDirectory() {
        assertEquals("passwd", DownloadPlan.sanitizeName("../../etc/passwd"))
        assertEquals("film.mp4", DownloadPlan.sanitizeName("a/../../film.mp4"))
        assertEquals("film.mp4", DownloadPlan.sanitizeName("..\\..\\film.mp4"))
    }

    @Test
    fun separatorsAndControlCharactersBecomeUnderscores() {
        val cleaned = DownloadPlan.sanitizeName("a\u0001b\u0007c.mp4")
        assertEquals("a_b_c.mp4", cleaned)
        assertEquals("a_b.mp4", DownloadPlan.sanitizeName("a:b.mp4"))
        assertEquals("my_video_file.mp4", DownloadPlan.sanitizeName("my video file.mp4"))
        // Every space becomes its own underscore: no collapsing magic, so the
        // result is predictable from the input alone.
        assertEquals("my_video___file.mp4", DownloadPlan.sanitizeName("my video   file.mp4"))
    }

    @Test
    fun blankOrHopelessNamesAreRejected() {
        assertNull(DownloadPlan.sanitizeName(null))
        assertNull(DownloadPlan.sanitizeName(""))
        assertNull(DownloadPlan.sanitizeName("   "))
        assertNull(DownloadPlan.sanitizeName("..."))
        assertNull(DownloadPlan.sanitizeName("/"))
    }

    @Test
    fun longNamesKeepTheirExtension() {
        val long = "x".repeat(300) + ".mp4"
        val cleaned = DownloadPlan.sanitizeName(long)!!
        assertEquals(DownloadPlan.NAME_MAX_CHARS, cleaned.length)
        assertTrue(cleaned.endsWith(".mp4"))
    }

    @Test
    fun aLeadingDotIsStrippedSoGalleriesSeeTheFile() {
        assertEquals("film.mp4", DownloadPlan.sanitizeName(".film.mp4"))
        assertEquals("film.mp4", DownloadPlan.sanitizeName("-film.mp4"))
    }

    @Test
    fun theUrlNameIsTheLastPathSegment() {
        assertEquals(
            "episode.mp4",
            DownloadPlan.guessName("https://cdn.example.com/shows/s01/episode.mp4", 1L)
        )
    }

    @Test
    fun queryStringsDoNotBecomePartOfTheName() {
        assertEquals(
            "episode.mp4",
            DownloadPlan.guessName("https://cdn.example.com/episode.mp4?X-Sig=a/b&e=1", 1L)
        )
    }

    @Test
    fun aUrlWithoutAFileNameFallsBackToATimestamp() {
        assertEquals("download-1700000000000.mp4", DownloadPlan.guessName("https://cdn.example.com", 1700000000000L))
        assertEquals("download-7.mp4", DownloadPlan.guessName("https://cdn.example.com/stream/", 7L))
        assertEquals("download-7.mp4", DownloadPlan.guessName("https://cdn.example.com/watch?v=1", 7L))
    }

    @Test
    fun anExplicitNameWinsOverTheUrl() {
        assertEquals("mama.mp4", DownloadPlan.resolveName("https://x/y/other.mkv", "mama.mp4", 1L))
    }

    @Test
    fun anUnusableExplicitNameFallsBackToTheUrl() {
        assertEquals("real.mp4", DownloadPlan.resolveName("https://x/real.mp4", "  ", 1L))
        assertEquals("real.mp4", DownloadPlan.resolveName("https://x/real.mp4", "...", 1L))
    }

    @Test
    fun theLimitTripsOnlyAboveTwoGigabytes() {
        assertFalse(DownloadPlan.overLimit(0L))
        assertFalse(DownloadPlan.overLimit(DownloadPlan.MAX_BYTES))
        assertTrue(DownloadPlan.overLimit(DownloadPlan.MAX_BYTES + 1))
        assertTrue(DownloadPlan.overLimit(3L * 1024 * 1024 * 1024))
    }

    @Test
    fun mediaExtensionsAreRecognised() {
        assertTrue(DownloadPlan.isMedia("a.mp4"))
        assertTrue(DownloadPlan.isMedia("a.MKV"))
        assertTrue(DownloadPlan.isMedia("a.mp3"))
        assertFalse(DownloadPlan.isMedia("a.apk"))
        assertFalse(DownloadPlan.isMedia("noextension"))
    }

    @Test
    fun thePathIsAlwaysInsideMovies() {
        assertEquals("/sdcard/Movies/film.mp4", DownloadPlan.publicPath("film.mp4"))
    }

    @Test
    fun successNamesTheFileItsSizeAndThePath() {
        assertEquals(
            "已下载 film.mp4 1234 字节 → /sdcard/Movies/film.mp4",
            DownloadPlan.success("film.mp4", 1234L)
        )
    }

    @Test
    fun failureCarriesBothTheReasonAndWhatLanded() {
        val text = DownloadPlan.failure(DownloadPlan.httpFailure(404), 4096L)
        assertTrue(text.startsWith(Admin.ERROR_PREFIX))
        assertTrue(text.contains("下载失败: HTTP 404"))
        assertTrue(text.contains("已写 4096 字节"))
    }

    @Test
    fun everyFailureSentenceIsDistinct() {
        assertTrue(DownloadPlan.httpFailure(500).contains("500"))
        assertTrue(DownloadPlan.sizeFailure().contains("2048MB"))
        assertTrue(DownloadPlan.timeoutFailure(600).contains("600"))
        assertEquals(3, setOf(
            DownloadPlan.httpFailure(500),
            DownloadPlan.sizeFailure(),
            DownloadPlan.timeoutFailure(600)
        ).size)
    }
}
