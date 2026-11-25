package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 `tree` 到底读哪几个窗口（v0.6.0-pad12）。
 *
 * 真机上的缺陷是：窗口**没变**、只是窗口**内部**的布局变了（点了「高级设置」，
 * 三个输入框出现），`AccessibilityWindowInfo.getRoot()` 给的是缓存快照，读不到；
 * 切走再切回就对了。所以活动窗口必须走 `rootInActiveWindow` 这个会重读的门。
 *
 * 但 `rootInActiveWindow` 只回答一个窗口，而 `tree` 承诺所有窗口。于是两个来源
 * 要合并且不能重：同一个窗口出现两份，每个节点的匹配数都会翻倍，
 * `click apply <文字>` 就会对一个只存在一次的节点说「有歧义」。
 */
class WindowPickerTest {

    private fun stub(id: Int, active: Boolean = false, layer: Int = 0) =
        WindowPicker.WindowStub(id, active, layer)

    @Test
    fun onlyTheActiveWindowMeansOnlyOneRoot() {
        assertEquals(listOf(7), WindowPicker.merge(7, listOf(stub(7, active = true))))
    }

    @Test
    fun theImeWindowIsKeptAlongsideTheApp() {
        // 输入法窗口在最上层，键盘上的「发送」和 App 里的「发送」是两个节点，
        // 只留活动窗口会让 confirm 按错地方。
        val merged = WindowPicker.merge(7, listOf(stub(7, active = true), stub(9, layer = 1)))
        assertEquals(listOf(7, 9), merged)
    }

    @Test
    fun theActiveWindowIsNeverEmittedTwice() {
        // 这就是缓存快照那份拷贝：id 相同，必须只留 rootInActiveWindow 读来的那份。
        val merged = WindowPicker.merge(7, listOf(stub(7, active = true), stub(9, layer = 1)))
        assertEquals(1, merged.count { it == 7 })
    }

    @Test
    fun withoutAFreshRootEveryWindowIsKept() {
        // rootInActiveWindow 返回 null（锁屏、过渡动画）时不能因此丢掉整屏。
        val merged = WindowPicker.merge(null, listOf(stub(3, layer = 0), stub(4, active = true, layer = 2)))
        assertEquals(listOf(4, 3), merged)
    }

    @Test
    fun aNullRootAndNoWindowsIsAnEmptyAnswer() {
        assertEquals(emptyList<Int>(), WindowPicker.merge(null, emptyList()))
    }

    @Test
    fun theFreshRootLeadsEvenWhenAnotherWindowClaimsToBeActive() {
        // 框架有时把 isActive 标在别的窗口上（分屏、悬浮窗）。已经拿到的新鲜根优先，
        // 顺序不能因为一个标记就翻过来 —— 操作员看到的第一屏应该是活动窗口。
        val merged = WindowPicker.merge(7, listOf(stub(8, active = true, layer = 5), stub(7, layer = 1)))
        assertEquals(7, merged.first())
        assertEquals(listOf(7, 8), merged)
    }

    @Test
    fun upperWindowsComeBeforeLowerOnes() {
        // 对话框/键盘画在 App 之上，同名的节点先按上面那个算。
        val merged = WindowPicker.merge(1, listOf(stub(1, active = true), stub(2, layer = 0), stub(3, layer = 4)))
        assertEquals(listOf(1, 3, 2), merged)
    }

    @Test
    fun everyWindowAppearsAtMostOnce() {
        val merged = WindowPicker.merge(2, listOf(stub(2, active = true), stub(2), stub(5), stub(5, layer = 1)))
        assertEquals(merged.size, merged.toSet().size)
    }

    @Test
    fun theActiveIdIsReadFromTheFlagNotFromTheOrder() {
        val windows = listOf(stub(11), stub(12, active = true), stub(13))
        assertEquals(12, WindowPicker.activeId(windows))
        assertEquals(null, WindowPicker.activeId(listOf(stub(11), stub(13))))
    }

    @Test
    fun theResultIsStableForAGivenSetOfWindows() {
        // 框架列窗口的顺序不保证；同样的窗口集合必须给出同样的读法，
        // 否则 tree 两次跑出两种顺序，操作员会以为界面在动。
        val windows = listOf(stub(4, layer = 1), stub(9, layer = 3), stub(2, layer = 0))
        assertEquals(WindowPicker.merge(4, windows), WindowPicker.merge(4, windows.reversed()))
    }

    @Test
    fun aWindowListWithNoRootsStillKeepsTheFreshOne() {
        // windows 为空（部分 ROM 在过渡态返回空列表）时，新鲜的根照样要读。
        assertTrue(WindowPicker.merge(7, emptyList()).contains(7))
    }
}
