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

    /**
     * v0.5.6：`stop` 走 relay 命令这条路必须和按按钮等价。
     *
     * v0.5.5 的漏洞就是这条没测：Executor 只 stopSelf() 不清 autoStart，
     * 守护 60 秒后把服务拉回来，直接违反「停止就彻底停止」。
     */
    @Test
    fun remoteStopClearsAutoStartJustLikeTheButton() {
        val remote = StartPolicy.onRemoteStop()
        val button = StartPolicy.onToggle(wasRunning = true)
        assertFalse(remote.start)
        assertFalse(remote.autoStart)
        assertEquals(button, remote)
    }

    @Test
    fun remoteStopLetsTheWatchdogDisarm() {
        // onDestroy 用 shouldDisarm(autoStart) 决定要不要收守护：清掉才收。
        assertTrue(WatchdogPlan.shouldDisarm(StartPolicy.onRemoteStop().autoStart))
        assertFalse(WatchdogPlan.shouldDisarm(StartPolicy.onToggle(wasRunning = false).autoStart))
    }

    @Test
    fun remoteStopKeepsTheUiFromRevivingTheAgent() {
        assertFalse(
            StartPolicy.shouldStartOnOpen(
                configured = true,
                autoStart = StartPolicy.onRemoteStop().autoStart,
                running = false
            )
        )
    }

    @Test
    fun theHintMentionsBothDirections() {
        assertTrue(StartPolicy.HINT.contains("停止后不会自动启动"))
        assertTrue(StartPolicy.HINT.contains("开机自动启动"))
    }
}
