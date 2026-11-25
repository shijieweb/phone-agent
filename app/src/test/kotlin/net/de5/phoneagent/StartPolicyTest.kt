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

    /**
     * v0.6.3-pad12：界面上的「开机自动启动」复选框删了，提示语必须跟着改。
     *
     * 钉住两件事：① 不能再提那个不存在的控件（不然妈妈会去找）；② 必须说清
     * 「重启要再点一次启动」——这正是老板要的行为，也是最容易被当成故障的一件事。
     */
    @Test
    fun theHintSpeaksAboutStartStopAndReboot() {
        assertTrue(StartPolicy.HINT.contains("启动"))
        assertTrue(StartPolicy.HINT.contains("停止"))
        assertTrue(StartPolicy.HINT.contains("重启"))
        assertFalse("提示语不能再指向已删除的复选框", StartPolicy.HINT.contains("开机自动启动"))
        assertFalse("提示语不该提勾选", StartPolicy.HINT.contains("勾选"))
    }

    /**
     * autoStart 标志仍然存在（只是界面上没有开关了），语义是「最后一次按的是启动
     * 还是停止」。这个用例钉住它不会被顺手删掉——onDestroy 收守护、打开界面拉服务
     * 都还看它。
     */
    @Test
    fun theFlagStillMeansWhichButtonWasPressedLast() {
        assertTrue(StartPolicy.onToggle(wasRunning = false).autoStart)
        assertFalse(StartPolicy.onToggle(wasRunning = true).autoStart)
        assertFalse(StartPolicy.onRemoteStop().autoStart)
    }
}
