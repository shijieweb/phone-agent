package net.de5.phoneagent

import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Parallel synthesis with strictly ordered delivery (v0.11.3-pad12, SPEC.md §3.30).
 *
 * The problem this exists for: Fish TTS costs about 0.10 s per character, the
 * player reads Chinese at 4-5 characters a second, and `speak` used to
 * synthesize on a single thread. A 120-character chunk therefore needed ~12 s to
 * synthesize while the 40-character chunk in front of it played for ~10 s, so
 * **every sentence boundary was a several-second silence** -- exactly where a
 * listener notices a gap.
 *
 * Now [workers] chunks are synthesized at the same time, but the player still
 * receives them **in index order**, one at a time: the audio is a sentence
 * sequence, so out-of-order delivery would be gibberish. Two bounds keep the
 * parallelism honest:
 *
 *  - [workers] chunks may be in the network at once (3: the VPS answers one
 *    request in ~12 s worst case, and three at a time is what it already
 *    serves without queueing);
 *  - [capacity] chunks may be finished and waiting for the player (4). Past
 *    that a synthesizer blocks, so a 30-chunk paragraph never buffers 30 MP3s.
 *
 * A value for which [stopsHere] is true is a failure: it is still delivered in
 * its own position (so the receipt can say "played 2 of 3"), no index after it
 * is started, and the next [take] returns null. A [task] that throws is
 * delivered as [SynthDelivery.Crashed] in the same position, because a hang
 * would be worse than a bad receipt.
 *
 * Every wait is bounded, on purpose. The upstream service is not reliable per
 * request -- 10 serial syntheses measured 2.3-2.9 s each except the 7th, which
 * took **80.8 s** -- so [take] accepts a budget and answers
 * [SynthDelivery.Stalled] instead of blocking forever when a worker wedges.
 *
 * Pure JDK: one lock, one condition, one semaphore. No executor, no dependency.
 * That is also what makes it testable -- [task] is a plain lambda, so a test can
 * make chunk 0 slow and chunk 1 fast and assert what order comes out.
 *
 * @param total how many chunks there are; delivery covers `0 until total`.
 * @param workers how many synthesizers run at once (clamped to [total]).
 * @param capacity how many chunks may be finished but not yet taken.
 * @param threadName prefix for the synthesizer threads (`speak-synth-0` ...).
 * @param task synthesizes chunk `index`; called once per index, on a worker.
 * @param stopsHere true for a value that ends the run after its own delivery.
 */
internal class SynthBoard<T : Any>(
    private val total: Int,
    private val workers: Int,
    private val capacity: Int,
    private val threadName: String,
    private val task: (Int) -> T,
    private val stopsHere: (T) -> Boolean
) {

    init {
        require(total >= 0) { "total must not be negative" }
        require(workers >= 1) { "workers must be at least 1" }
        require(capacity >= 1) { "capacity must be at least 1" }
    }

    /** One result, in index order. */
    private sealed class Entry<T : Any> {
        class Ok<T : Any>(val value: T) : Entry<T>()
        class Bad<T : Any>(val error: Throwable) : Entry<T>()
    }

    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private val permits = Semaphore(capacity)
    private val results = arrayOfNulls<Entry<T>>(total)
    private val threads = ArrayList<Thread>(minOf(workers, total))

    private var nextToStart = 0
    private var nextToDeliver = 0
    private var stopAt = total
    private var halted = false
    private var closed = false

    /** Start the synthesizers. Call [take] on this thread while they run. */
    fun start() {
        val count = minOf(workers, total)
        for (index in 0 until count) {
            val thread = Thread({ work() }, threadName + "-" + index)
            thread.isDaemon = true
            threads.add(thread)
        }
        for (thread in threads) thread.start()
    }

    /**
     * The next chunk in index order, or null when the run is over.
     *
     * Blocks while the chunk is still being synthesized, but never longer than
     * [timeoutMs]: past that the run is reported as [SynthDelivery.Stalled] at
     * the chunk that never arrived. Null means every index was delivered, a
     * [stopsHere] value was delivered, [stop] was called, or the wait expired --
     * so a caller that keeps taking never hangs on a wedged worker.
     */
    fun take(timeoutMs: Long = Long.MAX_VALUE): SynthDelivery<T>? {
        val began = System.nanoTime()
        val deadline =
            if (timeoutMs == Long.MAX_VALUE) Long.MAX_VALUE else began + timeoutMs * 1_000_000L
        lock.lock()
        try {
            while (!closed && !halted && nextToDeliver < total) {
                val entry = results[nextToDeliver]
                if (entry == null) {
                    if (deadline == Long.MAX_VALUE) {
                        ready.await()
                    } else {
                        val left = deadline - System.nanoTime()
                        if (left <= 0L) {
                            val index = nextToDeliver
                            halted = true
                            stopAt = minOf(stopAt, index)
                            return SynthDelivery.Stalled(index, (System.nanoTime() - began) / 1_000_000L)
                        }
                        ready.awaitNanos(left)
                    }
                    continue
                }
                results[nextToDeliver] = null
                val index = nextToDeliver
                nextToDeliver++
                // The player is one chunk further along, so one more chunk may
                // be synthesized.
                permits.release()
                when (entry) {
                    is Entry.Bad -> {
                        halted = true
                        stopAt = minOf(stopAt, index)
                        return SynthDelivery.Crashed(index, entry.error)
                    }
                    is Entry.Ok -> {
                        val value = entry.value
                        if (stopsHere(value)) {
                            halted = true
                            stopAt = minOf(stopAt, index)
                        }
                        return SynthDelivery.Value(index, value)
                    }
                }
            }
            return null
        } finally {
            lock.unlock()
        }
    }

    /**
     * Interrupt the synthesizers and make every later [take] return null.
     *
     * The joins are bounded ([JOIN_MS] each): a worker can be stuck in a socket
     * read that ignores interrupts, and it is a daemon thread, so waiting for it
     * forever would turn one dead connection into a hung command.
     */
    fun stop() {
        val toStop = lock.withLock {
            closed = true
            ready.signalAll()
            ArrayList(threads)
        }
        for (thread in toStop) thread.interrupt()
        for (thread in toStop) {
            try {
                thread.join(JOIN_MS)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** One synthesizer: claim an index, do it, publish it. */
    private fun work() {
        while (true) {
            try {
                permits.acquire()
            } catch (t: InterruptedException) {
                return
            }
            val index = lock.withLock {
                if (closed || halted || nextToStart >= stopAt) -1 else nextToStart++
            }
            if (index < 0) {
                permits.release()
                return
            }
            val entry: Entry<T> = try {
                Entry.Ok(task(index))
            } catch (t: Throwable) {
                Entry.Bad(t)
            }
            // Asked outside the lock: the predicate belongs to the caller and a
            // slow one must not stall delivery.
            val terminal = entry is Entry.Ok && stopsHere(entry.value)
            lock.withLock {
                results[index] = entry
                if (entry is Entry.Bad || terminal) stopAt = minOf(stopAt, index)
                ready.signalAll()
            }
        }
    }

    private companion object {
        /** How long [stop] waits for a stuck worker before giving up on it. */
        const val JOIN_MS = 1_000L
    }
}

/**
 * One chunk as it comes off a [SynthBoard], in index order.
 *
 * Three shapes on purpose: a chunk that failed to synthesize is a value the
 * caller can describe ("服务端 502"), a chunk whose synthesis threw is a bug and
 * keeps its stack for the log, and a chunk that never arrived is a wedged
 * worker -- each has a different sentence to show the operator.
 */
internal sealed class SynthDelivery<out T> {

    /** Chunk [index] is ready. */
    class Value<T : Any>(val index: Int, val value: T) : SynthDelivery<T>()

    /** Chunk [index] threw instead of returning a value. */
    class Crashed(val index: Int, val error: Throwable) : SynthDelivery<Nothing>()

    /** Chunk [index] did not arrive within the caller's budget ([waitedMs]). */
    class Stalled(val index: Int, val waitedMs: Long) : SynthDelivery<Nothing>()
}
