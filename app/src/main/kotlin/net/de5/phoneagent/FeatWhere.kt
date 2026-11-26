package net.de5.phoneagent

/**
 * `where` —— 一条命令问清"现在在哪个页面"（v0.8.0-pad12）。
 *
 * 存在的理由：操作前先问一句在哪个页面，能省掉一半 `tree` 往返。
 * 包名走无障碍（[PhoneAccessibilityService.instance] 的 rootInActiveWindow），
 * activity 名无障碍拿不到，只有自连 ADB 通着时才补（`dumpsys window`）。
 *
 * 输出一行 JSON，字段顺序固定：`{"ok":true,"pkg":...,"act":...,"scr":...,"kg":...}`。
 * 手拼而不是用 `JSONObject`：这样纯 JVM 单测能逐字比对（同 LocationJsonTest 的做法）。
 */
internal object FeatWhere : FeatureModule {

    override val verb: String = "where"

    override fun parse(rest: String): Parsed? =
        if (rest.isBlank()) Parsed.Ok("") else Parsed.Bad(WherePlan.USAGE)

    override fun execute(ctx: FeatureContext, args: String): ExecutionResult {
        // autowake 开着时先亮屏（关着的时候这一步什么也不做，既有行为不变）。
        val guard = Autowake.preflight(ctx.context)
        if (guard.refusal != null) return guard.refusal

        val service = PhoneAccessibilityService.instance
            ?: return ExecutionResult(EXIT_NO_ACCESSIBILITY, "", WherePlan.noAccessibility())
        val root = ctx.onMain(QUERY_TIMEOUT_MS) { service.rootInActiveWindow }
        val pkg = root?.packageName?.toString().orEmpty()
        val line = WherePlan.json(
            pkg = pkg,
            act = activityViaShell(ctx),
            scr = if (isScreenOn(ctx.context)) WherePlan.SCR_ON else WherePlan.SCR_OFF,
            kg = Autowake.keyguardState(ctx.context)
        )
        val note = guard.note ?: return ExecutionResult(EXIT_OK, line, "")
        val (stdout, stderr) = FeatureNotes.attach(line, "", listOf(note))
        return ExecutionResult(EXIT_OK, stdout, stderr)
    }

    /**
     * 有自连 ADB 才补 activity 名；没有就留空串。
     *
     * 真机上 `dumpsys window | grep mCurrentFocus` 可能尾随一句
     * `Failed to write while dumping service window: Broken pipe`，退出码仍是 0，
     * 所以这里只看 `code == 0`，解析交给 [WherePlan.activityOf] 容错。
     */
    private fun activityViaShell(ctx: FeatureContext): String {
        val out = ctx.runShell(WherePlan.FOCUS_COMMAND, QUERY_TIMEOUT_MS) ?: return ""
        if (out.precondition || out.code != 0) return ""
        return WherePlan.activityOf(out.text)
    }

    private const val QUERY_TIMEOUT_MS = 5_000L
}

/** [FeatWhere] 的纯逻辑：JSON 拼装、焦点行解析、文案。 */
internal object WherePlan {

    const val USAGE = "usage: where"

    /** 取当前焦点窗口的 shell 命令（需要 uid 2000，走自连 ADB）。 */
    const val FOCUS_COMMAND = "dumpsys window | grep mCurrentFocus"

    const val SCR_ON = "on"
    const val SCR_OFF = "off"

    private const val FOCUS_KEY = "mCurrentFocus="

    /**
     * 一行 JSON。
     *
     * 键顺序 ok / pkg / act / scr / kg 是控制器的取值契约，别动。
     */
    fun json(pkg: String, act: String, scr: String, kg: String): String =
        "{\"ok\":true,\"pkg\":\"" + escape(pkg) +
            "\",\"act\":\"" + escape(act) +
            "\",\"scr\":\"" + escape(scr) +
            "\",\"kg\":\"" + escape(kg) + "\"}"

    /**
     * 从 `dumpsys window` 的输出里抠 activity 全名。
     *
     * 真机输出形如：
     * `mCurrentFocus=Window{e21ab69 u0 com.tencent.mm/com.tencent.mm.ui.chatting.variants.ChattingMainUI}`
     * —— 取最后一个 `/` 之后、到 `}` 或空白为止。取不到（`mCurrentFocus=null`、
     * 没有焦点行、焦点在桌面上没有 activity）就返回空串，调用方原样回 ""。
     */
    fun activityOf(dump: String): String {
        val line = dump.lineSequence().firstOrNull { it.contains(FOCUS_KEY) } ?: return ""
        val tail = line.substring(line.indexOf(FOCUS_KEY) + FOCUS_KEY.length)
        val slash = tail.lastIndexOf('/')
        if (slash < 0) return ""
        val after = tail.substring(slash + 1)
        val end = after.indexOfFirst { it == '}' || it.isWhitespace() }
        return (if (end < 0) after else after.substring(0, end)).trim()
    }

    fun noAccessibility(): String =
        "无障碍服务没连上：where 靠无障碍读当前窗口的包名，请先在系统设置里打开 phoneagent 的无障碍服务"

    private fun escape(value: String): String {
        if (value.isEmpty()) return ""
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
