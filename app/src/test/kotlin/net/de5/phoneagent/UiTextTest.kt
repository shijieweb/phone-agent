package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 可点文案的契约（v0.7.3-pad12）。
 *
 * 操作员在真机上靠 `click apply <文字>` 点，而匹配是**子串包含**（ElementActions
 * 的 labelMatches）。所以两个文案相等会点错，一个是另一个的子串也一样会点错——
 * 「刷新」这种短词尤其危险：只要哪天有个按钮叫「刷新统计」，`click apply 刷新`
 * 就不知道落在谁身上。这两条约束在这里一次钉死，加新文案时不用靠记性。
 */
class UiTextTest {

    /** 八个可点文案两两不相等。 */
    @Test
    fun noTwoClickableLabelsAreTheSame() {
        assertEquals(UiText.clickable.size, UiText.clickable.toSet().size)
    }

    /** 任何一个都不是另一个的子串，顺序无关。 */
    @Test
    fun noClickableLabelIsASubstringOfAnother() {
        val labels = UiText.clickable
        for (i in labels.indices) {
            for (j in labels.indices) {
                if (i == j) continue
                assertFalse(
                    "「" + labels[j] + "」是「" + labels[i] + "」的子串，click apply 会点错",
                    labels[i].contains(labels[j])
                )
            }
        }
    }

    /**
     * v0.7.3-pad12 新加的「刷新」单独再钉一遍。
     *
     * 上面那条循环已经把这件事覆盖了，但这条用例存在的意义是**名字**：
     * 将来有人把「刷新」改成别的词，或者把某个按钮改叫「刷新」什么，
     * 红的是这条，一眼知道撞的是哪个功能。
     */
    @Test
    fun refreshIsItsOwnLabel() {
        assertEquals("「刷新」只能出现一次", 1, UiText.clickable.count { it == UiText.REFRESH })
        for (other in UiText.clickable) {
            if (other == UiText.REFRESH) continue
            assertFalse(
                "「刷新」和「" + other + "」互相包含",
                UiText.REFRESH.contains(other) || other.contains(UiText.REFRESH)
            )
        }
    }

    /** 一共九个，改动数量必须是有意的（这条数字变了就该顺手想想是不是漏了文档）。 */
    @Test
    fun thereAreEightClickableLabels() {
        assertEquals(9, UiText.clickable.size)
    }
}
