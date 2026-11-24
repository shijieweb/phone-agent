package net.de5.phoneagent

/**
 * Formatting for the `notif` command group (SPEC.md section 3.8).
 *
 * Pure on purpose: the notification listener can only be exercised on a device
 * with the listener grant, but *what the operator sees* is decided here, so it
 * is testable without one.
 */
internal object NotifPlan {

    const val LIMIT_DEFAULT = 30
    const val LIMIT_MAX = 200

    /** Titles and bodies are clipped: one chat message, not a screen dump. */
    const val TITLE_MAX = 80
    const val TEXT_MAX = 160

    /** One notification, reduced to what the listing shows. */
    class Item(
        val pkg: String,
        val title: String?,
        val text: String?,
        val timeMillis: Long,
        val ongoing: Boolean
    )

    /** What to say when the listener is not connected — includes the exact grant. */
    const val EMPTY =
        "没有可读通知（通知使用权未开启：adb shell cmd notification allow_listener " +
            "net.de5.phoneagent/net.de5.phoneagent.NotifListenerService）"

    const val NO_MATCH = "没有匹配的通知"

    fun clampLimit(value: Int): Int = value.coerceIn(1, LIMIT_MAX)

    /**
     * Flatten one field into a single line.
     *
     * Newlines are the whole reason this exists: a chat notification carries a
     * multi-line body, and a raw \n would break the one-line-per-notification
     * shape the operator reads. Runs of whitespace collapse to one space.
     */
    fun clip(value: String?, max: Int): String {
        if (value == null) return ""
        if (max <= 0) return ""
        val flat = StringBuilder()
        var pendingSpace = false
        for (ch in value) {
            val space = ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t'
            if (space) {
                if (flat.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) {
                    flat.append(' ')
                    pendingSpace = false
                }
                flat.append(ch)
            }
        }
        val trimmed = flat.toString()
        if (trimmed.length <= max) return trimmed
        if (max == 1) return trimmed.substring(0, 1)
        return trimmed.substring(0, max - 1) + "…"
    }

    /** `- pkg | title | text`; a leading `*` marks an ongoing notification. */
    fun line(item: Item): String {
        val sb = StringBuilder()
        sb.append(if (item.ongoing) "*" else "-")
        sb.append(' ').append(item.pkg)
        val title = clip(item.title, TITLE_MAX)
        if (title.isNotEmpty()) sb.append(" | ").append(title)
        val text = clip(item.text, TEXT_MAX)
        if (text.isNotEmpty()) sb.append(" | ").append(text)
        return sb.toString()
    }

    /** Case-insensitive substring search over package, title and body. */
    fun matches(item: Item, query: String?): Boolean {
        val needle = query?.trim()?.lowercase() ?: return true
        if (needle.isEmpty()) return true
        val haystack = item.pkg + " " + (item.title ?: "") + " " + (item.text ?: "")
        return haystack.lowercase().contains(needle)
    }

    fun report(items: List<Item>, limit: Int, query: String?): String {
        val hits = items.filter { matches(it, query) }
        if (hits.isEmpty()) return if (items.isEmpty()) EMPTY else NO_MATCH
        val cap = clampLimit(limit)
        val shown = if (hits.size <= cap) hits else hits.subList(0, cap)
        val sb = StringBuilder()
        sb.append("通知 ").append(hits.size).append(" 条")
        if (shown.size < hits.size) sb.append("（只显示前 ").append(shown.size).append(" 条）")
        for (item in shown) sb.append('\n').append(line(item))
        return sb.toString()
    }
}
