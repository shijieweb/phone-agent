package net.de5.phoneagent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 量一次 /data/local/tmp/phoneagent-guard.log（v0.5.8-pad12）。
 *
 * 单独一个对象，因为有两个调用方：relay 上的 `logs size` / `logs trim`
 * （[CommandExecutor]）和调试页顶部那一行摘要（[DebugActivity] 每 15 秒量一次）。
 * 两边必须用同一套超时与同一套「读不到就说读不到」的判定，否则同一个文件会
 * 在两个地方报出不同的数字。
 *
 * 只碰 [SelfAdb]，不碰 UI；**调用方必须在后台线程**（[read] 会阻塞到 shell
 * 回来或超时）。
 */
internal object GuardProbe {

    /** wc/tail 对几 KB 的文件是瞬间的事，10 秒已经是「链路不对」的量级。 */
    private const val TIMEOUT_MS = 10_000L

    /** 看门狗余量：哨兵要等命令自己结束，边界上不能误判超时。 */
    private const val SLACK_MS = 5_000L

    /**
     * 读一次。
     *
     * 第二个值是**读不到的原因**，原样念给操作员听——读不到就说读不到
     * （没配对 / 超时 / 文件还没建），绝不用 0 字节糊过去。
     */
    fun read(): Pair<LogSizes.Guard?, String?> {
        val exit = offMain(TIMEOUT_MS + SLACK_MS) {
            SelfAdb.shell(LogSizes.readCommand(), TIMEOUT_MS)
        } ?: return Pair(null, "读取超时（" + TIMEOUT_MS + "ms 内没有结果）")
        if (exit.precondition) return Pair(null, exit.text)
        if (exit.code == null) return Pair(null, SelfAdbPlan.timeoutText(TIMEOUT_MS))
        val guard = LogSizes.parseGuard(exit.text)
        if (guard != null) return Pair(guard, null)
        if (exit.text.contains("MISSING")) return Pair(null, "文件还没建，守护脚本还没 arm 过")
        return Pair(null, "回执里没有字节数，shell 可能没跑起来")
    }

    /**
     * 裁一次守护日志。返回 true 表示**真拿到了回执**（不论裁没裁，看 [LogSizes.trimStatus]），
     * false 表示命令没跑成——调用方据此决定要不要回读，而不是替裁剪成功吹牛。
     */
    fun trim(): Boolean {
        val exit = offMain(TIMEOUT_MS + SLACK_MS) {
            SelfAdb.shell(LogSizes.trimCommand(), TIMEOUT_MS)
        } ?: return false
        if (exit.precondition) return false
        if (exit.code == null) return false
        return LogSizes.trimStatus(exit.text) != null
    }

    /** 与 [CommandExecutor] 里那个同名方法同一套做法：daemon 线程 + 闩，超时返回 null。 */
    private fun <T> offMain(timeoutMs: Long, block: () -> T): T? {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<T?>(null)
        val thread = Thread {
            holder.set(
                try {
                    block()
                } catch (t: Throwable) {
                    null
                }
            )
            latch.countDown()
        }
        thread.isDaemon = true
        thread.name = "rc-guardprobe"
        try {
            thread.start()
        } catch (t: Throwable) {
            return null
        }
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        return holder.get()
    }
}
