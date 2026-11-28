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
 * 第三种状态。v0.7.0-pad12 加回一个「视频转音乐」，v0.7.3-pad12 又加了一个「刷新」
 * （「视频转音乐」页里重数一遍用），现在共八个可点文字。
 */
internal object UiText {

    const val START = "启动"
    const val STOP = "停止"
    const val SAVE = "保存"
    const val STAY_ON = "屏幕常亮"
    const val PERMISSION = "一键设置权限"
    const val ADVANCED = "高级设置"

    /**
     * 打开「视频转音乐」页（v0.7.0-pad12）。
     *
     * 单独一页而不是塞进配置页：那一页要显示进度和结果，和「启动/停止」这种
     * 一按就生效的开关不是一类东西。
     */
    const val CONVERT = "视频转音乐"

    /**
     * 「视频转音乐」页里的重新统计（v0.7.3-pad12）。
     *
     * 妈妈的真实用法是：先点进这一页，再用文件管理器往里拷视频，然后切回来。
     * 切回来会自动重扫（[ConvertActivity.onResume]），这个按钮是自动那条不灵时的
     * 手动兜底——她不用去猜「为什么还是旧的」。
     */
    const val REFRESH = "刷新"

    /**
     * 打开「摄像头看图」页（v0.9.0-pad12）。
     *
     * 手机主人举着手机看一样东西、打一句话，操作员在那边就能看到这一帧——
     * 见 [CameraActivity]。和 [CONVERT] 一样是单独一页：它要占满整屏放预览，
     * 塞不进配置页。
     */
    const val CAMERA = "摄像头看图"

    /** 启动/停止是同一个按钮的两个状态，永远只出现一个，但两个字符串仍互不相同。 */
    val clickable = listOf(
        START,
        STOP,
        SAVE,
        STAY_ON,
        PERMISSION,
        ADVANCED,
        CONVERT,
        REFRESH,
        CAMERA
    )

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
