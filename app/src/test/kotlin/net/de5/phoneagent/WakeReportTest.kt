package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reporting contract of `wake` (SPEC.md section 3.2).
 *
 * The layer-by-layer behaviour needs a real `PowerManager`, but the *output*
 * shape — which is what the operator actually reads — is pure string building
 * and is pinned down here.
 */
class WakeReportTest {

    @Test
    fun alreadyOnIsReportedWithoutTheOkToken() {
        assertEquals(
            "wake already-on scr=on kg=none",
            Wake.reportLine("already-on", screenOn = true, keyguard = "none")
        )
    }

    @Test
    fun aWakeLockWinNamesTheLayer() {
        assertEquals(
            "wake ok via=wakelock scr=on kg=none",
            Wake.reportLine("wakelock", screenOn = true, keyguard = "none")
        )
    }

    @Test
    fun anActivityWinNamesTheLayer() {
        assertEquals(
            "wake ok via=activity scr=on kg=locked",
            Wake.reportLine("activity", screenOn = true, keyguard = "locked")
        )
    }

    @Test
    fun aSecureKeyguardIsStillASuccessWhenTheScreenCameOn() {
        // SPEC: a secure keyguard can never be cleared silently; report it and
        // keep exit=0 as long as the screen is on.
        val line = Wake.reportLine("activity", screenOn = true, keyguard = "secure")
        assertEquals("wake ok via=activity scr=on kg=secure", line)
        assertTrue(line.contains("kg=secure"))
    }

    @Test
    fun theFailureLineIsExact() {
        val result = Wake.failureLine("locked")
        assertEquals(EXIT_TIMEOUT, result.exit)
        assertEquals("wake failed via=none scr=off kg=locked", result.stdout)
    }

    @Test
    fun aFailedWakeKeepsItsKeyguardState() {
        assertEquals("wake failed via=none scr=off kg=secure", Wake.failureLine("secure").stdout)
        assertEquals("wake failed via=none scr=off kg=none", Wake.failureLine("none").stdout)
    }

    @Test
    fun everySuccessfulLayerUsesTheSameShape() {
        for (via in listOf("wakelock", "activity")) {
            val line = Wake.reportLine(via, screenOn = true, keyguard = "none")
            assertTrue("$via line was $line", line.startsWith("wake ok via=$via "))
            assertTrue(line.contains("scr=on"))
            assertTrue(line.contains("kg="))
        }
    }

    @Test
    fun wakeNeverThrowsForAnUnusableContext() {
        // The spec is explicit: being blocked by background-start limits is a
        // reported outcome. With a null-ish system service the call must still
        // return a result rather than propagate.
        val result = Wake.wakeForTesting(
            power = null,
            keyguard = "none",
            screenStaysOff = true,
            startActivity = { false }
        )
        assertEquals(EXIT_TIMEOUT, result.exit)
        assertEquals("wake failed via=none scr=off kg=none", result.stdout)
    }

    @Test
    fun anAlreadyOnScreenShortCircuitsEveryLayer() {
        val result = Wake.wakeForTesting(
            power = null,
            keyguard = "none",
            screenStaysOff = false,
            startActivity = { error("the activity must not be started") }
        )
        assertEquals(EXIT_OK, result.exit)
        assertEquals("wake already-on scr=on kg=none", result.stdout)
    }

    @Test
    fun aBlockedActivityStartIsReportedNotThrown() {
        // The activity refuses to start and the screen never comes on: this is
        // the "ROM blocked us" case, which must be a clean exit=4.
        var starts = 0
        val result = Wake.wakeForTesting(
            power = null,
            keyguard = "locked",
            screenStaysOff = true,
            startActivity = { starts++; false }
        )
        assertEquals(EXIT_TIMEOUT, result.exit)
        assertTrue(result.stdout.contains("via=none"))
        assertTrue("the activity layer was attempted", starts >= 1)
    }
}
