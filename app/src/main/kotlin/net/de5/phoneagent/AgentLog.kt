package net.de5.phoneagent

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Tiny in-memory ring buffer of recent agent activity, so that a failure can be
 * reported together with the events that led to it (the phone has no adb, so the
 * clipboard is our only channel out of the device).
 *
 * Every entry is also mirrored to logcat under the "PhoneAgent" tag. Nothing here
 * touches the network or the filesystem and no call ever throws.
 */
internal object AgentLog {

    /** Enough for roughly two hours of 25 s polls, small enough to paste anywhere. */
    private const val MAX_LINES = 400

    private const val LOGCAT_TAG = "PhoneAgent"

    private val lines = ArrayDeque<String>(MAX_LINES)
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /** Lines evicted because the buffer was full; reported so the gap is visible. */
    private var dropped = 0

    @Synchronized
    fun add(tag: String, message: String) {
        val line = stamp.format(Date()) + " [" + tag + "] " + message
        if (lines.size >= MAX_LINES) {
            lines.pollFirst()
            dropped++
        }
        lines.addLast(line)
        try {
            Log.i(LOGCAT_TAG, line)
        } catch (t: Throwable) {
            // Logging must never be the reason the agent stops working.
        }
    }

    /** Most recent [max] lines, oldest first, with a note when older ones were lost. */
    @Synchronized
    fun dump(max: Int = MAX_LINES): String {
        if (lines.isEmpty()) return "(日志为空)"
        val sb = StringBuilder()
        val lost = dropped + if (lines.size > max) lines.size - max else 0
        if (lost > 0) sb.append("(已丢弃较早的 ").append(lost).append(" 行)\n")
        val skip = if (lines.size > max) lines.size - max else 0
        var index = 0
        for (line in lines) {
            if (index++ < skip) continue
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    @Synchronized
    fun size(): Int = lines.size

    @Synchronized
    fun droppedCount(): Int = dropped

    @Synchronized
    fun clear() {
        lines.clear()
        dropped = 0
        add("log", "日志已清空")
    }
}
