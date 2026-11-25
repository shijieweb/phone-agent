package net.de5.phoneagent

/**
 * 配置页上所有**可点控件**的文字（v0.5.8-pad12）。
 *
 * 为什么值得单独拎出来：操作员在真机上靠 `click apply <文字>` 点，不靠坐标。
 * 两个控件同名就点错，文字随状态乱变就点不到。所以文字在这里集中定义，
 * [clickable] 给单测用——重复一次就红。
 */
internal object UiText {

    const val START = "启动"
    const val STOP = "停止"
    const val SAVE = "保存"
    const val AUTO_START = "开机自动启动"
    const val STAY_ON = "屏幕常亮"
    const val PERMISSION = "一键设置权限"
    const val ADVANCED = "高级设置"

    /** 启动/停止是同一个按钮的两个状态，永远只出现一个，但两个字符串仍互不相同。 */
    val clickable = listOf(START, STOP, SAVE, AUTO_START, STAY_ON, PERMISSION, ADVANCED)

    /** 高级区里三个输入框的标题（不可点，但也不能重名，否则看图对不上）。 */
    const val RELAY_LABEL = "Relay base URL"
    const val ID_LABEL = "Agent id"
    const val SECRET_LABEL = "Secret"
}
