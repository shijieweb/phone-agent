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

    /**
     * v0.5.5：界面被打开时的自启。四组真值表全覆盖，因为这里是
     * 「老板点过停止就是真停」的最后一道门。
     */
    @Test
    fun openingTheUiStartsOnlyWhenConfiguredAndArmedAndStopped() {
        assertTrue(StartPolicy.shouldStartOnOpen(configured = true, autoStart = true, running = false))
        assertFalse(StartPolicy.shouldStartOnOpen(configured = false, autoStart = true, running = false))
        assertFalse(StartPolicy.shouldStartOnOpen(configured = true, autoStart = false, running = false))
        assertFalse(StartPolicy.shouldStartOnOpen(configured = true, autoStart = true, running = true))
    }

    @Test
    fun openingTheUiNeverRevivesAStoppedAgent() {
        // 点过停止之后 autoStart 是 false，界面被谁打开都不许复活。
        assertFalse(StartPolicy.shouldStartOnOpen(configured = true, autoStart = false, running = false))
    }

    @Test
    fun theHintMentionsBothDirections() {
        assertTrue(StartPolicy.HINT.contains("停止后不会自动启动"))
        assertTrue(StartPolicy.HINT.contains("开机自动启动"))
    }
}
