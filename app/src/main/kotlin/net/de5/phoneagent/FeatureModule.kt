package net.de5.phoneagent

import android.content.Context

/**
 * 一个可插拔功能模块（v0.8.0-pad12）。
 *
 * 存在的理由只有一个：**加第 13 个功能时，只新增一个 `Feat<名字>.kt`，再在
 * [FeatureRegistry] 的清单里加一行**。语法解析、执行、以及这个功能所有用户可见的
 * 文案都锁在同一个文件里，Commands.kt 和 Executor.kt 一个字都不用动。
 *
 * 契约（违反就不是模块）：
 *  - [verb] 认领首词，小写，与 [FeatureRegistry] 清单里的顺序无关；
 *  - [parse] 只做语法判断，不碰 Android；解析不了返回 [Parsed.Bad]，文案统一
 *    `usage: ...`，由调用方映射成 `exit=2`；
 *  - [execute] 拿 [FeatureContext] 干活，返回值就是最终回执，不许自己写 exit 码
 *    映射表以外的东西。
 *
 * 现有 82 个命令**不在此列**：它们一条都没搬进来，语义、文案、退出码原样不动。
 * 模块化是为了以后好上功能，不是为了重写历史。
 */
internal interface FeatureModule {

    /** 认领的首词，小写（`where`、`media`、`wm` …）。 */
    val verb: String

    /**
     * 语法解析。
     *
     * @param rest 首词之后、原样未切分的剩余参数（含前导空白），这样 `notify post`
     *   的正文才能逐字保留。
     * @return [Parsed.Ok] 带规范化后的参数串；[Parsed.Bad] 带 usage 文案；
     *   返回 null 表示"这个词不是我的"，调用方回落到 `unknown command`。
     */
    fun parse(rest: String): Parsed?

    /** 执行。`args` 是 [Parsed.Ok.rest]（已规范化）。 */
    fun execute(ctx: FeatureContext, args: String): ExecutionResult
}

/** [FeatureModule.parse] 的两种结果。 */
internal sealed class Parsed {

    /** 语法通过；[rest] 是执行阶段要看的规范化参数串（可为空串）。 */
    class Ok(val rest: String) : Parsed()

    /** 词认得、参数不能用；[detail] 就是给操作员看的 usage 文案。 */
    class Bad(val detail: String) : Parsed()
}

/**
 * 模块能碰到的全部外部能力。
 *
 * 刻意做成接口而不是直接塞 `CommandExecutor`：功能文件只需要这四样东西，
 * 拿得越少越不容易把既有语义改坏，也方便以后换实现（例如把 shell 换成别的通道）。
 */
internal interface FeatureContext {

    /** 应用上下文，给 SharedPreferences / PowerManager 这类只读查询用。 */
    val context: Context

    /** 真改系统状态的命令，这次调用允不允许真干（当前恒为 true，留给以后收紧）。 */
    val applyAllowed: Boolean

    /**
     * 在主线程跑一段框架调用并等它。
     *
     * @return 结果；超时或抛异常时 null（与 [CommandExecutor] 的同名方法一致）。
     */
    fun <T> onMain(timeoutMs: Long, block: () -> T): T?

    /**
     * 以 uid 2000 跑一条 shell。
     *
     * @return 回执；**自连 ADB 没连上时返回 null**，功能自己决定是回
     *   `exit=6` 还是干脆不需要 shell。
     */
    fun runShell(command: String, timeoutMs: Long): ShellOut?
}

/**
 * 一次 uid 2000 shell 的回执。
 *
 * 和 [SelfAdbPlan.Exit] 同形但独立：功能文件不必依赖自连 ADB 的实现细节，
 * 纯逻辑单测也能直接构造一个回执来断言文案。
 */
internal class ShellOut(
    /** 远端退出码；null 表示命令没跑完（超时/通道断了）。 */
    val code: Int?,
    /** 远端输出（stdout 与 stderr 混在一个流里）。 */
    val text: String,
    /** true 表示根本没连上/没配对，[code] 无意义。 */
    val precondition: Boolean
) {
    /** 命令真的跑完且远端返回 0。 */
    fun ok(): Boolean = !precondition && code == 0
}
