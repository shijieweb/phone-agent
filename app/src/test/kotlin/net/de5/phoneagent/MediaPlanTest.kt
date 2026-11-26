package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `media` (v0.8.0-pad12, SPEC.md §3.22): five actions, five keycodes, no apply. */
class MediaPlanTest {

    @Test
    fun theKeycodesAreTheOnesVerifiedOnThePhone() {
        assertEquals(126, MediaPlan.keyCode("play"))
        assertEquals(127, MediaPlan.keyCode("pause"))
        assertEquals(87, MediaPlan.keyCode("next"))
        assertEquals(88, MediaPlan.keyCode("prev"))
        assertEquals(86, MediaPlan.keyCode("stop"))
    }

    @Test
    fun actionsAreCaseInsensitive() {
        assertEquals(87, MediaPlan.keyCode("NEXT"))
        assertEquals(88, MediaPlan.keyCode("Prev"))
    }

    @Test
    fun anUnknownActionHasNoKeycode() {
        assertNull(MediaPlan.keyCode("rewind"))
        assertNull(MediaPlan.keyCode(""))
        assertEquals(listOf("play", "pause", "next", "prev", "stop"), MediaPlan.actions())
    }

    @Test
    fun noArgumentsIsAUsageLine() {
        assertTrue(FeatMedia.parse("") is Parsed.Bad)
        assertTrue(FeatMedia.parse("   ") is Parsed.Bad)
        val bad = FeatMedia.parse("volume-up") as Parsed.Bad
        assertEquals(MediaPlan.USAGE, bad.detail)
    }

    @Test
    fun theShellPathReportsTheKeycodeItPressed() {
        assertEquals("media next keyevent=87", MediaPlan.shellLine("next", 87))
    }

    @Test
    fun theFallbackLineSaysItIsNotGuaranteed() {
        val line = MediaPlan.audioLine("play")
        assertTrue(line.startsWith("media play via=audiomanager"))
        assertTrue(line.contains("不保证"))
    }

    @Test
    fun aRemoteFailureIsReportedAsAnErrorNotAsSuccess() {
        val line = MediaPlan.failedLine("play", 1, "  Permission denied ")
        assertTrue(line.startsWith(Admin.ERROR_PREFIX))
        assertTrue(line.contains("Permission denied"))
    }

    @Test
    fun mediaIsNotPartOfTheGlobalKeyNames() {
        // Mixing media keys into `key` would change its semantics; they stay apart,
        // so `key play` must stay a usage error rather than quietly becoming media.
        assertTrue(Commands.parse("key play") is Command.BadUsage)
        assertTrue(Commands.parse("key next") is Command.BadUsage)
        assertEquals(Command.Key("back"), Commands.parse("key back"))
    }
}
