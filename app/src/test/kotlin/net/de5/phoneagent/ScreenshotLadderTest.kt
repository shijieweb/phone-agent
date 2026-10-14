package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC.md section 10 case 2: the "re-encode smaller" ladder of section 4. */
class ScreenshotLadderTest {

    @Test
    fun firstAttemptUsesTheRequestedParameters() {
        val attempt = ShotLadder.initial(720, 55)
        assertEquals(720, attempt.maxPx)
        assertEquals(55, attempt.quality)
        assertEquals(0, attempt.step)
        assertTrue(attempt.note.contains("maxPx=720"))
        assertTrue(attempt.note.contains("q=55"))
    }

    @Test
    fun noRetryWhenTheImageFits() {
        assertNull(ShotLadder.next(ShotLadder.initial(720, 55), 100_000))
    }

    @Test
    fun noRetryAtTheExactLimit() {
        assertNull(ShotLadder.next(ShotLadder.initial(720, 55), ShotLadder.MAX_BASE64_CHARS))
    }

    @Test
    fun firstRetryHalvesMaxPx() {
        val next = ShotLadder.next(ShotLadder.initial(720, 55), ShotLadder.MAX_BASE64_CHARS + 1)
        assertTrue(next != null)
        assertEquals(360, next!!.maxPx)
        assertEquals(55, next.quality)
        assertEquals(1, next.step)
        assertTrue(next.note.contains("maxPx=360"))
    }

    @Test
    fun firstRetryNeverGoesBelowTheMinimumMaxPx() {
        val next = ShotLadder.next(ShotLadder.initial(320, 55), 200_000)
        assertTrue(next != null)
        assertEquals(320, next!!.maxPx)
        assertEquals(1, next.step)
    }

    @Test
    fun secondRetryDropsQualityToThirtyFive() {
        val next = ShotLadder.next(ShotAttempt(360, 55, 1, ""), 200_000)
        assertTrue(next != null)
        assertEquals(360, next!!.maxPx)
        assertEquals(35, next.quality)
        assertEquals(2, next.step)
        assertTrue(next.note.contains("q=35"))
    }

    @Test
    fun secondRetryKeepsAQualityThatIsAlreadyLower() {
        val next = ShotLadder.next(ShotAttempt(360, 20, 1, ""), 200_000)
        assertTrue(next != null)
        assertEquals(20, next!!.quality)
        assertEquals(2, next.step)
    }

    @Test
    fun theLadderGivesUpAfterTwoRetries() {
        assertNull(ShotLadder.next(ShotAttempt(360, 35, 2, ""), 200_000))
    }

    @Test
    fun theLimitIsOneHundredAndEightyFiveThousandChars() {
        assertEquals(185_000, ShotLadder.MAX_BASE64_CHARS)
    }
}
