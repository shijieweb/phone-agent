package net.de5.phoneagent

/**
 * `files pull PATH` — send one file out through the relay blob channel
 * (SPEC.md section 3.7).
 *
 * The relay accepts a blob name only when it matches
 * `^[A-Za-z0-9_.-]{1,80}$`, and it silently rejects anything larger than
 * [ShotBlob.MAX_BYTES]. Both facts are decided here, before a byte is read, so
 * a bad name or an oversized file is a sentence on the phone rather than a 400
 * from a server the operator cannot see.
 */
internal object PullPlan {

    const val PREFIX = "file-"

    /** Enough for `.tar.gz`-style endings, short enough to keep the name legal. */
    const val EXT_MAX_CHARS = 8

    const val USAGE = "usage: files pull PATH"

    /**
     * The original extension, lower-cased and stripped to `[a-z0-9]`.
     *
     * Returns null when there is nothing usable: a name must never be extended
     * with a character the relay would reject.
     */
    fun extensionOf(path: String): String? {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        val raw = name.substring(dot + 1).lowercase()
        if (raw.length > EXT_MAX_CHARS) return null
        if (!raw.all { (it in 'a'..'z') || (it in '0'..'9') }) return null
        return raw
    }

    /**
     * `file-<millis>-<6 chars>` plus the original extension, always inside the
     * relay's accepted window.
     */
    fun name(millis: Long, suffix: String, extension: String?): String {
        val stem = PREFIX + millis + "-" + cleanSuffix(suffix)
        val withExt = if (extension.isNullOrEmpty()) stem else stem + "." + extension
        return if (ShotBlob.isValidName(withExt)) withExt else stem
    }

    /** The one stdout line: what landed, how big, and which path it came from. */
    fun json(name: String, bytes: Int, path: String): String =
        "{\"name\":\"" + name + "\",\"bytes\":" + bytes + ",\"path\":\"" + escape(path) + "\"}"

    /** A refusal that names the limit, because "too large" alone is not actionable. */
    fun tooLarge(bytes: Long): String =
        "文件太大: " + FileGuard.formatSize(bytes) +
            "（relay blob 上限 " + FileGuard.formatSize(ShotBlob.MAX_BYTES) + "）"

    fun failure(reason: String): String = Admin.ERROR_PREFIX + "上传失败: " + reason

    private fun cleanSuffix(suffix: String): String {
        val kept = StringBuilder(6)
        for (ch in suffix.lowercase()) {
            if ((ch in 'a'..'z') || (ch in '0'..'9')) kept.append(ch)
            if (kept.length == 6) break
        }
        while (kept.length < 6) kept.append('0')
        return kept.toString()
    }

    private fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) out.append('?') else out.append(ch)
            }
        }
        return out.toString()
    }
}
