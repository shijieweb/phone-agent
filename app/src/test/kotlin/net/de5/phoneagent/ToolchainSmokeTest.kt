package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Toolchain smoke test: proves the JVM unit-test task runs on CI before the real
 * test suite (SPEC.md section 10) lands.
 */
class ToolchainSmokeTest {

    @Test
    fun joinsRelayPathsWithoutDoubleSlash() {
        assertEquals("https://x:1/agent/hello", joinUrl("https://x:1", "/agent/hello"))
        assertEquals("https://x:1/agent/hello", joinUrl("https://x:1/", "/agent/hello"))
        assertEquals("https://x:1/agent/hello", joinUrl("https://x:1///", "/agent/hello"))
    }

    @Test
    fun returnsNullOnBlankBase() {
        assertNull(joinUrlOrNull("  ", "/agent/hello"))
    }
}
