package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grammar of v0.4.2: `files find/df/pull`, `call speaker`, `a11y status`
 * (SPEC.md §3.7, §3.13, §3.15).
 */
class FilesCallA11yCommandsTest {

    @Test
    fun findTakesPathThenKeyword() {
        assertEquals(
            Command.FilesFind("/sdcard", "mp4", FindPlan.LIMIT_DEFAULT),
            Commands.parse("files find /sdcard mp4")
        )
    }

    @Test
    fun findTakesAnOptionalCountAtTheEnd() {
        assertEquals(
            Command.FilesFind("/sdcard/DCIM", "jpg", 10),
            Commands.parse("files find /sdcard/DCIM jpg 10")
        )
        // Clamped, not rejected: an over-large count is a slip, not a typo worth
        // refusing the whole command for.
        assertEquals(FindPlan.LIMIT_MAX, (Commands.parse("files find /sdcard jpg 9999") as Command.FilesFind).limit)
        assertEquals(1, (Commands.parse("files find /sdcard jpg 0") as Command.FilesFind).limit)
    }

    @Test
    fun findKeepsSpacesInThePath() {
        // "My Documents" is a real directory name; the keyword and the count come
        // off the end, so everything left is the path.
        assertEquals(
            Command.FilesFind("/sdcard/My Documents", "doc", 5),
            Commands.parse("files find /sdcard/My Documents doc 5")
        )
        assertEquals(
            Command.FilesFind("/sdcard/My Documents", "doc", FindPlan.LIMIT_DEFAULT),
            Commands.parse("files find /sdcard/My Documents doc")
        )
    }

    @Test
    fun findRefusesAHalfTypedLine() {
        assertTrue(Commands.parse("files find") is Command.BadUsage)
        assertTrue(Commands.parse("files find /sdcard") is Command.BadUsage)
    }

    @Test
    fun dfTakesNothing() {
        assertEquals(Command.FilesDf, Commands.parse("files df"))
        assertEquals(Command.FilesDf, Commands.parse("files DF"))
        assertTrue(Commands.parse("files df now") is Command.BadUsage)
    }

    @Test
    fun pullTakesOneVerbatimPath() {
        assertEquals(Command.FilesPull("/sdcard/a.mp4"), Commands.parse("files pull /sdcard/a.mp4"))
        assertEquals(
            Command.FilesPull("/sdcard/My Movies/a b.mp4"),
            Commands.parse("files pull /sdcard/My Movies/a b.mp4")
        )
        assertTrue(Commands.parse("files pull") is Command.BadUsage)
    }

    @Test
    fun theFilesUsageListsTheNewVerbs() {
        val text = (Commands.parse("files") as Command.BadUsage).detail
        assertTrue(text, text.contains("files find PATH KEYWORD [n]"))
        assertTrue(text, text.contains("files df"))
        assertTrue(text, text.contains("files pull PATH"))
    }

    @Test
    fun speakerWithoutAnArgumentIsAQuery() {
        assertEquals(Command.CallSpeaker(null), Commands.parse("call speaker"))
        assertEquals(Command.CallSpeaker(null), Commands.parse("call SPEAKER"))
    }

    @Test
    fun speakerAcceptsTheUsualSynonyms() {
        assertEquals(Command.CallSpeaker(true), Commands.parse("call speaker on"))
        assertEquals(Command.CallSpeaker(true), Commands.parse("call speaker 1"))
        assertEquals(Command.CallSpeaker(true), Commands.parse("call speaker TRUE"))
        assertEquals(Command.CallSpeaker(false), Commands.parse("call speaker off"))
        assertEquals(Command.CallSpeaker(false), Commands.parse("call speaker 0"))
        assertEquals(Command.CallSpeaker(false), Commands.parse("call speaker False"))
    }

    @Test
    fun speakerRefusesAnUnknownValueInsteadOfGuessing() {
        // Guessing "on" would route a call to the loudspeaker in a meeting.
        assertTrue(Commands.parse("call speaker maybe") is Command.BadUsage)
        assertTrue(Commands.parse("call speaker on now") is Command.BadUsage)
    }

    @Test
    fun aNumberIsStillANumberNotASpeakerVerb() {
        assertEquals(Command.CallDial("13800138000", false), Commands.parse("call 13800138000"))
        assertEquals(Command.CallAnswer, Commands.parse("call answer"))
        assertEquals(Command.CallState, Commands.parse("call state"))
    }

    @Test
    fun theCallUsageListsTheSpeakerVerb() {
        val text = (Commands.parse("call speaker on off") as Command.BadUsage).detail
        assertTrue(text, text.contains("call speaker [on|off]"))
    }

    @Test
    fun a11yStatusIsReadOnlyAndParses() {
        assertEquals(Command.A11yStatus, Commands.parse("a11y status"))
        assertEquals(Command.A11yStatus, Commands.parse("a11y STATUS"))
        assertEquals(Command.A11yHeal, Commands.parse("a11y heal"))
    }

    @Test
    fun a11yRefusesAnythingElse() {
        assertTrue(Commands.parse("a11y") is Command.BadUsage)
        assertTrue(Commands.parse("a11y on") is Command.BadUsage)
        assertTrue(Commands.parse("a11y heal now") is Command.BadUsage)
        assertEquals(
            A11yPlan.USAGE,
            (Commands.parse("a11y on") as Command.BadUsage).detail
        )
    }
}
