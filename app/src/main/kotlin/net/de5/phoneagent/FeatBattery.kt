package net.de5.phoneagent

/**
 * `battery` —— 电池优化白名单（v0.8.0-pad12）。
 *
 * 只读那半走 [Battery.isExempt]（PowerManager 查询，不需要 shell）；改白名单
 * 那半要 uid 2000 的 `cmd deviceidle whitelist`，所以必须自连 ADB。
 * 改系统状态默认 dry-run，加 `apply` 才真干。
 */
internal object FeatBattery : FeatureModule {

    override val verb: String = "battery"

    override fun parse(rest: String): Parsed? {
        val request = BatteryPlan.parse(rest) ?: return Parsed.Bad(BatteryPlan.USAGE)
        return Parsed.Ok(BatteryPlan.normalize(request))
    }

    override fun execute(ctx: FeatureContext, args: String): ExecutionResult {
        val request = BatteryPlan.parse(args) ?: return ExecutionResult(EXIT_USAGE, "", BatteryPlan.USAGE)
        if (request.read) {
            return ExecutionResult(EXIT_OK, BatteryPlan.readLine(Battery.isExempt(ctx.context)), "")
        }
        val command = BatteryPlan.command(request)
        if (!request.apply) return ExecutionResult(EXIT_OK, BatteryPlan.dryRunLine(command), "")

        // 动手前先读现状（v0.8.1）：手机上本来就在白名单里时，回"已加入"等于
        // 说谎，操作员没法判断这条命令到底有没有生效。读失败就当作"不知道"，
        // 回执退回原来的两句话，绝不因为读不到就不改。
        val before = runCatching { Battery.isExempt(ctx.context) }.getOrNull()

        val out = ctx.runShell(command, TIMEOUT_MS)
        if (out == null || out.precondition) {
            return ExecutionResult(EXIT_PERMISSION, "", BatteryPlan.needShell())
        }
        if (out.code == null) {
            return ExecutionResult(EXIT_TIMEOUT, "", SelfAdbPlan.timeoutText(TIMEOUT_MS))
        }
        if (out.code != 0) {
            return ExecutionResult(EXIT_PERMISSION, "", BatteryPlan.failedLine(command, out.code, out.text))
        }
        return ExecutionResult(EXIT_OK, BatteryPlan.appliedLine(request.allow, before), "")
    }

    private const val TIMEOUT_MS = 10_000L
}

/** [FeatBattery] 的纯逻辑：语法、命令拼装、文案。 */
internal object BatteryPlan {

    const val USAGE = "usage: battery | battery allow [apply] | battery deny [apply]"

    /** 本应用包名；白名单命令按它加减。 */
    const val PACKAGE = "net.de5.phoneagent"

    private val SPACES = Regex("\\s+")

    /** 一次请求：[read] 是只读查询；否则按 [allow] 加/减白名单。 */
    class Request(val read: Boolean, val allow: Boolean, val apply: Boolean)

    /** @return null 表示语法不成立（调用方回 usage）。 */
    fun parse(args: String): Request? {
        val tokens = args.trim().split(SPACES).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Request(true, false, false)
        val allow = when (tokens[0].lowercase()) {
            "allow" -> true
            "deny" -> false
            else -> return null
        }
        return when {
            tokens.size == 1 -> Request(false, allow, false)
            tokens.size == 2 && tokens[1].equals("apply", true) -> Request(false, allow, true)
            else -> null
        }
    }

    fun normalize(request: Request): String = when {
        request.read -> ""
        request.apply -> (if (request.allow) "allow" else "deny") + " apply"
        else -> if (request.allow) "allow" else "deny"
    }

    fun command(request: Request): String =
        "cmd deviceidle whitelist " + (if (request.allow) "+" else "-") + PACKAGE

    fun readLine(exempt: Boolean): String =
        if (exempt) "battery whitelisted" else "battery not-whitelisted"

    fun dryRunLine(command: String): String = SelfAdbPlan.dryRun(command)

    /**
     * 改完的回执，三种说法分开（v0.8.1）。
     *
     * [before] 是动手前的白名单状态，null 表示读不到（那就别猜）。本来就在
     * 白名单里还说"已加入"，操作员会以为命令真的干了活；本来就不在还说
     * "已移出"同理。
     */
    fun appliedLine(allow: Boolean, before: Boolean?): String {
        if (before == allow) {
            return if (allow) "battery 本来就在白名单里（未改动）" else "battery 本来就不在白名单里（未改动）"
        }
        return when {
            allow -> "battery 已加入电池优化白名单"
            else -> "battery 已移出电池优化白名单"
        }
    }

    /** 兼容 v0.8.0 的单参调用（不知道现状时按"刚改"说）。 */
    fun appliedLine(allow: Boolean): String = appliedLine(allow, null)

    fun needShell(): String =
        Admin.ERROR_PREFIX + "改电池白名单要 uid 2000 的 cmd deviceidle，需要先 shconnect：" +
            SelfAdbPlan.notConnected()

    fun failedLine(command: String, code: Int, text: String): String =
        Admin.ERROR_PREFIX + "battery 改白名单失败（" + command + " 返回 " + code + "）" +
            (if (text.isBlank()) "" else "：" + text.trim())
}
