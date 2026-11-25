package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置页那个「一键设置权限」按钮的判定顺序（v0.5.8-pad12）。
 *
 * 它把原来四个按钮（请求权限 / 打开无障碍设置 / 加入电池白名单 / 打开电池优化
 * 设置）收成一个：每按一次只做「下一件还没做的事」。顺序不是随便排的——
 * 定位必须排最前，因为后台定位是两段式询问（先一般定位，再「始终允许」），
 * 中间被别的设置页打断，第二次询问就再也不会来，用户永远拿不到后台定位。
 * 这几条用例钉的就是这个顺序，以及「文字必须各不一样」。
 */
class PermPlanTest {

    @Test
    fun everythingMissingStartsWithRuntimePermissions() {
        assertEquals(
            PermPlan.Step.RUNTIME,
            PermPlan.next(runtimeGranted = false, accessibilityLive = false, batteryExempt = false)
        )
    }

    @Test
    fun accessibilityComesAfterRuntime() {
        assertEquals(
            PermPlan.Step.ACCESSIBILITY,
            PermPlan.next(runtimeGranted = true, accessibilityLive = false, batteryExempt = false)
        )
    }

    @Test
    fun batteryComesAfterAccessibility() {
        assertEquals(
            PermPlan.Step.BATTERY,
            PermPlan.next(runtimeGranted = true, accessibilityLive = true, batteryExempt = false)
        )
    }

    @Test
    fun everythingGrantedIsDone() {
        assertEquals(
            PermPlan.Step.DONE,
            PermPlan.next(runtimeGranted = true, accessibilityLive = true, batteryExempt = true)
        )
    }

    /**
     * 电池白名单先满足也不能跳步：定位没给就还是定位。跳步会让「再按一次」
     * 落在用户还没准备好的那一步上。
     */
    @Test
    fun aLaterStepNeverSkipsAnEarlierOne() {
        assertEquals(
            PermPlan.Step.RUNTIME,
            PermPlan.next(runtimeGranted = false, accessibilityLive = true, batteryExempt = true)
        )
        assertEquals(
            PermPlan.Step.ACCESSIBILITY,
            PermPlan.next(runtimeGranted = true, accessibilityLive = false, batteryExempt = true)
        )
    }

    @Test
    fun everyStepExplainsWhatIsStillMissing() {
        val hints = PermPlan.Step.values().map { PermPlan.hint(it) }
        assertEquals(PermPlan.Step.values().size, hints.toSet().size)
        assertTrue(PermPlan.hint(PermPlan.Step.RUNTIME).contains("定位"))
        assertTrue(PermPlan.hint(PermPlan.Step.ACCESSIBILITY).contains("无障碍"))
        assertTrue(PermPlan.hint(PermPlan.Step.BATTERY).contains("电池"))
        assertEquals("权限已齐全", PermPlan.hint(PermPlan.Step.DONE))
    }

    @Test
    fun everyPressedLineIsDistinctFromTheHintItReplaces() {
        // DONE 是唯一例外，也应该是：全齐时按一下什么都没发生，提示不该变。
        val actionable = PermPlan.Step.values().filter { it != PermPlan.Step.DONE }
        assertEquals(3, actionable.size)
        for (step in actionable) {
            assertNotEquals(
                "按下的回执不能和缺项提示同一句话，否则看不出按钮有没有生效",
                PermPlan.hint(step),
                PermPlan.pressed(step)
            )
        }
    }

    /** 全齐时按下去只回一句同样的话——没东西可做，就不该假装做了。 */
    @Test
    fun pressingWhenNothingIsMissingChangesNothing() {
        assertEquals(PermPlan.hint(PermPlan.Step.DONE), PermPlan.pressed(PermPlan.Step.DONE))
    }

    /** 真机上靠 click apply <文字> 点，两个控件同名就点错。 */
    @Test
    fun noTwoClickableLabelsAreTheSame() {
        assertEquals(UiText.clickable.size, UiText.clickable.toSet().size)
    }

    /** 启动/停止是同一个按钮的两个状态，两个字符串仍必须不同。 */
    @Test
    fun startAndStopAreDifferentWords() {
        assertNotEquals(UiText.START, UiText.STOP)
    }
}
