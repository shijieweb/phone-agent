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
}
