package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `files pull` (SPEC.md §3.7): the blob name must survive the relay's
 * ^[A-Za-z0-9_.-]{1,80}$ rule, and the size limit is refused before any read.
 */
class PullPlanTest {

    @Test
    fun theOriginalExtensionIsKeptLowerCased() {
        assertEquals("mp4", PullPlan.extensionOf("/sdcard/Movies/A.MP4"))
        assertEquals("gz", PullPlan.extensionOf("/sdcard/backup.tar.gz"))
        assertEquals("jpg", PullPlan.extensionOf("a.jpg"))
    }

    @Test
    fun aNameWithoutAUsableExtensionGetsNone() {
        assertNull(PullPlan.extensionOf("/sdcard/noext"))
        assertNull(PullPlan.extensionOf("/sdcard/.hidden"))
        assertNull(PullPlan.extensionOf("/sdcard/trailing."))
        assertNull(PullPlan.extensionOf("/sdcard/a.p-n-g"))
        assertNull(PullPlan.extensionOf("/sdcard/a.verylongext"))
    }

    @Test
    fun theBlobNameIsAFilePrefixPlusTimePlusSuffix() {
        assertEquals("file-1000-abc123.mp4", PullPlan.name(1000L, "abc123", "mp4"))
        assertEquals("file-1000-abc123", PullPlan.name(1000L, "abc123", null))
    }

    @Test
    fun aDirtySuffixIsSanitisedRatherThanRejected() {
        assertEquals("file-1000-abc000", PullPlan.name(1000L, "abc", null))
        assertEquals("file-1000-ab0000", PullPlan.name(1000L, "ab!@#", null))
        assertTrue(ShotBlob.isValidName(PullPlan.name(1000L, "Z Z Z", "mp4")))
    }

    @Test
    fun theGeneratedNameIsAlwaysAcceptableToTheRelay() {
        for (ext in listOf(null, "mp4", "gz", "json")) {
            val name = PullPlan.name(System.currentTimeMillis(), ShotBlob.randomSuffix(), ext)
            assertTrue(name, ShotBlob.isValidName(name))
        }
    }

    @Test
    fun theAnswerIsOneJsonLine() {
        assertEquals(
            "{\"name\":\"file-1-a.mp4\",\"bytes\":42,\"path\":\"/sdcard/a.mp4\"}",
            PullPlan.json("file-1-a.mp4", 42, "/sdcard/a.mp4")
        )
    }

    @Test
    fun aPathWithAQuoteCannotBreakTheJson() {
        val text = PullPlan.json("file-1-a", 1, "/sdcard/a\"b")
        assertTrue(text, text.contains("\\\""))
        assertTrue(text, text.endsWith("\"}"))
    }

    @Test
    fun anOversizedFileNamesTheLimit() {
        val text = PullPlan.tooLarge(ShotBlob.MAX_BYTES + 1L)
        assertTrue(text, text.contains("文件太大"))
        assertTrue(text, text.contains("16.0 MB"))
    }

    @Test
    fun anUploadFailureKeepsTheErrorPrefix() {
        assertTrue(PullPlan.failure("timeout").startsWith(Admin.ERROR_PREFIX))
        assertTrue(PullPlan.failure("timeout").contains("上传失败: timeout"))
    }
}
