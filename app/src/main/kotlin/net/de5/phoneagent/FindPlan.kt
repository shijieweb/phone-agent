package net.de5.phoneagent

/**
 * `files find PATH KEYWORD [n]` (SPEC.md section 3.7).
 *
 * Pure Kotlin, like [FileGuard]: "which rows does this answer contain, and where
 * does it stop" is the part an operator will argue with, so it is decided here
 * and proven on a plain JVM instead of being buried in a directory walk.
 *
 * Two limits are deliberate:
 *
 *  - a hit list is capped at [LIMIT_MAX] rows, and the answer says how many were
 *    left out — a truncated search that looks complete is worse than no search;
 *  - `Android/data` and `Android/obb` are never entered. On Android 11+ those
 *    two trees belong to other apps and a walk into them returns nothing but
 *    permission noise, which would look like "the file is not there".
 */
internal object FindPlan {

    const val LIMIT_DEFAULT = 50
    const val LIMIT_MAX = 200

    const val USAGE = "usage: files find PATH KEYWORD [n]"

    /** Trees that scoped storage hides from every app without a special grant. */
    private val SKIP_SUFFIXES = listOf("/Android/data", "/Android/obb")

    fun clampLimit(value: Int): Int = value.coerceIn(1, LIMIT_MAX)

    /** Case-insensitive substring match: the operator types `mp4`, not a regex. */
    fun matches(name: String, keyword: String): Boolean {
        if (keyword.isEmpty()) return false
        return name.contains(keyword, ignoreCase = true)
    }

    /**
     * True when this absolute path is one of the two unreadable trees.
     *
     * Matched by suffix rather than by prefix so every accepted spelling of the
     * root (`/sdcard`, `/storage/emulated/0`, `/storage/self/primary`) behaves
     * the same way.
     */
    fun skipped(absolutePath: String): Boolean =
        SKIP_SUFFIXES.any { absolutePath.endsWith(it) || absolutePath.contains(it + "/") }

    /** One hit, reduced to what the row prints. */
    class Hit(val path: String, val isDir: Boolean, val size: Long)

    /** `<type> <bytes> <path>`; a directory has no size of its own. */
    fun row(hit: Hit): String {
        val kind = if (hit.isDir) "d" else "-"
        val size = if (hit.isDir) "        -" else FileGuard.formatSize(hit.size).padStart(9)
        return kind + " " + size + " " + hit.path
    }

    /** Path order, so the same tree always answers with the same list. */
    fun order(hits: List<Hit>): List<Hit> = hits.sortedBy { it.path.lowercase() }

    /**
     * The whole answer: a header naming what was searched, the rows, then one
     * line per thing that was withheld ([limit] rows and/or [scanCapped]).
     */
    fun report(
        root: String,
        keyword: String,
        hits: List<Hit>,
        limit: Int,
        scanCapped: Boolean = false
    ): String {
        val ordered = order(hits)
        val cap = clampLimit(limit)
        val shown = if (ordered.size <= cap) ordered else ordered.subList(0, cap)
        val sb = StringBuilder()
        sb.append("查找 ").append(root).append(" 关键字=").append(keyword)
            .append(" 命中 ").append(ordered.size).append(" 项")
        for (hit in shown) sb.append('\n').append(row(hit))
        if (shown.size < ordered.size) {
            sb.append('\n').append("… 还有 ").append(ordered.size - shown.size).append(" 项未列出")
        }
        if (scanCapped) {
            sb.append('\n').append("（已达到扫描上限，结果可能不完整）")
        }
        return sb.toString()
    }

    /** Nothing matched is a normal answer, not an error. */
    fun empty(root: String, keyword: String): String =
        "查找 " + root + " 关键字=" + keyword + " 命中 0 项"
}
