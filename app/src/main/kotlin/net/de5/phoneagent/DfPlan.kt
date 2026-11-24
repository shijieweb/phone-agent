package net.de5.phoneagent

/**
 * `files df` (SPEC.md section 3.7).
 *
 * The numbers come from [android.os.StatFs]; the *sentence* lives here because
 * the rounding and the percentage are the parts an operator compares against
 * the settings screen, and they must not drift between builds.
 */
internal object DfPlan {

    /** `总 104.0 GB 已用 53.0 GB 可用 51.0 GB (51%)` */
    fun report(total: Long, used: Long, free: Long): String {
        val safeTotal = if (total < 0L) 0L else total
        val safeUsed = if (used < 0L) 0L else used
        val safeFree = if (free < 0L) 0L else free
        return "总 " + FileGuard.formatSize(safeTotal) +
            " 已用 " + FileGuard.formatSize(safeUsed) +
            " 可用 " + FileGuard.formatSize(safeFree) +
            " (" + percent(safeUsed, safeTotal) + "%)"
    }

    /** Rounded to whole percent; an empty or unknown volume is 0, never NaN. */
    fun percent(used: Long, total: Long): Int {
        if (total <= 0L) return 0
        if (used <= 0L) return 0
        val raw = used.toDouble() * 100.0 / total.toDouble()
        return Math.round(raw).toInt().coerceIn(0, 100)
    }

    fun failure(reason: String): String = Admin.ERROR_PREFIX + "读取容量失败: " + reason
}
