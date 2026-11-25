package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 `tree` 的「读两次、取第二次」规则（v0.6.0-pad12）。
 *
 * 这是根因修复的自愈部分：即使某个 ROM 的新鲜根还慢一拍，第二次读也能把刚落地
 * 的布局拿到。代价是一次 150ms 的等待，所以规则必须只有一条 —— 第二次读到了就
 * 用第二次，读不到（超时）就绝不能把本来好好的第一次读变成超时。
 */
class TreeSettleTest {

    @Test
    fun aChangedSecondReadWins() {
        assertEquals("after", TreeSettle.pick("before", "after"))
    }

    @Test
    fun anIdenticalSecondReadChangesNothing() {
        assertEquals("same", TreeSettle.pick("same", "same"))
    }

    @Test
    fun aTimedOutSecondReadKeepsTheFirstAnswer() {
        // 第二次超时只是没读到更新的，不是整条命令失败。
        assertEquals("first", TreeSettle.pick("first", null))
    }

    @Test
    fun anEmptyFirstReadStillYieldsToARealSecondOne() {
        // 页面刚起来时第一次可能是空的；第二次读到了就必须用第二次，
        // 否则「打开设置 → tree 是空」会看起来像无障碍没连上。
        assertEquals("0|android.widget.TextView|设置|", TreeSettle.pick("", "0|android.widget.TextView|设置|"))
    }

    @Test
    fun theRetryBudgetStaysSmall() {
        // tree 是操作员一边点一边跑的命令，等待必须是一次性的小数目。
        assertTrue("重取间隔必须 ≤ 500ms，否则 tree 变慢命令", TreeSettle.SETTLE_MS <= 500L)
        assertTrue("必须真的等一会儿，0 等于没重取", TreeSettle.SETTLE_MS > 0L)
    }
}
