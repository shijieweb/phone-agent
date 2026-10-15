package net.de5.phoneagent

import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-instance liveness for the poll and heartbeat threads.
 *
 * ## Why this exists
 *
 * Up to v0.5.0 the two worker threads decided whether to keep looping by reading
 * the static `RcAgentService.running`. That flag is shared by *every* incarnation
 * of the service, so it cannot express "am I still the thread that was told to
 * run?". The failure was reproducible in production:
 *
 *  1. `onDestroy()` sets `running = false` and returns. It did **not** interrupt
 *     the poll thread, which is blocked inside `relay.poll()` with a 45 s read
 *     timeout.
 *  2. A new `startForegroundService` arrives (task removed, alarm restart, the
 *     operator tapping 启动) and `onStartCommand` sees `running == false`, so it
 *     calls `start()` — which sets `running = true`.
 *  3. The old thread finally wakes up. Its `polled` is `null` (the connection was
 *     torn down), it hits `continue`, re-tests `running`, sees `true`, and stays
 *     alive. There are now two long polls on one agent, and each restart can add
 *     another.
 *
 * The relay logs made this visible: 37 of 64 one-minute windows carried a poll
 * rate a single loop cannot produce, peak concurrency 3, with duplicated `hello`.
 *
 * ## The fix
 *
 * Every incarnation of the service allocates a fresh, never-reused generation
 * ([startNewGeneration]) and hands it to its workers. A worker only continues
 * while *its own* generation is still the live one. A generation is never
 * revived, so a new instance cannot resurrect an old thread — which is exactly
 * the property the shared boolean lacked.
 */
class LoopGuard {

    /**
     * A generation that has been allocated and is live right now. Callers that
     * want to model a service instance use this constructor; the generation is
     * taken from the global counter, so it is unique across the process.
     */
    constructor() : this(startNewGeneration())

    /** A guard for a generation allocated elsewhere (see [startNewGeneration]). */
    private constructor(allocated: Int) {
        generation = allocated
    }

    /**
     * Which incarnation of the service this guard belongs to. Handed to worker
     * threads so they can check themselves rather than the shared flag.
     */
    val generation: Int

    @Volatile
    private var stopped = false

    /** `true` while this instance is the current one and has not stopped. */
    fun isRunning(): Boolean = !stopped && generation == liveGeneration

    /**
     * `true` only when [myGeneration] is both live and this guard's own. A worker
     * passes the generation it captured at start, so a thread that outlived its
     * service fails this check even if the shared `running` flag is `true` again.
     */
    fun isRunning(myGeneration: Int): Boolean =
        !stopped && myGeneration == generation && myGeneration == liveGeneration

    /** Stops this instance for good. Idempotent. */
    fun stop() {
        stopped = true
        synchronized(Lock) {
            if (liveGeneration == generation) liveGeneration = 0
        }
    }

    companion object {

        private val Lock = Any()

        private val counter = AtomicInteger(0)

        /**
         * The generation of the service instance that should currently be
         * running. `0` means "nothing is live" — never handed out to an instance.
         */
        @Volatile
        private var liveGeneration: Int = 0

        /**
         * Allocate a fresh generation and mark it live, so any previous instance
         * is superseded from this moment on. A superseded generation is never
         * made live again, which is the core of the fix.
         */
        fun startNewGeneration(): Int {
            val next = counter.incrementAndGet()
            synchronized(Lock) { liveGeneration = next }
            return next
        }

        /** The generation that is currently allowed to run, or `0` for none. */
        fun live(): Int = liveGeneration
    }
}
