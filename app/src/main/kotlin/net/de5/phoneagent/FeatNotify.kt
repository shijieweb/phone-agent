package net.de5.phoneagent

/**
 * `notify post TITLE BODY` —— 用系统通知帮操作员"看一眼屏幕"（v0.8.0-pad12）。
 *
 * 走 uid 2000 的 `cmd notification post`，所以必须自连 ADB。默认 dry-run，
 * 加 `apply` 才真发。Android 12 上 `-S bigtext` 可能不认（`cmd notification`
 * 的参数各版本不一致），失败时去掉它重试一次——重试成功了也照实说一句。
 *
 * 首词是 `notify` 而不是 `notif`：`notif` 是既有的"列通知"命令，语义完全不同。
 */
internal object FeatNotify : FeatureModule {

    override val verb: String = "notify"

    override fun parse(rest: String): Parsed? {
        val request = NotifyPlan.parse(rest) ?: return Parsed.Bad(NotifyPlan.USAGE)
        return Parsed.Ok(NotifyPlan.normalize(request))
    }

    override fun execute(ctx: FeatureContext, args: String): ExecutionResult {
        val request = NotifyPlan.parse(args) ?: return ExecutionResult(EXIT_USAGE, "", NotifyPlan.USAGE)
        val command = NotifyPlan.command(request.title, request.body, bigText = true)
        if (!request.apply) return ExecutionResult(EXIT_OK, NotifyPlan.dryRunLine(command), "")

        val first = ctx.runShell(command, TIMEOUT_MS)
        if (first == null || first.precondition) {
            return ExecutionResult(EXIT_PERMISSION, "", NotifyPlan.needShell())
        }
        if (first.ok()) return ExecutionResult(EXIT_OK, NotifyPlan.postedLine(), "")

        // 兜底：去掉 -S bigtext 再发一次（Android 12 上这条参数可能不认）。
        val plain = NotifyPlan.command(request.title, request.body, bigText = false)
        val second = ctx.runShell(plain, TIMEOUT_MS)
        if (second == null || second.precondition) {
            return ExecutionResult(EXIT_PERMISSION, "", NotifyPlan.needShell())
        }
        if (second.ok()) return ExecutionResult(EXIT_OK, NotifyPlan.retriedLine(), "")
        if (second.code == null) {
            return ExecutionResult(EXIT_TIMEOUT, "", SelfAdbPlan.timeoutText(TIMEOUT_MS))
        }
        return ExecutionResult(EXIT_PERMISSION, "", NotifyPlan.failedLine(second.code, second.text))
    }

    private const val TIMEOUT_MS = 10_000L
}

/** [FeatNotify] 的纯逻辑：语法、命令拼装、文案。 */
internal object NotifyPlan {

    const val USAGE = "usage: notify post [apply] TITLE BODY"

    const val TITLE_MAX = 80
    const val BODY_MAX = 200

    /** 通知的 tag（`cmd notification post -t TAG` 用）。 */
    const val TAG = "phoneagent"

    /** 一次请求：标题一个 token，正文是剩下的原文。 */
    class Request(val title: String, val body: String, val apply: Boolean)

    /**
     * 语法：`post [apply] TITLE BODY`，`apply` 也可以写在末尾。
     *
     * 标题取一个 token，正文取剩下全部（允许空格，保留原文，trim 两端）。
     *
     * **apply 两个位置都认**（v0.8.1）：v0.8.0 只认前置，可 `wm size 900x1600
     * apply` 是后置——同一个 APP 两套规则，真机上操作员把 apply 写进正文了，
     * 于是拿到一条把 "apply" 当正文发出去的 dry-run。现在的规矩：
     * - 前置（`post apply T B`）：剥掉开头那一个，正文原样保留，末尾不再动
     *   （所以 `post apply T A apply` 的正文就是 `A apply`）。
     * - 后置（`post T B apply`）：只在剥完还剩"标题 + 正文"时才剥，
     *   免得把真带 apply 的正文吃掉。
     * - 两条都不成立时 apply 就是正文本身；`post T apply` 的正文是 `apply`
     *   （单 token 不剥），回执里的 dry-run 行会把它照出来。
     */
    fun parse(rest: String): Request? {
        val text = rest.trim()
        if (text.isEmpty()) return null
        val cut = text.indexOfFirst { it.isWhitespace() }
        val head = (if (cut < 0) text else text.substring(0, cut)).lowercase()
        if (head != "post") return null
        var tail = if (cut < 0) "" else text.substring(cut).trim()
        var apply = false
        if (tail.startsWith("apply ") || tail == "apply") {
            apply = true
            tail = if (tail == "apply") "" else tail.substring("apply".length).trim()
        } else {
            val at = trailingApplyAt(tail)
            if (at > 0) {
                val stripped = tail.substring(0, at).trim()
                if (stripped.indexOfFirst { it.isWhitespace() } > 0) {
                    apply = true
                    tail = stripped
                }
            }
        }
        if (tail.isEmpty()) return null
        val titleCut = tail.indexOfFirst { it.isWhitespace() }
        if (titleCut < 0) return null
        val title = tail.substring(0, titleCut).trim()
        val body = tail.substring(titleCut).trim()
        if (title.isEmpty() || body.isEmpty()) return null
        if (title.length > TITLE_MAX || body.length > BODY_MAX) return null
        return Request(title, body, apply)
    }

    /** @return 末尾那个 `apply` 之前的分隔位置；不是 apply 结尾则 -1（空白不挑种类）。 */
    private fun trailingApplyAt(text: String): Int {
        var at = text.length - 1
        while (at >= 0 && !text[at].isWhitespace()) at--
        if (at <= 0) return -1
        return if (text.substring(at + 1).equals("apply", true)) at else -1
    }

    fun normalize(request: Request): String =
        "post " + (if (request.apply) "apply " else "") + request.title + " " + request.body

    /**
     * `cmd notification post [-S bigtext] -t TITLE phoneagent BODY`。
     *
     * 引号内只转义 \ " $ ` 四个字符——剩下的原样塞进去，避免正文里的中文和标点被改。
     */
    fun command(title: String, body: String, bigText: Boolean): String =
        "cmd notification post " + (if (bigText) "-S bigtext " else "") +
            "-t \"" + quote(title) + "\" " + TAG + " \"" + quote(body) + "\""

    fun dryRunLine(command: String): String = SelfAdbPlan.dryRun(command)

    fun postedLine(): String = "notify 已发送"

    fun retriedLine(): String = "notify 已发送（这条 ROM 不认 -S bigtext，已去掉后重试成功）"

    fun needShell(): String =
        Admin.ERROR_PREFIX + "发通知要 uid 2000 的 cmd notification，需要先 shconnect：" +
            SelfAdbPlan.notConnected()

    fun failedLine(code: Int, text: String): String =
        Admin.ERROR_PREFIX + "notify 发送失败（返回 " + code + "）" +
            (if (text.isBlank()) "" else "：" + text.trim())

    private fun quote(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '$' -> sb.append("\\\$")
                '`' -> sb.append("\\`")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
