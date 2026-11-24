package net.de5.phoneagent

/**
 * The pure half of `dl` (SPEC.md section 3.6): what the file is called, where it
 * goes, when it is too big, and exactly what the operator reads afterwards.
 *
 * No Android imports, so every rule here runs on a plain JVM.
 */

/** Why a `dl` failed; decides the exit code the relay reports. */
internal enum class DlFail {
    /** The transfer outlasted `t=`, or was cancelled. -> exit 4 */
    TIMEOUT,

    /** The far side answered with a non-2xx status, or with nothing. -> exit 5 */
    REMOTE,

    /** The destination could not be created or written. -> exit 6 */
    LOCAL
}

/** Outcome of one `dl`. A failure carries the byte count so a half file is never a mystery. */
internal sealed class DlOutcome {
    class Ok(val bytes: Long, val path: String) : DlOutcome()
    class Failed(val reason: String, val written: Long, val kind: DlFail = DlFail.REMOTE) : DlOutcome()
}

internal object DownloadPlan {

    /** Public, no-permission-needed destination (see [DlDownloader] for why MediaStore). */
    const val PUBLIC_DIR = "Movies"

    /** What the operator types into VLC; also what `dl` prints. */
    const val PUBLIC_PATH_PREFIX = "/sdcard/Movies/"

    /** 2 GB: bigger than any film the family will watch, small enough to not fill the disk. */
    const val MAX_BYTES = 2L * 1024L * 1024L * 1024L

    const val TIMEOUT_DEFAULT = 600
    const val TIMEOUT_MAX = 3600

    /** Long names break on FAT-ish paths and in the MediaStore display-name column. */
    const val NAME_MAX_CHARS = 96

    private const val FALLBACK_PREFIX = "download-"
    private const val FALLBACK_SUFFIX = ".mp4"

    /** Extensions VLC opens by itself; used to decide whether to scan. */
    private val MEDIA_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp", "ts", "flv", "wmv", "mpg", "mpeg",
        "mp3", "m4a", "flac", "aac", "ogg", "wav", "opus"
    )

    /**
     * Clean one file name down to something safe inside [PUBLIC_DIR].
     *
     * Only the **last** path segment survives, so `../../etc/passwd` becomes
     * `passwd` and cannot escape the directory (and no form of the input can
     * ever contain a separator afterwards). Separators, control characters and
     * the characters Windows / FAT reject all collapse to `_`. A name that
     * cleans down to nothing returns null, and the caller falls back to
     * [guessName].
     */
    fun sanitizeName(raw: String?): String? {
        if (raw == null) return null
        var value = raw.trim()
        if (value.isEmpty()) return null
        val slash = maxOf(value.lastIndexOf('/'), value.lastIndexOf('\\'))
        if (slash >= 0) value = value.substring(slash + 1)
        val cleaned = StringBuilder(value.length)
        for (ch in value) {
            when {
                ch.code < 0x20 || ch.code == 0x7f -> cleaned.append('_')
                ch.isWhitespace() -> cleaned.append('_')
                ch == '/' || ch == '\\' || ch == ':' || ch == '*' || ch == '?' ||
                    ch == '"' || ch == '<' || ch == '>' || ch == '|' -> cleaned.append('_')
                else -> cleaned.append(ch)
            }
        }
        var result = cleaned.toString()
        // A leading dot hides the file from galleries and from a person browsing.
        while (result.startsWith(".") || result.startsWith("-")) result = result.substring(1)
        if (result.isEmpty()) return null
        if (result.length > NAME_MAX_CHARS) result = truncateKeepingExtension(result)
        return if (result.isEmpty()) null else result
    }

    /**
     * Last segment of the URL path, or `download-<stamp>.mp4`.
     *
     * Query strings are dropped first (a signed S3 link is unreadable as a name),
     * the segment must look like a file with an extension, and anything else
     * falls back instead of guessing.
     */
    fun guessName(url: String, stamp: Long): String {
        val fallback = FALLBACK_PREFIX + stamp + FALLBACK_SUFFIX
        var path = url.trim()
        val schemeEnd = path.indexOf("://")
        if (schemeEnd >= 0) path = path.substring(schemeEnd + 3)
        val authorityEnd = path.indexOf('/')
        if (authorityEnd < 0) return fallback
        path = path.substring(authorityEnd)
        val cut = path.indexOfFirst { it == '?' || it == '#' }
        if (cut >= 0) path = path.substring(0, cut)
        val segment = path.substring(path.lastIndexOf('/') + 1)
        if (segment.isEmpty()) return fallback
        if (!segment.contains('.')) return fallback
        val cleaned = sanitizeName(segment) ?: return fallback
        return if (cleaned.contains('.')) cleaned else fallback
    }

    /** The name `dl` actually uses: the operator's, if it survives cleaning. */
    fun resolveName(url: String, explicit: String?, stamp: Long): String =
        sanitizeName(explicit) ?: guessName(url, stamp)

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    /** True for containers VLC opens on its own, so a media scan is worth it. */
    fun isMedia(name: String): Boolean = MEDIA_EXTENSIONS.contains(extensionOf(name))

    /** Called on every chunk: the transfer aborts *before* the disk fills up. */
    fun overLimit(bytes: Long, maxBytes: Long = MAX_BYTES): Boolean = bytes > maxBytes

    fun publicPath(name: String): String = PUBLIC_PATH_PREFIX + name

    /** `已下载 <文件名> <字节数> 字节 → /sdcard/Movies/<文件名>` */
    fun success(name: String, bytes: Long): String =
        "已下载 " + name + " " + bytes + " 字节 → " + publicPath(name)

    /** `错误: 下载失败: <原因>（已写 <n> 字节）` — the count is what makes a retry decidable. */
    fun failure(reason: String, written: Long): String =
        Admin.ERROR_PREFIX + "下载失败: " + reason + "（已写 " + written + " 字节）"

    /** A non-2xx answer is reported with its status code, never as a generic failure. */
    fun httpFailure(code: Int): String = "HTTP " + code

    fun sizeFailure(maxBytes: Long = MAX_BYTES): String =
        "文件超过 " + (maxBytes / 1024L / 1024L) + "MB"

    fun timeoutFailure(seconds: Int): String = "超过 " + seconds + " 秒没有下完"

    private fun truncateKeepingExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 8) name.substring(dot) else ""
        val keep = NAME_MAX_CHARS - ext.length
        if (keep <= 0) return name.substring(0, NAME_MAX_CHARS)
        return name.substring(0, keep) + ext
    }
}
