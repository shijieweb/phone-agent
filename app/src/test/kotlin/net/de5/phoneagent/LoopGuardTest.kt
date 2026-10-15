package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The concurrent-poll defect, pinned down on a plain JVM.
 *
 * The shipped 0.5.0 loop decided whether to keep running by reading the *static*
 * `RcAgentService.running`. When a service instance was destroyed and a new one
 * started quickly, `onDestroy` cleared `running` and the next `start()` set it
 * back to `true` before the old thread had noticed. The old thread then saw
 * `running == true` again and never exited — two long polls on one agent.
 *
 * [LoopGuard] replaces that read: a thread carries the generation of the service
 * instance that spawned it, and a generation is never revived. These tests prove
 * the old thread exits *even though* the shared flag is `true` again, which is
 * precisely the case the old code got wrong.
 */
class LoopGuardTest {

    @Test
    fun aGuardKeepsRunningWhileItsOwnInstanceIsAlive() {
        val guard = LoopGuard()
        assertTrue(guard.isRunning())
        assertTrue("a freshly allocated generation is live", guard.generation > 0)
        assertEquals(LoopGuard.live(), guard.generation)
    }

    @Test
    fun stoppingAnInstanceEndsItsOwnGuard() {
        val guard = LoopGuard()
        guard.stop()
        assertFalse(guard.isRunning())
    }

    /**
     * The regression itself: the old thread's generation was stopped, then a new
     * instance started. The old guard must stay stopped — "the shared `running`
     * flag is true again" is exactly what used to resurrect it.
     */
    @Test
    fun aStoppedGuardStaysStoppedEvenAfterANewInstanceStarts() {
        val first = LoopGuard()
        val firstGeneration = first.generation
        first.stop()

        val second = LoopGuard()
        assertTrue("the new instance is running", second.isRunning())

        // The shared flag the old code consulted is true again; that must not
        // make the old guard alive.
        assertFalse(
            "the old generation must not be revived by a new start",
            first.isRunning()
        )
        assertFalse(
            "a superseded generation must not be revived",
            first.isRunning(firstGeneration)
        )
        assertTrue("the new generation is strictly newer", second.generation > firstGeneration)
    }

    @Test
    fun aLiveGenerationSoFarIsNotRunningOnceANewerOneExists() {
        val guard = LoopGuard()
        val stale = guard.generation
        guard.stop()
        val next = LoopGuard()
        assertFalse(guard.isRunning(stale))
        assertTrue(next.isRunning(next.generation))
    }

    /**
     * A generation that was never handed out by this guard must not be honoured,
     * otherwise a thread that outlived its service could still pass the check.
     */
    @Test
    fun aGenerationFromAnotherInstanceIsRejected() {
        val first = LoopGuard()
        val foreign = first.generation + 1
        assertTrue(first.isRunning())
        assertFalse("a generation we never issued is not running", first.isRunning(foreign))
    }

    @Test
    fun generationsAreUniqueAcrossInstances() {
        val seen = HashSet<Int>()
        val guards = ArrayList<LoopGuard>()
        var i = 0
        while (i < 50) {
            val guard = LoopGuard()
            assertTrue("generation ${guard.generation} was reused", seen.add(guard.generation))
            guards.add(guard)
            i++
        }
        assertEquals(50, guards.size)
    }

    @Test
    fun stoppingTwiceIsHarmless() {
        val guard = LoopGuard()
        guard.stop()
        guard.stop()
        assertFalse(guard.isRunning())
    }

    /**
     * The end-to-end shape of the bug: a worker loop that consults the guard must
     * leave, and it must do so even though we start a replacement instance before
     * it notices. The loop also has to survive being woken by the replacement
     * (an interrupt in the real service) without treating it as "keep going".
     */
    @Test
    fun theOldWorkerThreadExitsWhenANewInstanceStarts() {
        val first = LoopGuard()
        val iterations = AtomicInteger(0)
        val exited = CountDownLatch(1)
        val staleGeneration = first.generation

        val worker = Thread {
            // Mirrors pollLoop: keep going only while *my* generation is alive.
            while (first.isRunning(staleGeneration)) {
                iterations.incrementAndGet()
                try {
                    // Long poll: the real one blocks for up to 45 s here.
                    Thread.sleep(5L)
                } catch (t: InterruptedException) {
                    // onDestroy() interrupts us; stop rather than loop again.
                    Thread.currentThread().interrupt()
                    break
                }
            }
            exited.countDown()
        }
        worker.isDaemon = true
        worker.start()

        // Give the worker a moment to be genuinely inside its loop.
        val started = System.currentTimeMillis()
        while (iterations.get() == 0 && System.currentTimeMillis() - started < 2_000L) {
            Thread.sleep(1L)
        }
        assertTrue("the worker never entered its loop", iterations.get() > 0)

        // onDestroy(): stop my generation, then interrupt the blocked worker.
        first.stop()
        worker.interrupt()

        // start(): a replacement instance, before the old thread is gone.
        val second = LoopGuard()
        assertTrue(second.isRunning())

        assertTrue(
            "the stale worker must exit even though a new instance is running",
            exited.await(2_000L, TimeUnit.MILLISECONDS)
        )
        // The latch fires inside the thread, so join before asserting on liveness.
        worker.join(2_000L)
        assertFalse("the stale worker thread is still alive", worker.isAlive)
        assertTrue("the new instance is still running", second.isRunning())
    }

    /**
     * The generation check alone must be enough, with no interrupt involved.
     *
     * This is the part the shared boolean could not do. In 0.5.0 the stale thread
     * woke from its poll and re-read `running`, which had been set back to `true`
     * — so it carried on. Here the loop wakes on its own, finds its own
     * generation dead, and leaves, even though a newer instance is live.
     */
    @Test
    fun aStaleLoopLeavesOnItsOwnOnceItsGenerationIsDead() {
        val first = LoopGuard()
        val staleGeneration = first.generation
        val iterations = AtomicInteger(0)
        val exited = CountDownLatch(1)

        val worker = Thread {
            while (first.isRunning(staleGeneration)) {
                iterations.incrementAndGet()
                try {
                    Thread.sleep(5L)
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            exited.countDown()
        }
        worker.isDaemon = true
        worker.start()

        val started = System.currentTimeMillis()
        while (iterations.get() == 0 && System.currentTimeMillis() - started < 2_000L) {
            Thread.sleep(1L)
        }
        assertTrue("the worker never entered its loop", iterations.get() > 0)

        // Supersede it. Note: no interrupt, and the replacement is live.
        first.stop()
        val replacement = LoopGuard()
        assertTrue(replacement.isRunning())

        assertTrue(
            "the stale loop must leave on its next iteration",
            exited.await(2_000L, TimeUnit.MILLISECONDS)
        )
        worker.join(2_000L)
        assertFalse("the stale worker thread is still alive", worker.isAlive)
    }

    /**
     * The defect, modelled honestly: the *point* is that a worker blocked in a
     * long read never observes the moment the flag was cleared, because `start()`
     * puts it back before the worker wakes. A plain shared boolean therefore
     * cannot stop it.
     *
     * The worker below blocks (as `relay.poll()` does), the flag is cleared and
     * immediately set again while it is blocked, and the worker then wakes up and
     * carries on — the exact behaviour the relay logs showed. Contrast with
     * [theOldWorkerThreadExitsWhenANewInstanceStarts], where the same shape of
     * worker is stopped correctly.
     */
    @Test
    fun aBlockedWorkerCannotSeeAClearedSharedFlagThatIsSetAgain() {
        val sharedRunning = AtomicBoolean(true)
        val wokeUpAfterStop = CountDownLatch(1)
        val blocked = CountDownLatch(1)

        val worker = Thread {
            try {
                blocked.countDown()
                Thread.sleep(200L) // stands in for the 45 s poll read
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            // onDestroy() cleared the flag while we were blocked, but start() has
            // already set it back: a shared boolean cannot tell us apart.
            if (sharedRunning.get()) wokeUpAfterStop.countDown()
        }
        worker.isDaemon = true
        worker.start()

        assertTrue(blocked.await(2_000L, TimeUnit.MILLISECONDS))
        sharedRunning.set(false) // onDestroy()
        sharedRunning.set(true)  // start(), before the worker wakes

        assertTrue(
            "the blocked worker sees the flag as true again and keeps running",
            wokeUpAfterStop.await(2_000L, TimeUnit.MILLISECONDS)
        )
        worker.join(2_000L)
        assertFalse("the stale worker is still alive", worker.isAlive)
    }
}
