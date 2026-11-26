package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `autowake` (v0.8.0-pad12, SPEC.md §3.27).
 *
 * The decision table is the whole feature: wake when the screen is off, refuse
 * outright when the lock screen has a password (running on and reporting "node
 * not found" would look like a broken selector).
 */
class AutowakePlanTest {

    @Test
    fun theSwitchDefaultsToOffAndOnlyTakesOnOrOff() {
        assertEquals("autowake on", AutowakePlan.line(true))
        assertEquals("autowake off", AutowakePlan.line(false))
        assertEquals("usage: autowake [on|off]", AutowakePlan.USAGE)
        assertEquals(AutowakePlan.USAGE, (FeatAutowake.parse("yes") as Parsed.Bad).detail)
        assertEquals("on", (FeatAutowake.parse("ON") as Parsed.Ok).rest)
        assertEquals("off", (FeatAutowake.parse(" off ") as Parsed.Ok).rest)
        assertEquals("", (FeatAutowake.parse("") as Parsed.Ok).rest)
    }

    @Test
    fun theDecisionTableIsExhaustive() {
        // autowake off: never touch the screen.
        assertEquals(AutowakePlan.SKIP, AutowakePlan.decide(false, false, AutowakePlan.KEYGUARD_NONE))
        assertEquals(AutowakePlan.SKIP, AutowakePlan.decide(false, false, AutowakePlan.KEYGUARD_SECURE))
        // Screen already on: nothing to do.
        assertEquals(AutowakePlan.SKIP, AutowakePlan.decide(true, true, AutowakePlan.KEYGUARD_NONE))
        assertEquals(AutowakePlan.SKIP, AutowakePlan.decide(true, true, AutowakePlan.KEYGUARD_LOCKED))
        // A password lock is refused even before trying to wake.
        assertEquals(AutowakePlan.REFUSE, AutowakePlan.decide(true, false, AutowakePlan.KEYGUARD_SECURE))
        assertEquals(AutowakePlan.REFUSE, AutowakePlan.decide(true, true, AutowakePlan.KEYGUARD_SECURE))
        // Screen off, no password: wake it.
        assertEquals(AutowakePlan.WAKE, AutowakePlan.decide(true, false, AutowakePlan.KEYGUARD_NONE))
        assertEquals(AutowakePlan.WAKE, AutowakePlan.decide(true, false, AutowakePlan.KEYGUARD_LOCKED))
    }

    @Test
    fun theRefusalSaysWhatToDoInsteadOfJustFailing() {
        val text = AutowakePlan.refusalText()
        assertEquals(true, text.contains("锁屏"))
        assertEquals(true, text.contains("解锁"))
    }

    @Test
    fun theNoteIsTheSameWakePrefixTheWakeCommandUses() {
        assertEquals("wake=via=wakelock", AutowakePlan.note("wakelock"))
        assertEquals("wake=via=already-on", AutowakePlan.note("already-on"))
    }

    @Test
    fun theViaIsReadFromBothWakeReceiptShapes() {
        assertEquals("already-on", AutowakePlan.viaOf("wake already-on scr=on kg=none"))
        assertEquals("wakelock", AutowakePlan.viaOf("wake ok via=wakelock scr=on kg=none"))
        assertEquals("activity", AutowakePlan.viaOf("wake ok via=activity scr=on kg=locked"))
        assertEquals("unknown", AutowakePlan.viaOf("something else entirely"))
        assertEquals("wakelock", AutowakePlan.viaOf("  wake ok via=wakelock scr=on kg=none  "))
    }

    @Test
    fun theKeyguardWordsAreTheOnesWakeReports() {
        assertEquals("none", AutowakePlan.KEYGUARD_NONE)
        assertEquals("locked", AutowakePlan.KEYGUARD_LOCKED)
        assertEquals("secure", AutowakePlan.KEYGUARD_SECURE)
    }

    @Test
    fun theGuardTypesCarryEitherARefusalOrANote() {
        assertEquals(null, Autowake.NONE.refusal)
        assertEquals(null, Autowake.NONE.note)
        val guard = Autowake.Guard(ExecutionResult(EXIT_PERMISSION, "", "x"), null)
        assertEquals(EXIT_PERMISSION, guard.refusal!!.exit)
        assertEquals(null, guard.note)
    }
}
