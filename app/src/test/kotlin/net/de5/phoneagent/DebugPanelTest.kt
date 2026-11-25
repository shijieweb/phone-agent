package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug screen's two pieces of real logic, pinned on a plain JVM.
 *
 * [TapGate] is a door: if it opens one press early the debug screen shows up on
 * a stray tap, and if it measures the window with the wrong clock it never opens
 * and the operator is locked out of their own tool. [DebugPanel] decides what a
 * command's answer looks like — a 190 000-char stdout must not reach a TextView,
 * and a shortened answer must say that it was shortened.
 */
class DebugPanelTest {

    @Test
    fun fivePressesInsideTheWindowOpenTheDoor() {
        val gate = TapGate(5, 3_000L)
        assertFalse(gate.tap(1_000L))
        assertFalse(gate.tap(1_500L))
        assertFalse(gate.tap(2_000L))
        assertFalse(gate.tap(2_500L))
        assertTrue("the fifth press inside 3 s is the door", gate.tap(3_000L))
    }

    @Test
    fun pressesSpreadBeyondTheWindowNeverAddUp() {
        val gate = TapGate(5, 3_000L)
        assertFalse(gate.tap(0L))
        assertFalse(gate.tap(1_000L))
        assertFalse(gate.tap(2_000L))
        assertFalse(gate.tap(3_500L))
        assertFalse("the press at 0 has aged out, 1 000 is still inside", gate.tap(4_000L))
        assertFalse(gate.tap(5_000L))
        assertFalse("3 500 / 4 000 / 5 000 / 6 000 — still only four inside", gate.tap(6_000L))
        assertTrue("3 500 falls out of the 3 s window at 6 500", gate.tap(6_500L))
    }

    @Test
    fun theWindowIsInclusiveAtItsEdge() {
        val gate = TapGate(2, 3_000L)
        assertFalse(gate.tap(0L))
        assertTrue("exactly 3 000 ms apart still counts", gate.tap(3_000L))
    }

    @Test
    fun aFiredGateDoesNotLeavePressesPrimed() {
        val gate = TapGate(3, 3_000L)
        gate.tap(0L)
        gate.tap(100L)
        assertTrue(gate.tap(200L))
        assertEquals("the sequence is consumed", 0, gate.count())
        assertFalse("one press after the door opened is not a door", gate.tap(300L))
    }

    @Test
    fun resetForgetsEverything() {
        val gate = TapGate(3, 3_000L)
        gate.tap(0L)
        gate.tap(100L)
        gate.reset()
        assertEquals(0, gate.count())
        assertFalse(gate.tap(200L))
    }

    @Test
    fun aShortAnswerIsShownWhole() {
        val text = "exit=0\nok"
        assertEquals(text, DebugPanel.clip(text, 2000))
        assertFalse(DebugPanel.clip(text, 2000).contains(DebugPanel.TRUNCATED_MARK))
    }

    @Test
    fun aLongAnswerIsCutAndSaysSo() {
        val text = "x".repeat(5_000)
        val shown = DebugPanel.clip(text, 2000)
        assertTrue(shown.contains(DebugPanel.TRUNCATED_MARK))
        assertTrue("the real size is reported", shown.contains("5000"))
        assertTrue("it is no longer a 5 000-char string", shown.length < 2_100)
        assertTrue("the answer itself is still first", shown.startsWith("xxxx"))
    }

    @Test
    fun theCutNeverSplitsASurrogatePair() {
        // 1 999 plain chars, then an emoji whose two halves straddle the limit.
        val text = "a".repeat(1_999) + "\uD83D\uDE00" + "tail"
        val shown = DebugPanel.clip(text, 2_000)
        assertTrue(shown.contains(DebugPanel.TRUNCATED_MARK))
        val kept = shown.substringBefore('\n')
        assertEquals(1_999, kept.length)
        assertFalse(
            "a lone high surrogate renders as a replacement box",
            Character.isHighSurrogate(kept[kept.length - 1])
        )
    }

    @Test
    fun theExitCodeIsAlwaysTheFirstLine() {
        assertEquals("exit=0\nok", DebugPanel.formatResult(0, "ok", ""))
    }

    @Test
    fun stderrIsKeptApartFromStdout() {
        val shown = DebugPanel.formatResult(3, "", "accessibility service not connected")
        assertTrue(shown.startsWith("exit=3\n"))
        assertTrue(shown.contains("(无 stdout)"))
        assertTrue(shown.contains("--- stderr ---"))
        assertTrue(shown.contains("accessibility service not connected"))
    }

    @Test
    fun aHugeResultIsClippedByThePanelToo() {
        val shown = DebugPanel.formatResult(0, "y".repeat(190_000), "")
        assertTrue(shown.contains(DebugPanel.TRUNCATED_MARK))
        assertTrue(shown.length < 2_200)
    }

    @Test
    fun theNotRunningTextTellsTheOperatorWhatToDo() {
        assertTrue(DebugPanel.NOT_RUNNING.contains("服务未运行"))
        assertTrue(DebugPanel.NOT_RUNNING.contains("配置页"))
    }
}
