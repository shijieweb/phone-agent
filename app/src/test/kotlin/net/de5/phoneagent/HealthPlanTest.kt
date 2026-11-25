package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置页状态灯的三态判定（v0.6.2-pad12）。
 *
 * 钉住的缺陷：这一页是给非技术用户看的，判定错一次她就会照着错的信息去修一个
 * 不存在的问题，或者反过来以为没事。所以「服务没跑一律已停止」「缺几项都是部分
 * 异常不是已停止」「副行只列有问题的项且顺序固定」全部钉在这里。
 */
class HealthPlanTest {

    private fun snap(
        service: Boolean = true,
        a11y: Boolean = true,
        battery: Boolean = true,
        adb: Boolean = true
    ) = HealthPlan.Snapshot(service, a11y, battery, adb)

    @Test
    fun everythingGoodIsGreenAndSaysSo() {
        val health = HealthPlan.evaluate(snap())
        assertEquals(HealthPlan.Level.OK, health.level)
        assertEquals("运行中", health.title)
        assertEquals("一切正常", health.detail)
        assertTrue(health.issues.isEmpty())
    }

    @Test
    fun aStoppedServiceIsRedEvenWhenEverythingElseIsFine() {
        val health = HealthPlan.evaluate(snap(service = false))
        assertEquals(HealthPlan.Level.DOWN, health.level)
        assertEquals("已停止", health.title)
        assertEquals(listOf(HealthPlan.ISSUE_SERVICE), health.issues)
    }

    @Test
    fun aStoppedServiceStaysRedWhenEverythingElseIsAlsoBroken() {
        val health = HealthPlan.evaluate(snap(service = false, a11y = false, battery = false, adb = false))
        assertEquals(HealthPlan.Level.DOWN, health.level)
        assertEquals("已停止", health.title)
    }

    @Test
    fun accessibilityOffWhileRunningIsYellowAndNamesIt() {
        val health = HealthPlan.evaluate(snap(a11y = false))
        assertEquals(HealthPlan.Level.WARN, health.level)
        assertEquals("部分异常", health.title)
        assertTrue(health.issues.contains(HealthPlan.ISSUE_A11Y))
    }

    @Test
    fun twoMissingThingsAreListedInAFixedOrder() {
        val health = HealthPlan.evaluate(snap(battery = false, adb = false))
        assertEquals(HealthPlan.Level.WARN, health.level)
        assertEquals(listOf(HealthPlan.ISSUE_BATTERY, HealthPlan.ISSUE_ADB), health.issues)
        assertTrue(health.detail.startsWith(HealthPlan.ISSUE_BATTERY))
    }

    @Test
    fun theOrderDoesNotDependOnWhichFieldWasFalseFirst() {
        // 同一组缺失，换个构造顺序也必须给同一串 issues：顺序来自判定代码，
        // 不来自参数、集合或 Map 的迭代顺序。
        val a = HealthPlan.evaluate(snap(a11y = false, adb = false)).issues
        val b = HealthPlan.evaluate(snap(adb = false, a11y = false)).issues
        assertEquals(a, b)
        assertEquals(listOf(HealthPlan.ISSUE_A11Y, HealthPlan.ISSUE_ADB), a)
    }

    @Test
    fun allThreeMissingIsStillYellowNotRed() {
        val health = HealthPlan.evaluate(snap(a11y = false, battery = false, adb = false))
        assertEquals(HealthPlan.Level.WARN, health.level)
        assertEquals("部分异常", health.title)
        assertEquals(3, health.issues.size)
    }

    @Test
    fun aPermissionProblemPointsAtTheWizard() {
        val health = HealthPlan.evaluate(snap(a11y = false))
        assertTrue(health.detail.contains(HealthPlan.GUIDE_PERMISSION))
        assertFalse(health.detail.contains(HealthPlan.GUIDE_ADB))
    }

    @Test
    fun onlyAdbDownPointsAtShconnectInsteadOfTheWizard() {
        val health = HealthPlan.evaluate(snap(adb = false))
        assertEquals(listOf(HealthPlan.ISSUE_ADB), health.issues)
        assertTrue(health.detail.contains(HealthPlan.GUIDE_ADB))
        assertFalse(health.detail.contains(HealthPlan.GUIDE_PERMISSION))
    }

    @Test
    fun theGreenDetailUsesNoAlarmingWord() {
        val detail = HealthPlan.evaluate(snap()).detail
        for (word in listOf("异常", "失败", "错误", "缺", "未")) {
            assertFalse("全绿时副行不该出现「" + word + "」: " + detail, detail.contains(word))
        }
    }

    @Test
    fun theYellowAndRedTitlesAreTheWordsTheOwnerLooksFor() {
        assertEquals("运行中", HealthPlan.TITLE_OK)
        assertEquals("部分异常", HealthPlan.TITLE_WARN)
        assertEquals("已停止", HealthPlan.TITLE_DOWN)
        // 三个标题互不相同，否则换颜色也换不回可读性。
        assertEquals(3, setOf(HealthPlan.TITLE_OK, HealthPlan.TITLE_WARN, HealthPlan.TITLE_DOWN).size)
    }

    @Test
    fun theLampsReadTheSameSnapshotTheyAreDrawnFrom() {
        val s = snap(a11y = true, battery = false, adb = true)
        assertTrue(HealthPlan.lampOn(s, HealthPlan.LAMP_A11Y))
        assertFalse(HealthPlan.lampOn(s, HealthPlan.LAMP_BATTERY))
        assertTrue(HealthPlan.lampOn(s, HealthPlan.LAMP_ADB))
    }

    @Test
    fun theLampLabelsAreShortAndDistinct() {
        val labels = listOf(HealthPlan.LAMP_A11Y, HealthPlan.LAMP_BATTERY, HealthPlan.LAMP_ADB)
        assertEquals(3, labels.toSet().size)
        // 三盏灯横排等宽，最长的是「自连ADB」五个字符；再长就会在窄屏上换行。
        for (label in labels) assertTrue(label + " 太长，窄屏会换行", label.length <= 5)
    }

    /**
     * `click apply <文字>` 是子串包含匹配（ElementActions.labelMatches），所以灯的
     * 标签不能和任何可点文字互相包含：否则 `click apply 无障碍` 会先撞上灯标，
     * 报「只匹配到不可点文本」，或者更糟——点到别的按钮。
     */
    @Test
    fun noLampLabelOverlapsAClickableLabel() {
        for (lamp in listOf(HealthPlan.LAMP_A11Y, HealthPlan.LAMP_BATTERY, HealthPlan.LAMP_ADB)) {
            for (clickable in UiText.clickable) {
                assertFalse(
                    "灯的「" + lamp + "」和按钮的「" + clickable + "」互相包含，click apply 会点错",
                    lamp.contains(clickable) || clickable.contains(lamp)
                )
            }
        }
    }

    @Test
    fun noLampLabelOverlapsAFieldLabel() {
        for (lamp in listOf(HealthPlan.LAMP_A11Y, HealthPlan.LAMP_BATTERY, HealthPlan.LAMP_ADB)) {
            for (field in listOf(UiText.RELAY_LABEL, UiText.ID_LABEL, UiText.SECRET_LABEL)) {
                assertFalse(lamp + " / " + field, lamp.contains(field) || field.contains(lamp))
            }
        }
    }

    @Test
    fun anUnknownLampIsNeverOn() {
        assertFalse(HealthPlan.lampOn(snap(), "没有这盏灯"))
    }
}
