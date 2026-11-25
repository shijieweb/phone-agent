package net.de5.phoneagent

/**
 * 日志占用与清理的纯逻辑（v0.5.8-pad12）。
 *
 * 老板只提了一条：日志不能无限膨胀。审计下来真正无界的只有一处——守护脚本的
 * `/data/local/tmp/phoneagent-guard.log`（每重启一次追加一行，没人来删）。其余两处
 * 本来就有限：内存环形缓冲 [AgentLog] 封顶 400 行，缓存目录里的 APK 只在「下一次
 * 下载」之前裁。
 *
 * 这个文件刻意不 import 任何 android.*：怎么报、怎么裁、报成什么字样都要能在
 * 纯 JVM 单测里钉住，而不是靠真机点一遍。真正动手的部分（shell、删文件）在
 * [CommandExecutor] 里，只调用这里给出的命令与判定。
 */
internal object LogSizes {

    /** 与 [Downloader] 的 STALE_MS 同一个数：一天前的 APK 一定是残留，不是待装。 */
    const val APK_STALE_MS = 24L * 60L * 60L * 1000L

    /** 守护日志的一行测量：字节数 + 行数。 */
    data class Guard(val bytes: Long, val lines: Int)

    /** 缓存目录里的一个 APK 残留。 */
    data class Apk(val name: String, val bytes: Long, val modified: Long)

    /** 脚本里用到的美元符；直接写 $ 会被 Kotlin 当模板插值。 */
    private const val D = '$'

    /**
     * 量守护日志：输出 `B=字节 L=行`，文件不存在就 MISSING。
     *
     * 用 `echo B=$(wc -c < 文件)` 而不是让 wc 自己打印：wc 直接跑文件会把路径也
     * 打出来，而路径里带空格，切分就不可靠了。`tr -d " "` 去掉 wc 的补位空格。
     */
    fun readCommand(): String =
        "if [ -f " + WatchdogPlan.LOG_PATH + " ]; then " +
            "echo B=" + D + "(wc -c < " + WatchdogPlan.LOG_PATH + " | tr -d \" \") " +
            "L=" + D + "(wc -l < " + WatchdogPlan.LOG_PATH + " | tr -d \" \"); " +
            "else echo MISSING; fi"

    /**
     * 裁守护日志：超上限才动，保留末 [WatchdogPlan.LOG_KEEP_LINES] 行。
     *
     * 与守护脚本里那一段是同一套判定（先量、再 tail 到 .tmp、再 mv），所以
     * 「脚本自己会封顶」和「手动 logs trim」不会互相打脸。
     */
    fun trimCommand(): String =
        "if [ -f " + WatchdogPlan.LOG_PATH + " ]; then " +
            "SZ=" + D + "(wc -c < " + WatchdogPlan.LOG_PATH + " | tr -d \" \"); " +
            "if [ " + D + "{SZ:-0} -gt " + WatchdogPlan.LOG_MAX_BYTES + " ]; then " +
            "tail -n " + WatchdogPlan.LOG_KEEP_LINES + " " + WatchdogPlan.LOG_PATH +
            " > " + WatchdogPlan.LOG_PATH + ".tmp && mv " + WatchdogPlan.LOG_PATH +
            ".tmp " + WatchdogPlan.LOG_PATH + "; echo TRIMMED; else echo UNDER; fi; " +
            "else echo MISSING; fi"

    /**
     * 从 PTY 回显里抠出测量结果。
     *
     * PTY 会把命令行本身也回显一遍，而命令行里写着 `B=$(wc -c ...)`——带 $( 不带
     * 数字，所以只认 `B=数字` 就不会把回显当成结果。取最后一组：命令自己也可能
     * 打印历史行。
     */
    fun parseGuard(text: String): Guard? {
        val bytes = Regex("B=(\\d+)").findAll(text).lastOrNull()
            ?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val lines = Regex("L=(\\d+)").findAll(text).lastOrNull()
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return Guard(bytes, lines)
    }

    /** `logs trim` 的三种回执；null 表示回显里什么都没有（多半是 shell 没回来）。 */
    fun trimStatus(text: String): String? = when {
        text.contains("TRIMMED") -> "trimmed"
        text.contains("UNDER") -> "under"
        text.contains("MISSING") -> "missing"
        else -> null
    }

    /** 超过一天没动过的就是残留，跟 [Downloader] 的判定一致。 */
    fun stale(apks: List<Apk>, now: Long): List<Apk> =
        apks.filter { it.modified < now - APK_STALE_MS }

    fun total(apks: List<Apk>): Long {
        var sum = 0L
        for (apk in apks) sum += apk.bytes
        return sum
    }

    /**
     * `logs size` 的三行账本：内存环形缓冲、守护日志、缓存里的 APK 残留。
     *
     * 读不到守护日志时**不许编数字**：说清楚为什么读不到（自连 ADB 没连上 / 文件
     * 还没建 / 超时），让老板知道该去修哪一层。
     */
    fun sizeReport(
        guard: Guard?,
        guardNote: String?,
        ringLines: Int,
        ringDropped: Int,
        apks: List<Apk>
    ): String = StringBuilder()
        .append("内存日志: ").append(ringLines).append(" 行（上限 ")
        .append(AgentLog.MAX_LINES).append(" 行")
        .append(if (ringDropped > 0) "，已丢弃较早的 " + ringDropped + " 行" else "")
        .append("）\n")
        .append("守护日志: ").append(guardLine(guard, guardNote)).append('\n')
        .append("APK 残留: ").append(apkLine(apks))
        .toString()

    private fun guardLine(guard: Guard?, note: String?): String = when {
        guard != null -> FileGuard.formatSize(guard.bytes) + "（" + guard.lines +
            " 行，上限 " + FileGuard.formatSize(WatchdogPlan.LOG_MAX_BYTES.toLong()) + "）"
        note != null -> "不可用（" + note + "）"
        else -> "不可用"
    }

    private fun apkLine(apks: List<Apk>): String =
        if (apks.isEmpty()) {
            "0 个"
        } else {
            apks.size.toString() + " 个，共 " + FileGuard.formatSize(total(apks))
        }

    /**
     * `logs trim` 的回执。dry-run 与 apply 只差措辞，不差判定——判定只有一份。
     */
    fun trimReport(
        guard: Guard?,
        guardNote: String?,
        staleApks: List<Apk>,
        keptApks: Int,
        apply: Boolean,
        after: Guard?
    ): String = StringBuilder()
        .append(guardSection(guard, guardNote, apply, after)).append('\n')
        .append(apkSection(staleApks, keptApks, apply)).append('\n')
        .append(if (apply) "已按上面执行。" else "dry-run: 没有删除任何东西，加 apply 才真删。")
        .toString()

    private fun guardSection(guard: Guard?, note: String?, apply: Boolean, after: Guard?): String {
        if (guard == null) return "守护日志: 不可用（" + (note ?: "未读到") + "）"
        if (guard.bytes <= WatchdogPlan.LOG_MAX_BYTES) {
            return "守护日志: " + FileGuard.formatSize(guard.bytes) + "（" + guard.lines +
                " 行），没超上限 " + FileGuard.formatSize(WatchdogPlan.LOG_MAX_BYTES.toLong()) +
                "，不动"
        }
        if (!apply) {
            return "守护日志: " + FileGuard.formatSize(guard.bytes) + "（" + guard.lines +
                " 行）超过上限 " + FileGuard.formatSize(WatchdogPlan.LOG_MAX_BYTES.toLong()) +
                "，将裁到末 " + WatchdogPlan.LOG_KEEP_LINES + " 行"
        }
        if (after == null) {
            // 真删了却没回读到：说「没确认」，别替裁剪成功吹牛。
            return "守护日志: " + FileGuard.formatSize(guard.bytes) + "（" + guard.lines +
                " 行）超过上限，已发出裁剪命令，但没回读到结果"
        }
        return "守护日志: " + FileGuard.formatSize(guard.bytes) + "（" + guard.lines +
            " 行）→ " + FileGuard.formatSize(after.bytes) + "（" + after.lines +
            " 行），已裁到末 " + WatchdogPlan.LOG_KEEP_LINES + " 行"
    }

    private fun apkSection(staleApks: List<Apk>, keptApks: Int, apply: Boolean): String {
        if (staleApks.isEmpty()) return "APK 残留: " + keptApks + " 个，没有超 24 小时的，不动"
        val freed = FileGuard.formatSize(total(staleApks))
        return if (apply) {
            "APK 残留: 已删 " + staleApks.size + " 个（" + freed + "），留 " + keptApks + " 个"
        } else {
            "APK 残留: 其中 " + staleApks.size + " 个超 24 小时（" + freed + "），将删，留 " +
                keptApks + " 个"
        }
    }

    /** 调试页摘要里那一行：不读 shell，只报已经量到的数。 */
    fun summaryLine(ringLines: Int, ringDropped: Int, guard: Guard?, apks: List<Apk>): String {
        val sb = StringBuilder("日志: ").append(ringLines).append(" 行")
        if (ringDropped > 0) sb.append("/丢弃 ").append(ringDropped)
        sb.append(" · 守护日志 ")
        if (guard == null) sb.append("未读到") else sb.append(FileGuard.formatSize(guard.bytes))
        sb.append(" · APK 残留 ")
        if (apks.isEmpty()) {
            sb.append("0 个")
        } else {
            sb.append(apks.size).append(" 个 ").append(FileGuard.formatSize(total(apks)))
        }
        return sb.toString()
    }
}
