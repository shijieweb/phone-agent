package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SPEC.md section 10 case 4: joining the three relay paths. */
class RelayUrlTest {

    private val base = "https://dsh.owen1.de5.net:19092"

    @Test
    fun threeRelayPathsJoinCleanly() {
        assertEquals("$base/agent/hello", joinUrl(base, "/agent/hello"))
        assertEquals("$base/agent/poll", joinUrl(base, "/agent/poll"))
        assertEquals("$base/agent/result", joinUrl(base, "/agent/result"))
    }

    @Test
    fun aTrailingSlashNeverProducesADoubleSlash() {
        assertEquals("$base/agent/hello", joinUrl("$base/", "/agent/hello"))
        assertEquals("$base/agent/poll", joinUrl("$base//", "/agent/poll"))
        assertEquals("$base/agent/result", joinUrl("$base///", "/agent/result"))
    }

    @Test
    fun aPathWithoutALeadingSlashIsStillJoined() {
        assertEquals("$base/agent/hello", joinUrl(base, "agent/hello"))
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertEquals("$base/agent/hello", joinUrl("  $base  ", "  /agent/hello  "))
    }

    @Test
    fun aMissingBaseYieldsNull() {
        assertNull(joinUrlOrNull(null, "/agent/hello"))
        assertNull(joinUrlOrNull("", "/agent/hello"))
        assertNull(joinUrlOrNull("   ", "/agent/hello"))
        assertNull(joinUrlOrNull(base, ""))
        assertNull(joinUrlOrNull(base, "   "))
    }

    /**
     * Upgrading must not keep dialling the address the carrier resets: the app
     * used to write that default into storage, so a stored copy of it has to be
     * treated as "not chosen by a human" and moved to the current default.
     */
    @Test
    fun theLegacyDefaultMigratesToTheCurrentOne() {
        assertEquals(Prefs.DEFAULT_RELAY_BASE, resolveRelayBase(Prefs.LEGACY_RELAY_BASE))
        assertEquals(Prefs.DEFAULT_RELAY_BASE, resolveRelayBase("  ${Prefs.LEGACY_RELAY_BASE}  "))
        assertEquals(Prefs.DEFAULT_RELAY_BASE, resolveRelayBase(null))
        assertEquals(Prefs.DEFAULT_RELAY_BASE, resolveRelayBase(""))
        assertEquals(Prefs.DEFAULT_RELAY_BASE, resolveRelayBase("   "))
    }

    /**
     * A hand-typed URL picks up trailing slashes and case variations, and an
     * exact-match migration would leave the operator stranded on the address
     * that never connects. Both must still be recognised as the legacy default.
     */
    @Test
    fun theLegacyDefaultIsRecognisedDespiteTrailingSlashesOrCase() {
        assertEquals(Prefs.DEFAULT_RELAY_BASE, resolveRelayBase("${Prefs.LEGACY_RELAY_BASE}/"))
        assertEquals(Prefs.DEFAULT_RELAY_BASE, resolveRelayBase("${Prefs.LEGACY_RELAY_BASE}///"))
        assertEquals(Prefs.DEFAULT_RELAY_BASE, resolveRelayBase("  ${Prefs.LEGACY_RELAY_BASE}/  "))
        assertEquals(
            Prefs.DEFAULT_RELAY_BASE,
            resolveRelayBase(Prefs.LEGACY_RELAY_BASE.uppercase()),
        )
    }

    @Test
    fun aDeliberatelyTypedAddressIsKept() {
        val typed = "https://example.invalid:8443"
        assertEquals(typed, resolveRelayBase(typed))
        assertEquals(typed, resolveRelayBase("  $typed  "))
    }

    /**
     * Forgiving the legacy default must not turn into forgiving everything: an
     * address that merely resembles it is still the operator's choice.
     */
    @Test
    fun anAddressThatMerelyResemblesTheLegacyDefaultIsKept() {
        val otherPort = "https://dsh.owen1.de5.net:19093"
        val otherPath = "${Prefs.LEGACY_RELAY_BASE}/relay"
        assertEquals(otherPort, resolveRelayBase(otherPort))
        assertEquals(otherPath, resolveRelayBase(otherPath))
    }
}
