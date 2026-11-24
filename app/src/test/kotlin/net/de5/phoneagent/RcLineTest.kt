package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "#rc:" built-in action protocol (SPEC.md section 4.2).
 *
 * The controller parses our answer, so every branch that can produce a wrong
 * answer -- unknown verb, malformed JSON, out-of-range numbers -- is pinned here.
 */
class RcLineTest {

    @Test
    fun aShotLineWithoutPayloadUsesTheDocumentedDefaults() {
        assertEquals(RcLine.Shot(720, 55), parseRcLine("#rc: shot"))
        assertEquals(RcLine.Shot(720, 55), parseRcLine("#rc: shot   "))
    }

    @Test
    fun theControllerPayloadSetsWidthAndQuality() {
        assertEquals(
            RcLine.Shot(1280, 72),
            parseRcLine("#rc: shot {\"width\":1280,\"quality\":72}")
        )
    }

    @Test
    fun widthAndQualityAreClampedToTheProtocolWindow() {
        // 10 is a legitimate "make it tiny" request, unlike the human shot window.
        assertEquals(RcLine.Shot(320, 10), parseRcLine("#rc: shot {\"width\":100,\"quality\":1}"))
        assertEquals(
            RcLine.Shot(1440, 95),
            parseRcLine("#rc: shot {\"width\":4000,\"quality\":100}")
        )
    }

    @Test
    fun aMissingFieldFallsBackToItsDefault() {
        assertEquals(RcLine.Shot(1280, 55), parseRcLine("#rc: shot {\"width\":1280}"))
        assertEquals(RcLine.Shot(720, 30), parseRcLine("#rc: shot {\"quality\":30}"))
    }

    @Test
    fun windowHandleAndRectAreIgnoredSilently() {
        assertEquals(
            RcLine.Shot(1080, 60),
            parseRcLine("#rc: shot {\"hwnd\":1234,\"rect\":[0,0,10,10],\"width\":1080,\"quality\":60}")
        )
    }

    @Test
    fun anUnknownVerbIsReportedNeverExecuted() {
        assertEquals(RcLine.Unknown("wins"), parseRcLine("#rc: wins"))
        assertEquals(RcLine.Unknown("uia"), parseRcLine("#rc: uia {\"depth\":3}"))
        assertEquals(RcLine.Unknown("act"), parseRcLine("#rc: act click"))
    }

    @Test
    fun aLineWithoutAVerbIsBad() {
        val bad = parseRcLine("#rc:")
        assertTrue(bad is RcLine.Bad)
        assertTrue(parseRcLine("#rc:    ") is RcLine.Bad)
    }

    @Test
    fun aMalformedPayloadIsBadRatherThanSilentlyDefaulted() {
        // Truncated on the wire: the braces no longer balance.
        assertTrue(parseRcLine("#rc: shot {") is RcLine.Bad)
        assertTrue(parseRcLine("#rc: shot {\"width\":1280,\"quality\":") is RcLine.Bad)
        assertTrue(parseRcLine("#rc: shot not-json") is RcLine.Bad)
        // A JSON array is not the object the protocol sends.
        assertTrue(parseRcLine("#rc: shot [1,2,3]") is RcLine.Bad)
    }

    @Test
    fun aFieldWithoutANumberFallsBackToItsDefault() {
        // Only the *shape* is validated (no parser on the phone), so a value-less
        // field is read as "absent" rather than rejected.
        assertEquals(RcLine.Shot(720, 55), parseRcLine("#rc: shot {\"width\":}"))
    }

    @Test
    fun aQuotedNumberIsNotAccepted() {
        // The controller sends numbers; a string means the payload is not ours.
        assertEquals(RcLine.Shot(720, 55), parseRcLine("#rc: shot {\"width\":\"1280\"}"))
    }

    @Test
    fun onlyThePrefixMakesALineABuiltInAction() {
        assertTrue(isRcLine("#rc: shot"))
        assertTrue(isRcLine("   #rc: shot"))
        assertFalse(isRcLine("shot"))
        assertFalse(isRcLine("#rcc: shot"))
        assertFalse(isRcLine(""))
    }
}
