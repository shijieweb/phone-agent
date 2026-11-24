package net.de5.phoneagent

/**
 * `a11y status` (SPEC.md section 3.15).
 *
 * Three different facts decide whether the remote-control chain works, and they
 * fail independently:
 *
 *  - the master switch (`accessibility_enabled`) can be off while our entry is
 *    still listed;
 *  - the entry can be missing while the master switch is on;
 *  - both can be fine while the service is not bound (the process was killed, or
 *    Android dropped the binding after a crash loop).
 *
 * `status` already reports `acc`, but that is one boolean; an operator staring
 * at "accessibility service not connected" needs to know which of the three is
 * wrong before running the right repair.
 */
internal object A11yPlan {

    const val USAGE = "usage: a11y status|heal|rescue"

    /** The one adb line that re-creates a missing entry from a PC. */
    const val ADB_LINE =
        "adb shell settings put secure enabled_accessibility_services " +
            "net.de5.phoneagent/net.de5.phoneagent.PhoneAccessibilityService"

    /** Shown when nothing has been logged yet — never a blank value. */
    const val NO_RECORD = "(本次进程内无记录)"

    /**
     * Which trigger produced a repair line, newest-mechanism-first.
     *
     * The marker words are the ones [A11yHeal] writes. "安装后自愈" contains
     * "自愈", so the specific spellings must be tested before the generic one.
     */
    private val SOURCES = listOf(
        A11yHeal.SOURCE_INSTALL to "装包",
        A11yHeal.SOURCE_STARTUP to "启动",
        "自愈" to "心跳"
    )

    /** `装包` / `启动` / `心跳`, or "" for a line that is none of them. */
    fun sourceTag(line: String): String =
        SOURCES.firstOrNull { line.contains(it.first) }?.second ?: ""

    /**
     * Rewrite a raw [AgentLog] line so the trigger is visible at a glance:
     * `11-25 04:20:35 自愈: 已强制重连…` becomes
     * `11-25 04:20:35 [心跳] 已强制重连…`.
     *
     * Without this every repair looked identical and the v0.4.3 investigation had
     * to grep logcat to discover that the rebuild had never run and the *heartbeat*
     * had quietly saved the phone.
     */
    fun annotate(line: String?): String {
        if (line.isNullOrBlank()) return NO_RECORD
        val match = SOURCES.firstOrNull { line.contains(it.first) } ?: return line
        val at = line.indexOf(match.first)
        val before = line.substring(0, at).trimEnd()
        var after = line.substring(at + match.first.length).trim()
        // The install path appends its retry delay: "安装后自愈(3000ms): …". The
        // label already says which trigger fired; the raw line stays in the log
        // for the delay, so the summary line keeps to one shape.
        if (after.startsWith("(")) {
            val close = after.indexOf(')')
            if (close > 0) after = after.substring(close + 1).trim()
        }
        if (after.startsWith(":")) after = after.substring(1).trim()
        return before + " [" + match.second + "] " + after
    }

    /**
     * @param lastHeal the newest `[a11y]` line from [AgentLog], or null when
     *     nothing has been logged since the process started. Printed because the
     *     first question after an update is "did the rebuild even run?" — and the
     *     v0.4.2 answer was "no, silently" (BootReceiver returned before it).
     */
    fun report(
        enabled: Boolean,
        listed: Boolean,
        live: Boolean,
        lastHeal: String? = null
    ): String {
        val sb = StringBuilder()
        sb.append("无障碍总开关: ").append(if (enabled) "开" else "关")
        sb.append('\n').append("无障碍条目: ").append(if (listed) "已列出" else "缺失")
        sb.append('\n').append("服务实例: ").append(if (live) "已连接" else "未连接")
        // A blank line would read as "nothing to report" and hide exactly the
        // failure this line exists to expose (the rebuild never ran).
        sb.append('\n').append("最近自愈: ").append(annotate(lastHeal))
        sb.append('\n').append("结论: ").append(verdict(enabled, listed, live))
        return sb.toString()
    }

    /**
     * What to do next, in the operator's words.
     *
     * A healthy phone must say so without any advice attached: an instruction
     * printed next to "正常" invites someone to run it.
     */
    fun verdict(enabled: Boolean, listed: Boolean, live: Boolean): String = when {
        live && listed && enabled -> "正常"
        !listed -> "条目缺失，执行 a11y heal 或 " + ADB_LINE
        !enabled -> "总开关被关闭，执行 a11y heal"
        else -> "条目在但服务未绑定，执行 a11y rescue 强制重绑"
    }
}
