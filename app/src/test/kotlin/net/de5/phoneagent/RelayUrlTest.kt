package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
     * Upgrading must not keep dialling an address the carrier resets. This app
     * has shipped two defaults and wrote each into storage on first run, so a
     * stored copy of *either* has to be treated as "not chosen by a human" and
     * moved to the current default.
     *
     * The second one matters most right now: `mcp.owen1.de5.net` was the default
     * as recently as v0.6.0, so every install in the field is holding it, and it
     * is one of the names probe 10 proved gets reset.
     */
    @Test
    fun everyRetiredDefaultMigratesToTheCurrentOne() {
        for (retired in Prefs.RETIRED_RELAY_BASES) {
            assertEquals(
                "retired default $retired must migrate",
                Prefs.DEFAULT_RELAY_BASE,
                resolveRelayBase(retired),
            )
            assertEquals(Prefs.DEFAULT_RELAY_BASE, resolveRelayBase("  $retired  "))
            assertEquals(Prefs.DEFAULT_RELAY_BASE, resolveRelayBase("$retired/"))
            assertEquals(Prefs.DEFAULT_RELAY_BASE, resolveRelayBase("$retired///"))
            assertEquals(Prefs.DEFAULT_RELAY_BASE, resolveRelayBase(retired.uppercase()))
        }
        assertEquals(Prefs.DEFAULT_RELAY_BASE, resolveRelayBase(null))
        assertEquals(Prefs.DEFAULT_RELAY_BASE, resolveRelayBase(""))
        assertEquals(Prefs.DEFAULT_RELAY_BASE, resolveRelayBase("   "))
    }

    /** The list is the contract; losing an entry strands the installs holding it. */
    @Test
    fun bothHistoricalDefaultsAreStillListed() {
        assertTrue(Prefs.RETIRED_RELAY_BASES.contains("https://dsh.owen1.de5.net:19092"))
        assertTrue(Prefs.RETIRED_RELAY_BASES.contains("https://mcp.owen1.de5.net"))
    }

    /**
     * The address the carrier resets must never be the default again — that is
     * the whole reason the default changed twice.
     */
    @Test
    fun theCurrentDefaultIsNotARetiredOne() {
        assertEquals("https://relay.2-c.cc", Prefs.DEFAULT_RELAY_BASE)
        assertFalse(Prefs.RETIRED_RELAY_BASES.any { it.equals(Prefs.DEFAULT_RELAY_BASE, true) })
    }

    @Test
    fun aDeliberatelyTypedAddressIsKept() {
        val typed = "https://example.invalid:8443"
        assertEquals(typed, resolveRelayBase(typed))
        assertEquals(typed, resolveRelayBase("  $typed  "))
        assertEquals("https://relay.2-c.cc", resolveRelayBase("https://relay.2-c.cc"))
    }

    /**
     * Forgiving the retired defaults must not turn into forgiving everything: an
     * address that merely resembles one is still the operator's choice.
     */
    @Test
    fun anAddressThatMerelyResemblesARetiredDefaultIsKept() {
        val otherPort = "https://dsh.owen1.de5.net:19093"
        val otherPath = "${Prefs.RETIRED_RELAY_BASES[0]}/relay"
        val otherHost = "https://mcp.owen1.de5.net.evil.example"
        assertEquals(otherPort, resolveRelayBase(otherPort))
        assertEquals(otherPath, resolveRelayBase(otherPath))
        assertEquals(otherHost, resolveRelayBase(otherHost))
    }
}
