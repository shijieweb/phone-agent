package net.de5.phoneagent

/**
 * `status` 那一行 JSON 的字段表（v0.8.0-pad12）。
 *
 * 字段名是控制器的取值契约：**改一个就是断一次**，所以名字与顺序集中在这里，
 * 由纯 JVM 单测钉死。值仍然由 [CommandExecutor] 现场取（权限、电量、守护状态
 * 都不是纯逻辑），这个对象只负责"有哪几个键、按什么顺序、`shell` 怎么取值"。
 */
internal object StatusPlan {

    /** 自连 ADB 通着。 */
    const val SHELL_OK = "ok"

    /** 自连 ADB 断了（没配对、没连、或对端掉了）。 */
    const val SHELL_DOWN = "down"

    /**
     * 键名与顺序。v0.5.5 起有 `guard`，v0.8.0 起末尾加 `shell`；
     * 既有 11 个键一个都没改名、没挪位置。
     */
    val KEYS: List<String> = listOf(
        "ok",
        "ver",
        "acc",
        "svc",
        "scr",
        "bat",
        "loc",
        "loop",
        "opt",
        "queue",
        "guard",
        "shell"
    )

    /** `shell` 的取值：连上就是 ok，否则 down。 */
    fun shellFlag(connected: Boolean): String = if (connected) SHELL_OK else SHELL_DOWN

    /** 按 [KEYS] 的顺序拼字段，交给 `JSONObject` 逐个 put。 */
    fun fields(
        ver: String,
        acc: Boolean,
        svc: Boolean,
        scr: Boolean,
        bat: Int,
        loc: Boolean,
        loop: Int,
        opt: String,
        queue: Int,
        guard: String,
        shellConnected: Boolean
    ): List<Pair<String, Any>> = listOf(
        Pair("ok", true),
        Pair("ver", ver),
        Pair("acc", acc),
        Pair("svc", svc),
        Pair("scr", scr),
        Pair("bat", bat),
        Pair("loc", loc),
        Pair("loop", loop),
        Pair("opt", opt),
        Pair("queue", queue),
        Pair("guard", guard),
        Pair("shell", shellFlag(shellConnected))
    )
}
