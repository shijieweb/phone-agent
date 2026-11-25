package net.de5.phoneagent

/**
 * Which windows one tree read should include, and in what order (v0.6.0-pad12).
 *
 * The problem this exists for: on Flyme / Android 16 the root of a window taken
 * from `AccessibilityWindowInfo.getRoot()` is a **cached snapshot**. A panel that
 * expands inside the window that is already on screen does not show up in it —
 * not after a second, not after a scroll — until the window is re-activated by
 * switching away and back. `rootInActiveWindow` does re-read, so the active
 * window has to be read through that door.
 *
 * The catch is that `rootInActiveWindow` only ever answers for **one** window,
 * while `tree` has always promised every window (an IME, a system dialog, a
 * floating panel). So the two sources are merged here: the active window first,
 * then everything else, and never the same window twice — otherwise the active
 * window would be printed twice, once from each door, and a `click apply <text>`
 * would count double matches for a node that exists once.
 *
 * Pure: no framework types, so the rule is unit-testable (SPEC.md section 10).
 */
internal object WindowPicker {

    /** The framework facts we need about one window, as plain values. */
    data class WindowStub(val id: Int, val active: Boolean, val layer: Int)

    /**
     * Window ids to read, in reading order.
     *
     * @param activeRootId the window id of the root already taken from
     *   `rootInActiveWindow`, or null when that returned nothing.
     * @param windows every window the framework listed, in any order.
     *
     * The active window goes first: when it is also in [windows] it is emitted
     * once, under [activeRootId] (the fresh copy), and its cached duplicate is
     * dropped. When [activeRootId] is null the window the framework marks active
     * takes that place. Remaining windows follow top-of-screen first, because a
     * dialog or IME drawn above the app is what the operator means when two
     * nodes carry the same label.
     */
    fun merge(activeRootId: Int?, windows: List<WindowStub>): List<Int> {
        val primary = activeRootId ?: windows.firstOrNull { it.active }?.id
        val out = ArrayList<Int>(windows.size + 1)
        val seen = HashSet<Int>(windows.size + 1)
        if (primary != null && seen.add(primary)) out.add(primary)
        val rest = windows.sortedWith(
            compareByDescending<WindowStub> { it.layer }.thenBy { it.id }
        )
        for (window in rest) {
            if (seen.add(window.id)) out.add(window.id)
        }
        return out
    }

    /** @return the id of the window the framework marks active, or null. */
    fun activeId(windows: List<WindowStub>): Int? =
        windows.firstOrNull { it.active }?.id
}
