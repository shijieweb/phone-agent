package net.de5.phoneagent

/**
 * 配置页上所有**可点控件**的文字（v0.5.8-pad12）。
 *
 * 为什么值得单独拎出来：操作员在真机上靠 `click apply <文字>` 点，不靠坐标。
 * 两个控件同名就点错，文字随状态乱变就点不到。所以文字在这里集中定义，
 * [clickable] 给单测用——重复一次就红。
 *
 * v0.6.3-pad12 去掉了「开机自动启动」：那个开关的生命周期本来就和启动/停止按钮
 * 绑在一起（按启动就置位、按停止就清零），单独摆一个复选框只会让人以为还有
 * 第三种状态。现在只剩六个可点文字。
 */
internal object UiText {

    const val START = "启动"
    const val STOP = "停止"
    const val SAVE = "保存"
    const val STAY_ON = "屏幕常亮"
    const val PERMISSION = "一键设置权限"
    const val ADVANCED = "高级设置"

    /** 启动/停止是同一个按钮的两个状态，永远只出现一个，但两个字符串仍互不相同。 */
    val clickable = listOf(START, STOP, SAVE, STAY_ON, PERMISSION, ADVANCED)

    /** 高级区里三个输入框的标题（不可点，但也不能重名，否则看图对不上）。 */
    const val RELAY_LABEL = "Relay base URL"
    const val ID_LABEL = "Agent id"
    const val SECRET_LABEL = "Secret"

    /**
     * 三盏小灯的短标签（v0.6.2-pad12）。
     *
     * 放在这里而不是 [HealthPlan]：HealthPlan 只判定「亮不亮」，文字属于界面。
     * 但**必须**和可点文字一起查重——操作员用 `click apply <文字>` 点，而
     * ElementActions 的匹配是子串包含，「无障碍」正是「无障碍已关闭」的子串。
     */
    const val LAMP_A11Y = "无障碍"
    const val LAMP_BATTERY = "电池"
    const val LAMP_ADB = "自连ADB"

    /** 只读、不可点的文字。它们之间也不能重名，否则看图对不上是哪一项。 */
    val labels = listOf(
        RELAY_LABEL,
        ID_LABEL,
        SECRET_LABEL,
        LAMP_A11Y,
        LAMP_BATTERY,
        LAMP_ADB
    )
}
