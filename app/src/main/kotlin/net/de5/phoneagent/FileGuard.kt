package net.de5.phoneagent

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Path safety for the `files` command group (SPEC.md section 3.7).
 *
 * This file deliberately contains **no Android imports**: "no verb may ever
 * touch a path outside /sdcard" is a rule that has to hold on every device and
 * every ROM, so it is written as a pure function that can be proven on a plain
 * JVM instead of being trusted to behave.
 *
 * Two facts decide every decision here:
 *
 *  - `/sdcard`, `/storage/emulated/0` and `/storage/self/primary` are the same
 *    tree on every device we ship to, so all three spellings are accepted and
 *    nothing else is;
 *  - `..` is resolved **lexically before** the containment check, so
 *    `/sdcard/../etc/passwd` is rejected as an escape instead of being handed to
 *    the filesystem and hoping it says no.
 */
internal object FileGuard {

    /** The one tree every `files` verb may touch. */
    const val ROOT = "/sdcard"

    /** `/sdcard` is a symlink; these three spellings name the same directory. */
    val ALLOWED_ROOTS = listOf("/sdcard", "/storage/emulated/0", "/storage/self/primary")

    const val LIMIT_DEFAULT = 100
    const val LIMIT_MAX = 500

    /**
     * `files cat` stops here. The relay truncates stdout at 190 000 chars, and a
     * log file is exactly the kind of thing that would blow that budget.
     */
    const val CAT_MAX_BYTES = 64 * 1024

    /** Fixed pattern so a listing column never shifts between rows. */
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun clampLimit(value: Int): Int = value.coerceIn(1, LIMIT_MAX)

    /**
     * Normalise one path and prove it stays inside the allowed tree.
     *
     * A relative path is taken relative to [ROOT], which is what an operator
     * typing `files ls DCIM` means. Returns null for anything that escapes or
     * is unusable — callers turn that into one sentence, never an exception.
     */
    fun resolve(input: String?): String? {
        val raw = (input ?: "").trim()
        if (raw.isEmpty()) return ROOT
        // A NUL would truncate the path inside the filesystem call.
        if (raw.contains('\u0000')) return null
        val absolute = if (raw.startsWith("/")) raw else ROOT + "/" + raw
        val parts = ArrayList<String>()
        for (segment in absolute.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> {
                    if (parts.isEmpty()) return null
                    parts.removeAt(parts.size - 1)
                }
                else -> parts.add(segment)
            }
        }
        if (parts.isEmpty()) return null
        val joined = "/" + parts.joinToString("/")
        return if (isWithin(joined)) joined else null
    }

    /** True when [path] is one of the allowed roots or lives under one. */
    fun isWithin(path: String): Boolean =
        ALLOWED_ROOTS.any { path == it || path.startsWith(it + "/") }

    /** True when [path] *is* a root: `rm` and `mkdir` must never target one. */
    fun isRoot(path: String): Boolean = ALLOWED_ROOTS.any { path == it }

    /**
     * Human-readable size, one decimal, no locale dependence: the operator reads
     * this on a chat, and "1.5 MB" must not become "1,5 MB" on a German phone.
     */
    fun formatSize(bytes: Long): String {
        if (bytes < 0L) return "?"
        if (bytes < 1024L) return bytes.toString() + " B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var index = 0
        while (value >= 1024.0 && index < units.size - 1) {
            value /= 1024.0
            index++
        }
        val tenths = Math.round(value * 10.0)
        return (tenths / 10).toString() + "." + (tenths % 10).toString() + " " + units[index]
    }

    /** `yyyy-MM-dd HH:mm` in [zone]; the zone is a parameter so tests can pin it. */
    fun stamp(millis: Long, zone: ZoneId): String =
        STAMP.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** One directory entry, already reduced to what a listing shows. */
    class Row(val name: String, val isDir: Boolean, val size: Long, val modified: Long)

    /** Directories first, then case-insensitive name: stable, so diffs are readable. */
    fun order(rows: List<Row>): List<Row> =
        rows.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))

    fun row(row: Row, zone: ZoneId): String {
        val kind = if (row.isDir) "d" else "-"
        val size = if (row.isDir) "        -" else formatSize(row.size).padStart(9)
        val name = row.name + if (row.isDir) "/" else ""
        return kind + " " + size + " " + stamp(row.modified, zone) + " " + name
    }

    /**
     * The whole answer of `files ls`: a header that states how many entries the
     * directory really has (so a truncated listing is never mistaken for the
     * whole directory), then the rows.
     */
    fun listing(path: String, rows: List<Row>, limit: Int, zone: ZoneId): String {
        val ordered = order(rows)
        val cap = clampLimit(limit)
        val shown = if (ordered.size <= cap) ordered else ordered.subList(0, cap)
        val sb = StringBuilder()
        sb.append(path).append(" (").append(ordered.size).append(" 项)")
        if (shown.size < ordered.size) {
            sb.append("，只显示前 ").append(shown.size).append(" 项")
        }
        for (entry in shown) sb.append('\n').append(row(entry, zone))
        return sb.toString()
    }

    /** `files info` for one entry. */
    fun describe(path: String, isDir: Boolean, size: Long, modified: Long, zone: ZoneId): String {
        val sb = StringBuilder()
        sb.append(path)
        sb.append('\n').append("类型: ").append(if (isDir) "目录" else "文件")
        sb.append('\n').append("大小: ").append(if (isDir) "-" else formatSize(size))
        sb.append('\n').append("修改: ").append(stamp(modified, zone))
        return sb.toString()
    }
}
