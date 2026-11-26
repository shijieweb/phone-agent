package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Notes on a receipt (v0.8.0-pad12).
 *
 * A note must never be glued onto a JSON receipt: `status` and `where` are parsed
 * by the controller, and one trailing sentence turns them into garbage.
 */
class FeatureNotesTest {

    @Test
    fun aJsonReceiptKeepsItsStdoutAndPutsTheNoteOnStderr() {
        val (stdout, stderr) = FeatureNotes.attach("{\"ok\":true}", "", listOf("已自动恢复显示设置（超时）"))
        assertEquals("{\"ok\":true}", stdout)
        assertEquals("已自动恢复显示设置（超时）", stderr)
    }

    @Test
    fun aPlainReceiptGetsTheNoteAppendedWithOneSpace() {
        val (stdout, stderr) = FeatureNotes.attach("tap 10 20", "", listOf("wake=via=wakelock"))
        assertEquals("tap 10 20 wake=via=wakelock", stdout)
        assertEquals("", stderr)
    }

    @Test
    fun anEmptyStdoutSendsTheNoteToStderr() {
        val (stdout, stderr) = FeatureNotes.attach("", "boom", listOf("note"))
        assertEquals("", stdout)
        assertEquals("boom note", stderr)
    }

    @Test
    fun noNotesMeansNoChange() {
        assertEquals(Pair("out", "err"), FeatureNotes.attach("out", "err", emptyList()))
    }

    @Test
    fun jsonDetectionOnlyLooksAtTheFirstCharacter() {
        assertEquals(true, FeatureNotes.jsonLike("  {\"a\":1}"))
        assertEquals(true, FeatureNotes.jsonLike("{}"))
        assertEquals(false, FeatureNotes.jsonLike("tap 1 2"))
        assertEquals(false, FeatureNotes.jsonLike(""))
    }
}
