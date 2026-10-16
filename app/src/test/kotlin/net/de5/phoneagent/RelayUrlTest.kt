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
     * has shipped three defaults and wrote each into storage on first run, so a
     * stored copy of *any* has to be treated as "not chosen by a human" and
     * moved to the current default.
     *
     * The newest entry matters most right now: `relay.2-c.cc` was the default as
     * recently as v0.6.1, so installs in the field are holding it. It is not a
     * blocked name, but it is a Cloudflare detour the operator has retired in
     * favour of the direct route.
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
    fun everyHistoricalDefaultIsStillListed() {
        assertTrue(Prefs.RETIRED_RELAY_BASES.contains("https://dsh.owen1.de5.net:19092"))
        assertTrue(Prefs.RETIRED_RELAY_BASES.contains("https://mcp.owen1.de5.net"))
        assertTrue(Prefs.RETIRED_RELAY_BASES.contains("https://relay.2-c.cc"))
    }

    /**
     * The address the carrier resets must never be the default again — that is
     * the whole reason the default changed three times.
     */
    @Test
    fun theCurrentDefaultIsNotARetiredOne() {
        assertEquals("https://dsh.2-c.cc:19092", Prefs.DEFAULT_RELAY_BASE)
        assertFalse(Prefs.RETIRED_RELAY_BASES.any { it.equals(Prefs.DEFAULT_RELAY_BASE, true) })
    }

    @Test
    fun aDeliberatelyTypedAddressIsKept() {
        val typed = "https://example.invalid:8443"
        assertEquals(typed, resolveRelayBase(typed))
        assertEquals(typed, resolveRelayBase("  $typed  "))
        // `https://relay.2-c.cc` used to be kept here. As of v0.7.0 it is itself a
        // retired default, so it migrates instead — covered above.
        assertEquals(Prefs.DEFAULT_RELAY_BASE, resolveRelayBase("https://relay.2-c.cc"))
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
