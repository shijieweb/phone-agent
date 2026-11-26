package net.de5.phoneagent

/**
 * 功能模块注册表（v0.8.0-pad12）。
 *
 * 加第 13 个功能的**全部**改动：
 *
 *    1. 新建 `Feat<名字>.kt`（实现 [FeatureModule]）；
 *    2. 在下面的 [modules] 里加一行 `Feat<名字>,`。
 *
 * Commands.kt 与 Executor.kt 都不用动：前者只在 `when (head)` 末尾问一句
 * [parse]，后者只有一个 `is Command.Module` 分支问 [execute]。
 *
 * 顺序无关紧要，认领靠 [FeatureModule.verb] 精确匹配首词；重复的 verb 会被
 * [duplicateVerbs] 抓出来（单测盯着这一条）。
 */
internal object FeatureRegistry {

    /** 一行一个功能。**这就是加功能唯一要改的地方。** */
    val modules: List<FeatureModule> = listOf(
        FeatWhere,
        FeatMedia,
        FeatBattery,
        FeatNotify,
        FeatWm,
        FeatAutowake
    )

    /**
     * 首词分流。
     *
     * @return null 表示这个词没有任何模块认领，调用方回落到 `unknown command`。
     */
    fun parse(head: String, rest: String): Command? {
        val module = modules.firstOrNull { it.verb == head } ?: return null
        return when (val parsed = module.parse(rest)) {
            null -> null
            is Parsed.Ok -> Command.Module(module.verb, parsed.rest)
            is Parsed.Bad -> Command.BadUsage(parsed.detail)
        }
    }

    /** 执行。模块在 parse 之后被摘掉（理论上不会）时报 unknown，而不是崩。 */
    fun execute(command: Command.Module, ctx: FeatureContext): ExecutionResult {
        val module = modules.firstOrNull { it.verb == command.verb }
            ?: return ExecutionResult(EXIT_USAGE, "", "unknown command: " + command.verb)
        return module.execute(ctx, command.rest)
    }

    /** 全部认领的首词，按清单顺序；文档和自检用。 */
    fun verbs(): List<String> = modules.map { it.verb }

    /** 重复认领的首词（正常应为空；单测断言它为空）。 */
    fun duplicateVerbs(): List<String> =
        modules.groupBy { it.verb }.filter { it.value.size > 1 }.keys.sorted()
}

/**
 * 把"顺带要提一句"的注记挂到回执上（v0.8.0-pad12）。
 *
 * 两种注记：`wake=via=...`（autowake 亮屏了）和 `已自动恢复显示设置（超时）`。
 *
 * 规则只有一条：**绝不破坏 JSON 回执**。`status`/`where`/`shot` 的输出是给
 * 控制器解析的一行 JSON，往后拼一句话就是把它弄坏；这类回执的注记改走 stderr。
 */
internal object FeatureNotes {

    /** 这一行 stdout 是给机器读的 JSON 吗（只看首字符，够用且不会误伤）。 */
    fun jsonLike(stdout: String): Boolean = stdout.trimStart().startsWith("{")

    /**
     * @return (stdout, stderr) 两份新文本；[notes] 为空时原样返回。
     */
    fun attach(stdout: String, stderr: String, notes: List<String>): Pair<String, String> {
        if (notes.isEmpty()) return Pair(stdout, stderr)
        val line = notes.joinToString(" ")
        return when {
            stdout.isBlank() || jsonLike(stdout) -> Pair(stdout, join(stderr, line))
            else -> Pair(stdout + " " + line, stderr)
        }
    }

    private fun join(stderr: String, line: String): String =
        if (stderr.isBlank()) line else stderr + " " + line
}
