package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The start/stop button and the boot receiver must agree on when the agent may
 * come back by itself; these tests pin both halves (SPEC.md section 7).
 */
class StartPolicyTest {

    @Test
    fun stoppingAlsoClearsAutoStart() {
        val decision = StartPolicy.onToggle(wasRunning = true)
        assertFalse(decision.start)
        assertFalse(decision.autoStart)
    }

    @Test
    fun startingAlsoArmsAutoStart() {
        val decision = StartPolicy.onToggle(wasRunning = false)
        assertTrue(decision.start)
        assertTrue(decision.autoStart)
    }

    @Test
    fun autoStartNeedsBothFlagAndConfiguration() {
        assertTrue(StartPolicy.shouldAutoStart(autoStart = true, configured = true))
        assertFalse(StartPolicy.shouldAutoStart(autoStart = false, configured = true))
        assertFalse(StartPolicy.shouldAutoStart(autoStart = true, configured = false))
        assertFalse(StartPolicy.shouldAutoStart(autoStart = false, configured = false))
    }

    @Test
    fun theHintMentionsBothDirections() {
        assertTrue(StartPolicy.HINT.contains("停止后不会自动启动"))
        assertTrue(StartPolicy.HINT.contains("开机自动启动"))
    }
}
