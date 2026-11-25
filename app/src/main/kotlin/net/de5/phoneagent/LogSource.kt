package net.de5.phoneagent

/**
 * `logs` 走哪条路（v0.5.9-pad12）。
 *
 * ## 为什么需要选路
 *
 * Android 16 给 logcat 加了「日志访问」运行时授权：App 以**自己的 uid** 起
 * `logcat`，系统立刻弹一个「允许 Phone Agent 访问所有设备日志吗？」的对话框
 * （`com.android.systemui.logcat.LogAccessDialogActivity`），而且只给「允许访问
 * 一次」——**每次都要重弹**。远程操作员看不见那块屏，点不了那个框，于是
 * `logs` 在真机上等于不可用。
 *
 * 更坑的是 `pm grant READ_LOGS` 不解决问题：权限确实变成 granted=true（dumpsys
 * 里看得见），运行时授权是另一套判定，弹框照旧。所以文案里绝不能指路去 pm grant。
 *
 * 而 **uid 2000（shell）的 logcat 完全不需要这个授权**——自连 ADB 跑
 * `logcat -d -t N` 直接就有输出、零弹窗。所以：连上自连 ADB 就走它，
 * 没连上才退回 App 自读。
 *
 * 这里只做纯判定与文案，不碰 android.*，好在 JVM 单测里钉住「连上走哪条、
 * 没连走哪条、行数怎么钳」。
 */
internal object LogSource {

    /** 与 `logs [lines]` 的解析上限同一个数，免得别的调用方绕过 [Commands] 时对不上。 */
    const val MAX_LINES = Commands.LOGS_LINES_MAX

    const val MIN_LINES = 1

    /** 一条命令怎么跑。两个分支的**类型不同**，调用方不可能把它们搞混。 */
    sealed class Plan {
        /**
         * 自连 ADB（uid 2000）：不弹框、不需要 READ_LOGS，是首选。
         * [command] 是交给 `SelfAdb.shell` 的整行命令。
         */
        data class Adb(val command: String) : Plan()

        /**
         * App 自己起 logcat：Android 16 上会弹系统授权框，只在自连 ADB 不可用时用。
         * [arguments] 直接喂给 `ProcessBuilder`（第一个是程序名）。
         */
        data class Local(val arguments: List<String>) : Plan()
    }

    /** 行数钳进 1..[MAX_LINES]；静默钳制比报错好——日志行数不是需要操作员确认的东西。 */
    fun clamp(lines: Int): Int = lines.coerceIn(MIN_LINES, MAX_LINES)

    /** 自连 ADB 通道的命令行；`logcat -d -t N` 是「dump 完就退出」的意思。 */
    fun adbCommand(lines: Int): String = "logcat -d -t " + clamp(lines)

    /** App 自读通道的参数（含程序名），形状与以前完全一致。 */
    fun localArguments(lines: Int): List<String> =
        listOf("logcat", "-d", "-t", clamp(lines).toString())

    fun plan(adbConnected: Boolean, lines: Int): Plan =
        if (adbConnected) Plan.Adb(adbCommand(lines)) else Plan.Local(localArguments(lines))

    /**
     * App 自读拿不到任何行时念给操作员的话。
     *
     * 三件事必须都在里面，否则操作员会照着错的方向修：
     * 1) 这是 Android 16 的运行时授权弹框，不是「没授权」；
     * 2) 每次都要重弹，所以远程根本点不了 —— 这条路在无人值守场景下是死的；
     * 3) 出路是自连 ADB（`shconnect`）或 `sh apply`，**不是** `pm grant`。
     */
    fun localEmptyText(lines: Int): String =
        "logcat 无输出。Android 16 起 App 直接读日志要弹系统授权框（" +
            "「允许 Phone Agent 访问所有设备日志吗？」，只给「允许一次」，每次都要重弹），" +
            "远程点不了，所以这条路在无人值守时不可用；" +
            "pm grant READ_LOGS 也没用（权限显示已授予，弹框照旧）。" +
            "建议先 shconnect 连上自连 ADB 再执行 logs，" +
            "或直接 sh apply logcat -d -t " + clamp(lines)
}
