package net.de5.phoneagent

/**
 * 「视频转音乐」的纯逻辑：目录、扩展名、句子（v0.7.0-pad12）。
 *
 * 为什么单独一个文件：和 DfPlan 一样，句子是操作员会去对设置页/文件管理器的
 * 部分，不能随版本漂移。这里不 import 任何 android.*，所以整块能在普通 JVM 上
 * 单测（见 Mp3PlanTest）。
 *
 * 编码本身不在这个文件：解码走 Mp3Converter（MediaCodec），编码走
 * LameMp3Encoder（jump3r，纯 Java）。
 */
internal object Mp3Plan {

    /** 唯一的源目录，也是唯一的落盘目录：转好的 mp3 就躺在视频旁边。 */
    const val DEFAULT_DIR = "/sdcard/视频转音乐专用"

    /** 输出固定 44.1 kHz / 128 kbps：和 SPEC 里写的一致，换参数要先改文档。 */
    const val OUT_RATE = 44100
    const val BITRATE = 128

    /** 认这些后缀。不含 mp3/m4a/wav：那是音频，不是本命令的输入。 */
    val EXTENSIONS = setOf(
        "mp4", "mov", "mkv", "avi", "flv", "m4v", "3gp", "webm", "ts", "mpg"
    )

    const val USAGE = "usage: mp3 scan | mp3 run [apply] [force]"

    /**
     * 一个候选文件的事实。
     *
     * hasAudio 由 MediaExtractor 说了算，不靠后缀猜：mkv 里可以只有视频轨。
     * targetExists 只看同名 .mp3 在不在，不看它是不是这次转的。
     */
    class Row(
        val name: String,
        val bytes: Long,
        val durationMs: Long,
        val hasAudio: Boolean,
        val targetExists: Boolean
    )

    /** 后缀判断：小写、取最后一个点之后。没有点、点在最前（.mp4 这种隐藏文件）都算不是。 */
    fun isVideoName(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return false
        return name.substring(dot + 1).lowercase() in EXTENSIONS
    }

    /** 生日.mp4 → 生日.mp3；本来就没有后缀就补一个。 */
    fun targetName(name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        return base + ".mp3"
    }

    /** 3:01 / 1:02:03；时长读不出来（0 或负）时给问号，别假装是 0 秒。 */
    fun duration(millis: Long): String {
        if (millis <= 0L) return "?"
        val total = millis / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) h.toString() + ":" + two(m) + ":" + two(s) else m.toString() + ":" + two(s)
    }

    private fun two(value: Long): String = if (value < 10L) "0" + value else value.toString()

    /** 秒，恒一位小数（3.5s）。负的按 0 处理，别输出 -0.0s。 */
    fun seconds(millis: Long): String {
        val safe = if (millis < 0L) 0L else millis
        val tenths = Math.round(safe / 100.0)
        return (tenths / 10L).toString() + "." + (tenths % 10L).toString() + "s"
    }

    /** 0..100，给界面进度行用；总数未知时给 0。 */
    fun percent(done: Long, total: Long): Int {
        if (total <= 0L) return 0
        if (done <= 0L) return 0
        val raw = done.toDouble() * 100.0 / total.toDouble()
        return Math.round(raw).toInt().coerceIn(0, 100)
    }

    fun pendingCount(rows: List<Row>): Int = rows.count { it.hasAudio && !it.targetExists }
    fun existingCount(rows: List<Row>): Int = rows.count { it.hasAudio && it.targetExists }
    fun noAudioCount(rows: List<Row>): Int = rows.count { !it.hasAudio }

    /**
     * 这一批到底转谁：有音轨，且（[force] 或还没有同名 mp3）。
     *
     * 这条判据有三个人要看——dry-run 那句话、批量循环、界面上的「待转 N 个」。
     * 三处必须同一个答案，所以它只在这里定义一次，另外两处调它。
     */
    fun work(rows: List<Row>, force: Boolean): List<Row> =
        rows.filter { it.hasAudio && (force || !it.targetExists) }

    /**
     * 一个都不转的文件的**总数**，含无音轨的。
     *
     * dry-run 那一行只有「将转 / 跳过」两栏，没有无音轨这一栏，所以这里必须把
     * 无音轨也算进跳过，否则两个数加起来对不上文件总数，看的人会以为漏了一个。
     */
    fun skippedTotal(rows: List<Row>, force: Boolean): Int =
        rows.size - work(rows, force).size

    /**
     * 只数「因为已经有 mp3 才跳过」的那些，**不含无音轨**。
     *
     * 收尾的汇总行把无音轨单独列一栏，所以那里的「跳过」不能重复数它——同一份
     * 清单在两句话里用两个口径，是因为这两句话本来就在回答不同的问题。
     */
    fun skippedExisting(rows: List<Row>, force: Boolean): Int =
        rows.size - work(rows, force).size - noAudioCount(rows)

    /**
     * mp3 scan 的正文：目录、三个数、然后一个文件一行。
     *
     * 一个文件一行而不是一张表：这些行最终是在手机上看（或者贴给操作员），
     * 定宽表格在窄屏上会折行折成一团。
     */
    fun scanReport(dir: String, rows: List<Row>): String {
        if (rows.isEmpty()) {
            return "目录 " + dir + "\n没有视频文件（认这些后缀: " +
                EXTENSIONS.sorted().joinToString("/") + "）"
        }
        val text = StringBuilder()
        text.append("目录 ").append(dir).append('\n')
        text.append("视频 ").append(rows.size).append(" 个：待转 ")
            .append(pendingCount(rows)).append("，已有 mp3 ")
            .append(existingCount(rows)).append("，无音轨 ")
            .append(noAudioCount(rows))
        var index = 1
        for (row in rows) {
            text.append('\n').append(index).append(". ").append(row.name)
            text.append(' ').append(FileGuard.formatSize(row.bytes))
            text.append(' ').append(duration(row.durationMs))
            text.append(if (row.hasAudio) " 有音轨" else " 无音轨")
            text.append(" → ").append(state(row))
            index++
        }
        return text.toString()
    }

    /** 单个文件的一句话状态，scan 和 run 共用，免得两处说法不一样。 */
    fun state(row: Row): String = when {
        !row.hasAudio -> "跳过（无音轨）"
        row.targetExists -> "跳过（已有 mp3）"
        else -> "待转"
    }

    /**
     * mp3 run 不 apply 时的那一行：先把要发生的事说清楚，再让人决定。
     *
     * 「将转 N 个，跳过 M 个」是硬要求（SPEC 与老板的话都这么写），
     * 所以这两个数字必须出现在同一行里。
     */
    fun dryRunLine(rows: List<Row>, force: Boolean): String =
        "dry-run: 将转 " + work(rows, force).size + " 个，跳过 " + skippedTotal(rows, force) +
            " 个" + (if (force) "（force: 已有 mp3 也重转）" else "") + "；加 apply 真正开始"

    /** [1/2] 生日.mp4 … 一行，转换开始前打，让人知道卡在哪一个上。 */
    fun startLine(index: Int, total: Int, name: String): String =
        "[" + index + "/" + total + "] " + name + " …"

    /** 界面上的进度行：第几个/共几个 + 文件名 + 百分比。 */
    fun progressLine(index: Int, total: Int, name: String, percent: Int): String =
        "[" + index + "/" + total + "] " + name + " " + percent.coerceIn(0, 100) + "%"

    /** 单个文件转完：说清产出多大、花了多久。 */
    fun resultLine(index: Int, total: Int, name: String, outBytes: Long, elapsedMs: Long): String =
        "[" + index + "/" + total + "] " + name + " → " + targetName(name) + " " +
            FileGuard.formatSize(outBytes) + " 用时 " + seconds(elapsedMs)

    fun failedLine(index: Int, total: Int, name: String, reason: String): String =
        "[" + index + "/" + total + "] " + name + " 失败: " + reason

    /** 收尾那一行：四个数字都在这儿，少一个就得回去翻上面的行。 */
    fun summaryLine(done: Int, failed: Int, skipped: Int, noAudio: Int, elapsedMs: Long): String =
        "完成 " + done + " 个，失败 " + failed + " 个，跳过 " + skipped +
            " 个，无音轨 " + noAudio + " 个，用时 " + seconds(elapsedMs)

    /** 出错统一带 Admin.ERROR_PREFIX，Executor 靠这个前缀判失败。 */
    fun failure(reason: String): String = Admin.ERROR_PREFIX + "视频转音乐失败: " + reason
}
