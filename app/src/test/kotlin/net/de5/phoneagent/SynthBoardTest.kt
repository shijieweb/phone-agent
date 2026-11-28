package net.de5.phoneagent

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SynthBoard] (v0.11.3-pad12, SPEC.md §3.30).
 *
 * The bug this fixes is a silence, not an error: a single synthesizer could not
 * keep up with the player, so every sentence boundary waited ~5 s. Parallel
 * synthesis is the fix, and the danger it introduces is **order** -- the audio
 * is a sentence sequence, so a chunk delivered early is gibberish.
 *
 * These tests therefore pin four things, none of which a phone can show
 * without a stopwatch:
 *
 *  - delivery is in index order even when a later chunk finishes first;
 *  - the look-ahead bound really bounds (a synthesizer blocks instead of
 *    buffering the whole text);
 *  - a failure is delivered in its own position and stops the run there;
 *  - a wedged synthesizer is reported, not waited on forever (the upstream
 *    service took 80.8 s for 1 request in 10).
 *
 * `task` is a plain lambda, so no phone, no network and no MediaPlayer is
 * involved; only real threads, which is the part being tested.
 */
class SynthBoardTest {

    private fun board(
        total: Int,
        workers: Int = 3,
        capacity: Int = 4,
        task: (Int) -> String
    ): SynthBoard<String> = SynthBoard(total, workers, capacity, "test-synth", task, ::isFailure)

    /** The same shape FeatSpeak uses: a value that names its own failure. */
    private fun isFailure(value: String): Boolean = value.startsWith("fail")

    /** Everything the board delivers, in the order it delivers it. */
    private fun drain(board: SynthBoard<String>, budgetMs: Long = Long.MAX_VALUE): List<String> {
        val out = ArrayList<String>()
        while (true) {
            when (val item = board.take(budgetMs) ?: break) {
                is SynthDelivery.Crashed -> out.add("crash@" + item.index)
                is SynthDelivery.Stalled -> out.add("stall@" + item.index)
                is SynthDelivery.Value -> out.add(item.value)
            }
        }
        return out
    }

    @Test
    fun deliversInIndexOrderEvenWhenTheFirstChunksAreTheSlowest() {
        // Chunk 0 takes 15x longer than the rest, so a board that delivered on
        // completion would hand back c5 first.
        val board = board(total = 6) { index ->
            Thread.sleep(if (index == 0) 150L else 10L)
            "c" + index
        }
        board.start()
        val out = drain(board)
        board.stop()
        assertEquals(listOf("c0", "c1", "c2", "c3", "c4", "c5"), out)
    }

    @Test
    fun everyChunkIsSynthesizedExactlyOnce() {
        val calls = Array(9) { AtomicInteger(0) }
        val board = board(total = 9) { index ->
            calls[index].incrementAndGet()
            "c" + index
        }
        board.start()
        drain(board)
        board.stop()
        assertTrue(calls.all { it.get() == 1 })
    }

    @Test
    fun threeChunksAreSynthesizedAtTheSameTime() {
        // The whole point of v0.11.3: one synthesizer cannot keep up with the
        // player, three can. A single-threaded board reports a peak of 1.
        val live = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val board = board(total = 6) { index ->
            val now = live.incrementAndGet()
            peak.accumulateAndGet(now) { seen, candidate -> maxOf(seen, candidate) }
            Thread.sleep(120L)
            live.decrementAndGet()
            "c" + index
        }
        board.start()
        drain(board)
        board.stop()
        assertEquals(3, peak.get())
    }

    @Test
    fun lookAheadIsBoundedByTheCapacity() {
        // Nothing is taken for 300 ms. With capacity 2 at most two chunks may be
        // synthesized; a board without the bound would have done all eight.
        val started = AtomicInteger(0)
        val board = board(total = 8, workers = 3, capacity = 2) { index ->
            started.incrementAndGet()
            Thread.sleep(20L)
            "c" + index
        }
        board.start()
        Thread.sleep(300L)
        val whileIdle = started.get()
        assertTrue("started while idle: " + whileIdle, whileIdle in 1..2)
        val out = drain(board)
        board.stop()
        assertEquals(8, out.size)
        assertEquals((0 until 8).map { "c" + it }, out)
    }

    @Test
    fun aFailureIsDeliveredInItsOwnPositionAndEndsTheRun() {
        val board = board(total = 5) { index -> if (index == 2) "fail 502" else "c" + index }
        board.start()
        val out = drain(board)
        board.stop()
        // c3 and c4 may well have been synthesized already; they must not be
        // delivered, because the listener would hear them after a gap.
        assertEquals(listOf("c0", "c1", "fail 502"), out)
    }

    @Test
    fun afterAFailureNoLaterChunkIsStarted() {
        val started = ConcurrentLinkedQueue<Int>()
        val board = board(total = 10, workers = 1, capacity = 8) { index ->
            started.add(index)
            if (index == 2) "fail" else "c" + index
        }
        board.start()
        drain(board)
        board.stop()
        assertEquals(listOf(0, 1, 2), started.toList())
    }

    @Test
    fun aThrowingChunkIsDeliveredAsACrashInItsOwnPosition() {
        val board = board(total = 4) { index ->
            if (index == 1) throw IllegalStateException("boom")
            "c" + index
        }
        board.start()
        val out = drain(board)
        board.stop()
        assertEquals(listOf("c0", "crash@1"), out)
    }

    @Test
    fun aWedgedSynthesizerIsReportedInsteadOfWaitedOnForever() {
        // The upstream service does this: 1 request in 10 took 80.8 s. The
        // player must answer "stalled at chunk 1", not block for the rest of the
        // run -- and must not deliver chunk 2 either.
        val board = board(total = 3, workers = 1) { index ->
            if (index == 1) Thread.sleep(10_000L)
            "c" + index
        }
        board.start()
        val first = board.take(5_000L)
        assertEquals("c0", (first as SynthDelivery.Value).value)
        val stalled = board.take(200L)
        assertTrue("expected a stall, got " + stalled, stalled is SynthDelivery.Stalled)
        assertEquals(1, (stalled as SynthDelivery.Stalled).index)
        assertTrue(stalled.waitedMs >= 190L)
        assertNull(board.take(200L))
        board.stop()
    }

    @Test
    fun anEmptyTextDeliversNothingAndSpinsNoThread() {
        val board = board(total = 0) { "c" + it }
        board.start()
        assertNull(board.take())
        board.stop()
    }

    @Test
    fun stopUnblocksAConsumerThatIsStillWaiting() {
        val board = board(total = 4, workers = 1) { index ->
            Thread.sleep(10_000L)
            "c" + index
        }
        board.start()
        val taken = arrayOfNulls<SynthDelivery<String>>(1)
        val consumer = Thread({ taken[0] = board.take() }, "test-consumer")
        consumer.isDaemon = true
        consumer.start()
        Thread.sleep(100L)
        board.stop()
        consumer.join(2_000L)
        assertTrue("consumer still waiting", !consumer.isAlive)
        assertNull(taken[0])
    }

    @Test
    fun stopDoesNotWaitForeverForAWorkerStuckInASocketRead() {
        // A real HttpURLConnection read ignores interrupts; a stop() that joined
        // without a timeout would hang the command on one dead connection.
        val release = java.util.concurrent.CountDownLatch(1)
        val board = board(total = 4, workers = 1) { index ->
            release.await()
            "c" + index
        }
        board.start()
        val began = System.currentTimeMillis()
        board.stop()
        val elapsed = System.currentTimeMillis() - began
        release.countDown()
        assertTrue("stop took " + elapsed + " ms", elapsed < 4_000L)
    }

    @Test
    fun aLongRunKeepsOrderWithoutDeadlocking() {
        // 40 chunks through a capacity of 4: if a permit were ever leaked this
        // test would hang instead of failing, which is why it also asserts the
        // count.
        val board = board(total = 40, workers = 3, capacity = 4) { index ->
            Thread.sleep((index % 3).toLong())
            "c" + index
        }
        board.start()
        val out = drain(board)
        board.stop()
        assertEquals(40, out.size)
        assertEquals((0 until 40).map { "c" + it }, out)
    }

    @Test
    fun moreWorkersThanChunksIsNotAnError() {
        val live = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val board = board(total = 2, workers = 3) { index ->
            val now = live.incrementAndGet()
            peak.accumulateAndGet(now) { seen, candidate -> maxOf(seen, candidate) }
            Thread.sleep(100L)
            live.decrementAndGet()
            "c" + index
        }
        board.start()
        val out = drain(board)
        board.stop()
        assertEquals(listOf("c0", "c1"), out)
        assertEquals(2, peak.get())
    }
}
