package net.de5.phoneagent

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 批量转换的循环（v0.7.0-pad12）。
 *
 * 为什么单独一个对象：同一段循环有两个门要进——mp3 run apply 从 relay 进来，
 * 「视频转音乐」页从按钮进来。抄两份的话，一边修好的判据（跳过谁、force 怎么算、
 * 失败怎么记账）另一边会悄悄落后，操作员看到的清单和实转结果就对不上了。
 *
 * 每转一个文件套一层超时：解码器卡在一个坏容器上不会自己返回，而这里是最容易
 * 卡死的地方。超时按「这一个失败」记，继续下一个——一个坏文件不该让整批停摆。
 */
internal object Mp3Runner {

    /** 一次批量的结果。句子已经全部在 [lines] 里，调用方只负责输出。 */
    class Outcome(
        val lines: List<String>,
        val done: Int,
        val failed: Int,
        val skipped: Int,
        val noAudio: Int,
        val elapsedMs: Long
    )

    /**
     * 按 [rows] 真转一遍。[force] 为真时连已有同名 mp3 的也重转。
     *
     * [onLine] 每出一行调一次（界面拿它实时刷新）；为 null 时只攒进结果。
     * 返回的 [Outcome.lines] 是最终的全量清单，实时刷新的界面可以直接用它收尾。
     */
    fun run(
        dir: String,
        rows: List<Mp3Plan.Row>,
        force: Boolean,
        onLine: ((String) -> Unit)? = null
    ): Outcome {
        // 判据只有一份，在 Mp3Plan.work / skippedTotal / skippedExisting：dry-run
        // 那句话和界面上的「待转 N 个」读的也是它，所以清单和实活不可能对不上。
        val todo = Mp3Plan.work(rows, force)
        val noAudio = Mp3Plan.noAudioCount(rows)
        val skipped = Mp3Plan.skippedExisting(rows, force)
        val began = System.currentTimeMillis()
        val lines = ArrayList<String>()
        val lock = Any()

        fun emit(line: String) {
            synchronized(lock) { lines.add(line) }
            onLine?.invoke(line)
        }

        if (todo.isEmpty()) {
            return Outcome(emptyList(), 0, 0, skipped, noAudio, 0L)
        }

        var done = 0
        var failed = 0
        for ((index, row) in todo.withIndex()) {
            val position = index + 1
            val source = File(dir, row.name)
            val target = File(dir, Mp3Plan.targetName(row.name))
            emit(Mp3Plan.startLine(position, todo.size, row.name))
            AgentLog.add("mp3", "开始 " + row.name)

            var lastStep = -1
            val attempt = await(FILE_TIMEOUT_MS) {
                try {
                    val converted = Mp3Converter.convert(source, target) { percent ->
                        // 每跨过 10% 的整数档记一行，100% 留给结果行。
                        val step = percent / 10
                        if (step != lastStep && percent < 100) {
                            lastStep = step
                            emit(Mp3Plan.progressLine(position, todo.size, row.name, percent))
                        }
                    }
                    Attempt(converted, null)
                } catch (t: Throwable) {
                    Attempt(null, describe(t))
                }
            }

            if (attempt == null) {
                failed++
                emit(Mp3Plan.failedLine(position, todo.size, row.name, "转换超时"))
                AgentLog.add("mp3", "超时 " + row.name)
                continue
            }
            val converted = attempt.value
            if (converted == null) {
                failed++
                emit(Mp3Plan.failedLine(position, todo.size, row.name, attempt.error ?: "未知错误"))
                AgentLog.add("mp3", "失败 " + row.name + ": " + (attempt.error ?: ""))
                continue
            }
            done++
            emit(Mp3Plan.resultLine(position, todo.size, row.name, converted.bytes, converted.elapsedMs))
            AgentLog.add("mp3", "完成 " + row.name + " " + converted.bytes + " 字节")
        }

        val elapsed = System.currentTimeMillis() - began
        emit(Mp3Plan.summaryLine(done, failed, skipped, noAudio, elapsed))
        val snapshot = synchronized(lock) { ArrayList(lines) }
        return Outcome(snapshot, done, failed, skipped, noAudio, elapsed)
    }

    /** [await] 的返回值：成功带结果，失败带原因。 */
    private class Attempt(
        val value: Mp3Converter.ConvertResult?,
        val error: String?
    )

    /** 跑 [block] 并最多等 [timeoutMs]；超时返回 null。块里的异常自己接住。 */
    private fun await(timeoutMs: Long, block: () -> Attempt): Attempt? {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<Attempt?>(null)
        val thread = Thread({
            holder.set(
                try {
                    block()
                } catch (t: Throwable) {
                    Attempt(null, describe(t))
                }
            )
            latch.countDown()
        }, "mp3-convert")
        thread.isDaemon = true
        try {
            thread.start()
        } catch (t: Throwable) {
            return Attempt(null, describe(t))
        }
        return try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) null else holder.get()
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            Attempt(null, "被中断")
        }
    }

    /**
     * 单个文件的预算。本机实测编码 43 倍速，瓶颈在解码；就算一个三小时的视频
     * 也该在几分钟内完成，十分钟是安全上限。
     */
    private const val FILE_TIMEOUT_MS = 600_000L
}
