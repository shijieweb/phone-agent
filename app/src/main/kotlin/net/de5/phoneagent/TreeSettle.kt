package net.de5.phoneagent

/**
 * The one-retry rule behind `tree` (v0.6.0-pad12).
 *
 * Reading the accessibility tree twice in a row and keeping the second answer is
 * a blunt instrument, but it is the only self-healing part of the fix: the
 * root-level change ([WindowPicker]) removes the cached-snapshot case we can
 * name, and this covers the ROMs where the fresh root still lags one event loop
 * behind a layout change. The budget is deliberately one retry, 150 ms apart —
 * `tree` is the command an operator runs in a loop while poking a screen, so it
 * may not become a slow command.
 *
 * Pure, so the "which answer wins" rule is pinned instead of implied.
 */
internal object TreeSettle {

    /** The gap between the two reads, in milliseconds. */
    const val SETTLE_MS = 150L

    /**
     * @param first the tree read on the first pass.
     * @param second the tree read after [SETTLE_MS], or null when that pass timed out.
     * @return the answer to report: the second read when it differs (it is the
     *   later truth — a layout change that landed between the passes), otherwise
     *   the first. A null second pass never turns a good read into a timeout.
     */
    fun pick(first: String, second: String?): String =
        if (second == null || second == first) first else second
}
