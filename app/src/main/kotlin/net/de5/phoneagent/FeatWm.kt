package net.de5.phoneagent

import android.content.Context

/**
 * `wm` —— 看/改显示分辨率与密度，**并且会自己过期**（v0.8.0-pad12）。
 *
 * 真改分辨率能把界面撑坏（点不到按钮、看不见状态栏），所以这条命令带自动
 * 过期：真改之前把原始值和截止时间（默认 5 分钟）写进 Prefs，之后任何命令
 * 执行前都会查一次，过期就 `wm size reset` + `wm density reset`，并在下一次
 * 回执里提一句"已自动恢复显示设置（超时）"。
 *
 * 读和改都要 uid 2000 的 `wm`，所以必须自连 ADB；改系统状态默认 dry-run。
 */
internal object FeatWm : FeatureModule {

    override val verb: String = "wm"

    override fun parse(rest: String): Parsed? {
        val request = WmPlan.parse(rest) ?: return Parsed.Bad(WmPlan.USAGE)
        return Parsed.Ok(WmPlan.normalize(request))
    }

    override fun execute(ctx: FeatureContext, args: String): ExecutionResult {
        val request = WmPlan.parse(args) ?: return ExecutionResult(EXIT_USAGE, "", WmPlan.USAGE)
        if (request.read) return read(ctx)

        val command = WmPlan.command(request)
        if (!request.apply) return ExecutionResult(EXIT_OK, WmPlan.dryRunLine(command), "")

        // 真改之前先记原始值（只为回执和事后排查，恢复统一走 reset）。
        val original = WmPlan.originalLine(originalSize(ctx).orEmpty(), originalDensity(ctx).orEmpty())

        val out = ctx.runShell(command, TIMEOUT_MS)
        if (out == null || out.precondition) {
            return ExecutionResult(EXIT_PERMISSION, "", WmPlan.needShell())
        }
        if (out.code == null) {
            return ExecutionResult(EXIT_TIMEOUT, "", SelfAdbPlan.timeoutText(TIMEOUT_MS))
        }
        if (out.code != 0) {
            return ExecutionResult(EXIT_PERMISSION, "", WmPlan.failedLine(command, out.code, out.text))
        }

        val prefs = Prefs(ctx.context)
        if (request.value == WmPlan.RESET) {
            WmExpiry.clear(prefs)
            return ExecutionResult(EXIT_OK, WmPlan.resetLine(request.kind), "")
        }
        WmExpiry.arm(prefs, original, WmPlan.deadlineOf(System.currentTimeMillis(), request.seconds))
        return ExecutionResult(EXIT_OK, WmPlan.appliedLine(request), "")
    }

    private fun read(ctx: FeatureContext): ExecutionResult {
        val sizeOut = ctx.runShell(WmPlan.SIZE_READ, TIMEOUT_MS)
        if (sizeOut == null || sizeOut.precondition) return ExecutionResult(EXIT_PERMISSION, "", WmPlan.needShell())
        val densityOut = ctx.runShell(WmPlan.DENSITY_READ, TIMEOUT_MS)
        if (densityOut == null || densityOut.precondition) return ExecutionResult(EXIT_PERMISSION, "", WmPlan.needShell())
        // v0.8.2：读命令要报**当前生效值**，所以这里走 current*（优先 Override）。
        // 原来的 sizeOf/densityOf 优先 Physical，那是给 original*（保险丝基准）用的，
        // 两个用途共用一个函数就会串成 v0.8.1 那个假值。
        val size = WmPlan.currentSize(sizeOut.text)
        val density = WmPlan.currentDensity(densityOut.text)
        if (size == null || density == null) return ExecutionResult(EXIT_PERMISSION, "", WmPlan.unreadable())
        val line = WmPlan.readLine(
            size,
            density,
            WmPlan.originalSize(sizeOut.text),
            WmPlan.originalDensity(densityOut.text),
        )
        return ExecutionResult(EXIT_OK, line, "")
    }

    private fun originalSize(ctx: FeatureContext): String? {
        val out = ctx.runShell(WmPlan.SIZE_READ, TIMEOUT_MS) ?: return null
        if (!out.ok()) return null
        return WmPlan.originalSize(out.text)
    }

    private fun originalDensity(ctx: FeatureContext): String? {
        val out = ctx.runShell(WmPlan.DENSITY_READ, TIMEOUT_MS) ?: return null
        if (!out.ok()) return null
        return WmPlan.originalDensity(out.text)
    }

    private const val TIMEOUT_MS = 10_000L
}

/** [FeatWm] 的纯逻辑：语法、命令拼装、输出解析、文案。 */
internal object WmPlan {

    const val USAGE =
        "usage: wm | wm size WxH [SECONDS] apply | wm size reset apply | " +
            "wm density N [SECONDS] apply | wm density reset apply"

    const val SIZE_READ = "wm size"
    const val DENSITY_READ = "wm density"
    const val SIZE_RESET = "wm size reset"
    const val DENSITY_RESET = "wm density reset"

    const val KIND_SIZE = "size"
    const val KIND_DENSITY = "density"
    const val RESET = "reset"

    const val SECONDS_DEFAULT = 300
    const val SECONDS_MIN = 30
    const val SECONDS_MAX = 3600

    const val SIZE_MIN = 200
    const val SIZE_MAX = 5000
    const val DENSITY_MIN = 100
    const val DENSITY_MAX = 1000

    private val SPACES = Regex("\\s+")
    private val SIZE_SHAPE = Regex("[0-9]+x[0-9]+")

    /** 一次请求：[read] 是只读；否则 [kind] + [value]（或 [RESET]）+ 过期秒数。 */
    class Request(
        val read: Boolean,
        val kind: String,
        val value: String,
        val seconds: Int,
        val apply: Boolean
    )

    fun parse(args: String): Request? {
        val tokens = args.trim().split(SPACES).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Request(true, "", "", SECONDS_DEFAULT, false)

        var list = tokens
        var apply = false
        if (list[list.size - 1].equals("apply", true)) {
            apply = true
            list = list.subList(0, list.size - 1)
        }
        if (list.isEmpty() || list.size > 3) return null

        val kind = list[0].lowercase()
        if (kind != KIND_SIZE && kind != KIND_DENSITY) return null
        if (list.size < 2) return null

        val value = list[1].lowercase()
        var seconds = SECONDS_DEFAULT
        if (list.size == 3) {
            val given = list[2].toIntOrNull() ?: return null
            if (given < SECONDS_MIN || given > SECONDS_MAX) return null
            seconds = given
        }
        if (value == RESET) {
            // reset 不接受秒数：它本来就是"立刻还原"，没有过期概念。
            if (list.size == 3) return null
            return Request(false, kind, RESET, seconds, apply)
        }
        if (kind == KIND_SIZE) {
            if (!SIZE_SHAPE.matches(value)) return null
            val parts = value.split("x")
            val width = parts[0].toIntOrNull() ?: return null
            val height = parts[1].toIntOrNull() ?: return null
            if (width < SIZE_MIN || width > SIZE_MAX) return null
            if (height < SIZE_MIN || height > SIZE_MAX) return null
        } else {
            val density = value.toIntOrNull() ?: return null
            if (density < DENSITY_MIN || density > DENSITY_MAX) return null
        }
        return Request(false, kind, value, seconds, apply)
    }

    fun normalize(request: Request): String {
        if (request.read) return ""
        val head = request.kind + " " + request.value
        val seconds = if (request.value == RESET || request.seconds == SECONDS_DEFAULT) {
            ""
        } else {
            " " + request.seconds
        }
        return head + seconds + (if (request.apply) " apply" else "")
    }

    /**
     * 真跑的那条 shell 命令。
     *
     * 前缀 `wm ` 一个字都不能省：v0.8.0 少了它，真机（MEIZU_22 / Android 16）
     * 上 `wm size 900x1600 apply` 实际执行的是 `size 900x1600`，回
     * `/system/bin/sh: size: inaccessible or not found`。读和两个 reset 常量
     * 本来就带前缀，所以只有 apply 全废——单测 `WmPlanTest` 现在把
     * "每条命令都以 wm 开头"钉死，常量也一起钉。
     */
    fun command(request: Request): String = "wm " + request.kind + " " + request.value

    fun dryRunLine(command: String): String = SelfAdbPlan.dryRun(command)

    fun appliedLine(request: Request): String =
        "wm 已设置 " + request.kind + "=" + request.value + "，" +
            request.seconds + "s 后自动恢复（执行 wm " + request.kind + " reset apply 可立刻恢复）"

    fun resetLine(kind: String): String = "wm 已恢复 " + kind + " 到系统默认"

    /**
     * 读命令的一行输出，字段顺序固定。
     *
     * 没有 Override 时**逐字节保持老格式** `wm size 1200x2670 density 520`，免得
     * 既有脚本的解析断掉。有 Override 时当前生效值和物理值不是一回事了，必须
     * 两个都说出来，否则就像 v0.8.1 那样：设备明明改成 900x1600，回执却报
     * 1200x2670，看着像没改成功。
     *
     * @param size 当前生效尺寸（优先 Override）
     * @param physicalSize 物理尺寸（`Physical size:` 那一行），null 表示没读到
     */
    fun readLine(size: String, density: String, physicalSize: String? = null, physicalDensity: String? = null): String {
        val plain = "wm size " + size + " density " + density
        val sizeOverridden = physicalSize != null && physicalSize.isNotEmpty() && physicalSize != size
        val densityOverridden = physicalDensity != null && physicalDensity.isNotEmpty() && physicalDensity != density
        if (!sizeOverridden && !densityOverridden) return plain
        val ps = if (physicalSize.isNullOrEmpty()) "?" else physicalSize
        val pd = if (physicalDensity.isNullOrEmpty()) "?" else physicalDensity
        return plain + " (override; physical " + ps + "/" + pd + ")"
    }

    /**
     * `wm size` 输出里的**当前生效**尺寸——给读命令用。
     *
     * 被人改过时输出有两行（`Physical size: 1200x2670` + `Override size: 900x1600`），
     * 此时设备真的在用 Override。v0.8.1 在这里错用了 [originalSize]，
     * 于是 `wm` 报的是物理值，看着像改没成功（真机 P3）。
     */
    fun currentSize(text: String): String? =
        valueAfter(text, "Override size:") ?: valueAfter(text, "Physical size:")

    /** `wm density` 输出里的**当前生效**密度——给读命令用，优先 Override。 */
    fun currentDensity(text: String): String? =
        valueAfter(text, "Override density:") ?: valueAfter(text, "Physical density:")

    /**
     * 从 `wm size` 的输出里取**物理**尺寸——只给保险丝基准值用。
     *
     * **优先 Physical**：那才是 reset 之后会回到的样子。拿 Override 当"原始值"
     * 等于把临时值记成了基准，恢复完再记一次就再也回不去了。
     */
    fun originalSize(text: String): String? =
        valueAfter(text, "Physical size:") ?: valueAfter(text, "Override size:")

    /** 从 `wm density` 的输出里取**物理**密度——只给保险丝基准值用，优先 Physical。 */
    fun originalDensity(text: String): String? =
        valueAfter(text, "Physical density:") ?: valueAfter(text, "Override density:")

    /** 原始值一行，写进 Prefs 备查（拿不到就写空，不影响过期恢复）。 */
    fun originalLine(size: String, density: String): String {
        val parts = ArrayList<String>(2)
        if (size.isNotEmpty()) parts.add("size=" + size)
        if (density.isNotEmpty()) parts.add("density=" + density)
        return parts.joinToString(" ")
    }

    fun deadlineOf(now: Long, seconds: Int): Long = now + seconds * 1000L

    fun expired(deadlineAt: Long, now: Long): Boolean = deadlineAt > 0L && now >= deadlineAt

    fun expiryNote(): String = "已自动恢复显示设置（超时）"

    fun needShell(): String =
        Admin.ERROR_PREFIX + "wm 走 uid 2000 的 shell，需要先 shconnect：" + SelfAdbPlan.notConnected()

    fun unreadable(): String =
        Admin.ERROR_PREFIX + "读不到显示参数：wm size / wm density 的输出里既没有 Override 行也没有 Physical 行"

    fun failedLine(command: String, code: Int, text: String): String =
        Admin.ERROR_PREFIX + "wm 执行失败（" + command + " 返回 " + code + "）" +
            (if (text.isBlank()) "" else "：" + text.trim())

    private fun valueAfter(text: String, key: String): String? {
        val at = text.indexOf(key)
        if (at < 0) return null
        val tail = text.substring(at + key.length).trim()
        if (tail.isEmpty()) return null
        val end = tail.indexOfFirst { it.isWhitespace() }
        val value = if (end < 0) tail else tail.substring(0, end)
        return if (value.isEmpty()) null else value
    }
}

/** 跑一条 uid 2000 shell 的最小接口，[WmExpiry] 用它恢复显示设置。 */
internal fun interface ShellRunner {
    fun run(command: String): ShellOut?
}

/**
 * 显示设置的自动过期（v0.8.0-pad12）。
 *
 * 状态只有两样：截止时间与原始值，都放 Prefs（进程被杀也还在）。
 * [check] 由 [CommandExecutor] 在**每条命令执行前**调一次；没武装的时候
 * 只读一个 long，零成本。
 */
internal object WmExpiry {

    fun arm(prefs: Prefs, original: String, deadlineAt: Long) {
        prefs.wmOriginal = original
        prefs.wmDeadline = deadlineAt
    }

    fun clear(prefs: Prefs) {
        prefs.wmDeadline = 0L
        prefs.wmOriginal = ""
    }

    /**
     * 到期就恢复并返回一句注记；没到期 / 恢复失败返回 null。
     *
     * 恢复失败（自连 ADB 断了）**故意不解除武装**：下次命令再试，宁可多试几次，
     * 也不能把界面留在改坏的分辨率上还当没事。
     */
    fun check(context: Context, runner: ShellRunner): String? {
        val prefs = Prefs(context)
        val deadline = prefs.wmDeadline
        if (!WmPlan.expired(deadline, System.currentTimeMillis())) return null
        val size = runner.run(WmPlan.SIZE_RESET)
        val density = runner.run(WmPlan.DENSITY_RESET)
        val restored = size != null && size.ok() && density != null && density.ok()
        if (!restored) return null
        clear(prefs)
        return WmPlan.expiryNote()
    }
}
