package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Blob names and the one-line JSON contract (SPEC.md section 4).
 *
 * The relay silently rejects a bad name with a 400 that is very hard to trace
 * back from the phone, so the accepted shape is pinned here instead.
 */
class ShotBlobTest {

    @Test
    fun aNameIsPrefixMillisAndSixCharacters() {
        assertEquals("shot-1700000000000-abc123", ShotBlob.name(1700000000000L, "abc123"))
    }

    @Test
    fun theSuffixIsSanitisedAndPadded() {
        // Upper case is lowered, separators are dropped, short input is padded.
        assertEquals("shot-5-abc000", ShotBlob.name(5L, "ABC"))
        assertEquals("shot-5-abcdef", ShotBlob.name(5L, "ab-cd_ef"))
        assertEquals("shot-5-000000", ShotBlob.name(5L, ""))
    }

    @Test
    fun aGeneratedNameMatchesTheRelayPattern() {
        val name = ShotBlob.name(1700000000000L, ShotBlob.randomSuffix(Random(7)))
        assertTrue(ShotBlob.isValidName(name))
        assertTrue(name.length <= ShotBlob.NAME_MAX_CHARS)
        assertTrue(name.startsWith("shot-1700000000000-"))
        assertEquals(6, name.substringAfterLast('-').length)
    }

    @Test
    fun theRelayNamePatternIsEnforced() {
        assertTrue(ShotBlob.isValidName("shot-1-abc123"))
        assertTrue(ShotBlob.isValidName("a.b_c-D9"))
        assertFalse(ShotBlob.isValidName(""))
        assertFalse(ShotBlob.isValidName("has space"))
        assertFalse(ShotBlob.isValidName("slash/inside"))
        assertFalse(ShotBlob.isValidName("x".repeat(ShotBlob.NAME_MAX_CHARS + 1)))
        assertTrue(ShotBlob.isValidName("x".repeat(ShotBlob.NAME_MAX_CHARS)))
    }

    @Test
    fun theShotJsonCarriesTheFieldsTheControllerReads() {
        assertEquals(
            "{\"name\":\"shot-1-abc123\",\"bytes\":4096,\"w\":720,\"h\":1600,\"q\":55," +
                "\"method\":\"screen\"}",
            ShotBlob.json("shot-1-abc123", 4096, 720, 1600, 55)
        )
    }

    @Test
    fun theShotJsonStaysFarBelowTheRelayLineBudget() {
        val line = ShotBlob.json(ShotBlob.name(Long.MAX_VALUE, "zzzzzz"), 16777216, 1440, 3200, 95)
        assertTrue(line.length < 300)
    }

    @Test
    fun theErrorShapeIsWhatTheControllerExpects() {
        assertEquals("{\"ok\":false,\"err\":\"boom\"}", ShotBlob.error("boom"))
        assertEquals(
            "{\"ok\":false,\"err\":\"say \\\"hi\\\"\"}",
            ShotBlob.error("say \"hi\"")
        )
        // A raw newline would break the one-line contract.
        assertFalse(ShotBlob.error("a\nb").contains("\n"))
    }

    @Test
    fun theBlobLimitIsSixteenMegabytes() {
        assertEquals(16L * 1024 * 1024, ShotBlob.MAX_BYTES)
    }
}
