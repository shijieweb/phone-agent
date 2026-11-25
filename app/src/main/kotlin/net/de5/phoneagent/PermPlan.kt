package net.de5.phoneagent

/**
 * 配置页「一键设置权限」向导的纯逻辑（v0.5.8-pad12）。
 *
 * 为什么要有向导：以前是四个按钮并排（请求权限 / 打开无障碍设置 / 加入电池白名单 /
 * 打开电池优化设置），妈妈不知道该点哪个、点几次、什么算齐了。现在只有一个按钮，
 * 每按一次只做「下一件还没做的事」，按钮下面一行小字说清还缺什么。
 *
 * 这里只判定顺序与文案，不碰 android.*：真机上最容易错的恰恰是「先要哪个」，
 * 而这条顺序能在 JVM 单测里钉住。
 */
internal object PermPlan {

    /** 一步比一步靠后；DONE 表示全齐。 */
    enum class Step { RUNTIME, ACCESSIBILITY, BATTERY, DONE }

    /**
     * 下一件要做的事。
     *
     * 顺序不能换：定位权限必须在最前——无障碍与电池白名单的弹窗都要先有前台
     * Activity，而定位的两段式询问（Android 11+ 的「始终允许」是第二次单独问）
     * 一旦被别的对话框打断，第二次询问就不会出现，用户永远拿不到后台定位。
     */
    fun next(runtimeGranted: Boolean, accessibilityLive: Boolean, batteryExempt: Boolean): Step =
        when {
            !runtimeGranted -> Step.RUNTIME
            !accessibilityLive -> Step.ACCESSIBILITY
            !batteryExempt -> Step.BATTERY
            else -> Step.DONE
        }

    /** 按钮下面那行小字：还缺什么 / 已齐全。 */
    fun hint(step: Step): String = when (step) {
        Step.RUNTIME -> "还缺：定位权限（会问两次，第二次选「始终允许」）"
        Step.ACCESSIBILITY -> "还缺：无障碍服务，在列表里打开 PhoneAgent"
        Step.BATTERY -> "还缺：电池白名单，在系统弹窗里选「允许」"
        Step.DONE -> "权限已齐全"
    }

    /** 按下按钮之后写在状态行的话；全齐时点也不报错，只回一句。 */
    fun pressed(step: Step): String = when (step) {
        Step.RUNTIME -> "正在申请定位权限（会问两次）"
        Step.ACCESSIBILITY -> "已打开无障碍设置"
        Step.BATTERY -> "已请求电池白名单"
        Step.DONE -> "权限已齐全"
    }
}
