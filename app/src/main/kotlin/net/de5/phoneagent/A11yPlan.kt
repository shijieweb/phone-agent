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
        sb.append('\n').append("最近自愈: ")
            .append(if (lastHeal.isNullOrBlank()) "(本次进程内无记录)" else lastHeal)
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
