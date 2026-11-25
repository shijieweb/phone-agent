package net.de5.phoneagent

/**
 * 配置页顶部状态灯的纯逻辑（v0.6.2-pad12）。
 *
 * 为什么值得单独一个文件：这一页是给妈妈看的，她要的不是「四个字段各自什么值」，
 * 而是「现在到底好不好」。三态判定（好 / 半坏 / 停）一旦散在 UI 里，改一个字就会
 * 让「已停止」在服务其实还在跑的时候亮出来，而那种错没人会发现——它看着很正常。
 *
 * 这里不碰 android.*：四个输入都是外面探到的布尔，判定与文案在 JVM 单测里钉住。
 */
internal object HealthPlan {

    /** 四个真实探测结果。构造它的地方必须每次都重新探，不许读缓存。 */
    data class Snapshot(
        val serviceRunning: Boolean,
        val accessibilityOn: Boolean,
        val batteryWhitelisted: Boolean,
        val selfAdbConnected: Boolean
    )

    /** 主灯三态：绿 / 黄 / 红。 */
    enum class Level { OK, WARN, DOWN }

    /**
     * 一次判定的结果。
     *
     * [issues] 只列有问题的项，且**顺序固定**（无障碍 → 电池 → 自连 ADB）：
     * 用 Set 或 Map 迭代顺序会随 JVM 变，副行文字就会时好时坏，而副行是唯一
     * 告诉用户「哪里不对」的地方。
     */
    data class Health(
        val level: Level,
        val title: String,
        val detail: String,
        val issues: List<String>
    )

    const val TITLE_OK = "运行中"
    const val TITLE_WARN = "部分异常"
    const val TITLE_DOWN = "已停止"

    const val DETAIL_OK = "一切正常"
    const val DETAIL_DOWN = "服务没有在跑 · 点下面的「启动」"

    const val ISSUE_SERVICE = "服务未运行"
    const val ISSUE_A11Y = "无障碍已关闭"
    const val ISSUE_BATTERY = "电池白名单未加入"
    const val ISSUE_ADB = "自连 ADB 未连接"

    /** 三项里任何一项能靠一键向导补上，就指到那个按钮；只有 ADB 断时指到 shconnect。 */
    const val GUIDE_PERMISSION = "建议点下面的一键设置权限"
    const val GUIDE_ADB = "建议先 shconnect 连上自连 ADB"

    /**
     * 三盏小灯的短标签，横排等宽。
     *
     * 只是 [UiText] 的别名，不另存一份字符串：灯的标签和 `click apply` 认的文字
     * 必须是同一批，复制一份迟早会对不上（改一处忘另一处，操作员就点不到了）。
     */
    const val LAMP_A11Y = UiText.LAMP_A11Y
    const val LAMP_BATTERY = UiText.LAMP_BATTERY
    const val LAMP_ADB = UiText.LAMP_ADB

    private const val SEP = " · "

    /**
     * 三态判定。
     *
     * 服务没跑就是 DOWN，哪怕另外三项全绿：一个没在跑的服务对用户来说就是「停了」，
     * 把这种情况说成「部分异常」会让人以为还能用，于是不去点启动。
     * 服务在跑但缺东西才是 WARN——**缺几项都还是 WARN**，不是 DOWN。
     */
    fun evaluate(s: Snapshot): Health {
        if (!s.serviceRunning) {
            return Health(Level.DOWN, TITLE_DOWN, DETAIL_DOWN, listOf(ISSUE_SERVICE))
        }
        val issues = ArrayList<String>(3)
        if (!s.accessibilityOn) issues.add(ISSUE_A11Y)
        if (!s.batteryWhitelisted) issues.add(ISSUE_BATTERY)
        if (!s.selfAdbConnected) issues.add(ISSUE_ADB)
        if (issues.isEmpty()) {
            return Health(Level.OK, TITLE_OK, DETAIL_OK, emptyList())
        }
        return Health(Level.WARN, TITLE_WARN, issues.joinToString(SEP) + SEP + guidance(issues), issues)
    }

    /**
     * 副行末尾那句「该怎么办」。
     *
     * 只缺自连 ADB 时不能叫用户去点一键设置权限——那个向导不管 ADB，点完还是黄的，
     * 用户只会觉得按钮坏了。
     */
    private fun guidance(issues: List<String>): String =
        if (issues.any { it == ISSUE_A11Y || it == ISSUE_BATTERY }) GUIDE_PERMISSION else GUIDE_ADB

    /** 小灯亮不亮：只回答这一项自身，三盏灯一起灰是 [evaluate] 用主灯表达的事。 */
    fun lampOn(s: Snapshot, which: String): Boolean = when (which) {
        LAMP_A11Y -> s.accessibilityOn
        LAMP_BATTERY -> s.batteryWhitelisted
        LAMP_ADB -> s.selfAdbConnected
        else -> false
    }
}
