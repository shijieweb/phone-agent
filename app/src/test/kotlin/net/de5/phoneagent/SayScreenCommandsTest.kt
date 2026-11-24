package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grammar half of §3.9 and §3.14: what the operator may type, and what a
 * half-typed line must refuse instead of guessing.
 */
class SayScreenCommandsTest {

    @Test
    fun sayTakesTheWholeRemainderVerbatim() {
        assertEquals(
            Command.Say("你好 世界", 100, 100, "zh"),
            Commands.parse("say 你好 世界")
        )
        // Inner spacing survives: the utterance is text, not a token list.
        assertEquals("a  b", (Commands.parse("say a  b") as Command.Say).text)
    }

    @Test
    fun sayAcceptsRateAndPitchBeforeTheText() {
        val c = Commands.parse("say rate=150 pitch=90 你好") as Command.Say
        assertEquals(150, c.rate)
        assertEquals(90, c.pitch)
        assertEquals("你好", c.text)
    }

    @Test
    fun sayClampsRateAndPitchRatherThanRejecting() {
        val low = Commands.parse("say rate=1 pitch=1 hi") as Command.Say
        assertEquals(SayPlan.RATE_MIN, low.rate)
        assertEquals(SayPlan.PITCH_MIN, low.pitch)
        val high = Commands.parse("say rate=9999 pitch=9999 hi") as Command.Say
        assertEquals(SayPlan.RATE_MAX, high.rate)
        assertEquals(SayPlan.PITCH_MAX, high.pitch)
    }

    @Test
    fun sayDefaultsToChineseWhenNoLanguageIsGiven() {
        assertEquals("zh", (Commands.parse("say hi") as Command.Say).lang)
    }

    @Test
    fun sayAcceptsAllThreeSpellingsOfTheLanguageFlag() {
        for (line in listOf("say -l en hi", "say --lang en hi", "say -l=en hi")) {
            assertEquals(line, "en", (Commands.parse(line) as Command.Say).lang)
            assertEquals(line, "hi", (Commands.parse(line) as Command.Say).text)
        }
        assertEquals("zh", (Commands.parse("say -l zh hi") as Command.Say).lang)
    }

    @Test
    fun sayRejectsAnUnknownLanguageInsteadOfFallingBack() {
        // A silent fallback would read English in a Chinese voice, which the
        // operator would hear as a broken phone rather than a wrong flag.
        assertTrue(Commands.parse("say -l fr hi") is Command.BadUsage)
        assertTrue(Commands.parse("say -l") is Command.BadUsage)
        assertTrue(Commands.parse("say") is Command.BadUsage)
        assertTrue(Commands.parse("say   ") is Command.BadUsage)
        assertTrue(Commands.parse("say rate=abc hi") is Command.BadUsage)
    }

    @Test
    fun screenOnAndOffTakeNothingElse() {
        assertEquals(Command.ScreenOn, Commands.parse("screen on"))
        assertEquals(Command.ScreenOff, Commands.parse("screen off"))
        assertTrue(Commands.parse("screen on now") is Command.BadUsage)
        assertTrue(Commands.parse("screen") is Command.BadUsage)
        assertTrue(Commands.parse("screen dim") is Command.BadUsage)
    }

    @Test
    fun bareStayOnIsTheReportForm() {
        // Null means "ask", not "off": the report must never be read as a write.
        assertEquals(Command.ScreenStayOn(null), Commands.parse("screen stayon"))
        assertEquals(Command.ScreenStayOn(true), Commands.parse("screen stayon on"))
        assertEquals(Command.ScreenStayOn(false), Commands.parse("screen stayon off"))
    }

    @Test
    fun stayOnAlsoAcceptsTheNumericAndWordForms() {
        for (line in listOf("screen stayon 1", "screen stayon true")) {
            assertEquals(line, Command.ScreenStayOn(true), Commands.parse(line))
        }
        for (line in listOf("screen stayon 0", "screen stayon false")) {
            assertEquals(line, Command.ScreenStayOn(false), Commands.parse(line))
        }
        // The dashed spelling is the one people type by reflex.
        assertEquals(Command.ScreenStayOn(true), Commands.parse("screen stay-on on"))
    }

    @Test
    fun stayOnRefusesGarbageAndExtraWords() {
        assertTrue(Commands.parse("screen stayon maybe") is Command.BadUsage)
        assertTrue(Commands.parse("screen stayon on now") is Command.BadUsage)
    }

    @Test
    fun timeoutNeedsExactlyOneNumberAndIsClampedAtParseTime() {
        assertEquals(Command.ScreenTimeout(60_000), Commands.parse("screen timeout 60000"))
        assertEquals(Command.ScreenTimeout(15_000), Commands.parse("screen timeout 1"))
        assertEquals(
            Command.ScreenTimeout(ScreenPlan.STAY_ON_TIMEOUT),
            Commands.parse("screen timeout 99999999999")
        )
        assertTrue(Commands.parse("screen timeout") is Command.BadUsage)
        assertTrue(Commands.parse("screen timeout abc") is Command.BadUsage)
        assertTrue(Commands.parse("screen timeout 60000 70000") is Command.BadUsage)
    }

    @Test
    fun flagsMustComeBeforeTheTextBecauseTheTextIsTheRemainder() {
        // "say hi -l en" is one utterance that happens to contain a flag; the
        // parser must not steal it, or the operator's sentence gets mangled.
        val c = Commands.parse("say hi -l en") as Command.Say
        assertEquals("hi -l en", c.text)
        assertEquals("zh", c.lang)
    }

    @Test
    fun aFlagWithoutAnyTextIsRefused() {
        assertTrue(Commands.parse("say rate=150") is Command.BadUsage)
        assertTrue(Commands.parse("say -l en rate=150") is Command.BadUsage)
    }

    @Test
    fun screenSubcommandsAreCaseInsensitive() {
        assertEquals(Command.ScreenOn, Commands.parse("SCREEN ON"))
        assertEquals(Command.ScreenStayOn(true), Commands.parse("Screen StayOn On"))
        assertEquals(Command.ScreenTimeout(60_000), Commands.parse("screen TIMEOUT 60000"))
    }

    @Test
    fun aNegativeTimeoutClampsToTheFloorButAFractionIsRefused() {
        // A negative number is a typo, not an instruction to switch the panel
        // off instantly: clamping keeps the phone usable either way.
        assertEquals(Command.ScreenTimeout(15_000), Commands.parse("screen timeout -5"))
        assertTrue(Commands.parse("screen timeout 1.5") is Command.BadUsage)
    }

    @Test
    fun theOldRecordCommandIsGone() {
        // m00177 removed it; a stale client sending `rec` must not be read as
        // some other verb, so it lands on Unknown like any other typo.
        assertTrue(Commands.parse("rec 5") is Command.Unknown)
    }
}
