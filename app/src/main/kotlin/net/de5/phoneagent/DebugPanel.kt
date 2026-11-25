package net.de5.phoneagent

/**
 * The pure logic behind the hidden debug screen ([DebugActivity]).
 *
 * Two things there are worth pinning down on a plain JVM, because both are the
 * kind of code that fails silently on a phone:
 *
 * - [TapGate]: the five-press door. An off-by-one or a window measured with the
 *   wrong clock means the door either never opens (operator stranded) or opens
 *   on a stray tap (the debug screen is reachable by a curious thumb).
 * - [clip] / [formatResult]: what one command's answer looks like on a screen.
 *   A 190 000-char stdout dumped into a TextView freezes the UI thread, and a
 *   silently shortened answer is worse than a marked one.
 */
internal object DebugPanel {

    /** How much of one answer the panel shows. A phone screen, not a terminal. */
    const val PANEL_MAX_CHARS = 2000

    /**
     * Said instead of a result when no service instance is alive: every command
     * needs the executor that the service built (see
     * [RcAgentService.executeLocally]).
     */
    const val NOT_RUNNING = "服务未运行，先回配置页点启动"

    /** Prefix of the note appended to a shortened answer. */
    const val TRUNCATED_MARK = "…（已截断"

    /**
     * Cut [text] to [max] characters and say so.
     *
     * The cut never lands inside a surrogate pair: half of an emoji renders as a
     * replacement box and makes the whole line look corrupt.
     */
    fun clip(text: String, max: Int = PANEL_MAX_CHARS): String {
        if (text.length <= max) return text
        var cut = max
        if (cut > 0 && Character.isHighSurrogate(text[cut - 1])) cut--
        return text.substring(0, cut) +
            "\n" + TRUNCATED_MARK + "，共 " + text.length + " 字符，只显示前 " + cut + " 字符）"
    }

    /**
     * One command's answer as the panel shows it: the exit code first (it is the
     * only part that is always present), stdout, then stderr when there is any.
     * The whole thing is clipped, so an answer never grows past the screen.
     */
    fun formatResult(exit: Int, stdout: String, stderr: String, max: Int = PANEL_MAX_CHARS): String {
        val sb = StringBuilder()
        sb.append("exit=").append(exit).append('\n')
        sb.append(if (stdout.isEmpty()) "(无 stdout)" else stdout)
        if (stderr.isNotEmpty()) sb.append("\n--- stderr ---\n").append(stderr)
        return clip(sb.toString(), max)
    }
}

/**
 * "Press [need] times inside [windowMs]" — the door to [DebugActivity].
 *
 * Times are whatever the caller passes; the UI passes a monotonic clock
 * (`SystemClock.elapsedRealtime`), because a wall clock can jump backwards and
 * strand the door shut. A gate that has just fired empties itself, so opening
 * the debug screen once does not leave four presses primed for the next tap.
 */
internal class TapGate(private val need: Int, private val windowMs: Long) {

    private val stamps = ArrayDeque<Long>()

    /** Record one press at [now]; true when this press completes the sequence. */
    fun tap(now: Long): Boolean {
        stamps.addLast(now)
        while (stamps.isNotEmpty() && now - stamps.first() > windowMs) {
            stamps.removeFirst()
        }
        if (stamps.size < need) return false
        stamps.clear()
        return true
    }

    /** How many presses are still inside the window. */
    fun count(): Int = stamps.size

    fun reset() {
        stamps.clear()
    }
}
