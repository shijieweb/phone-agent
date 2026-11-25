package net.de5.phoneagent

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * The main-thread door. Accessibility calls (reading the tree, injecting a
 * gesture, taking a screenshot) must happen there, but the poll loop must not
 * block it — so callers post a short piece of work and wait on a latch.
 */
object MainThread {

    private val handler = Handler(Looper.getMainLooper())

    /** @return false when the looper is gone and the work will never run. */
    fun post(block: () -> Unit): Boolean = handler.post(Runnable { block() })
}

/**
 * Accessibility service: reads the window tree, injects gestures, takes
 * screenshots with no MediaProjection prompt (SPEC.md section 8).
 *
 * The XML config is authoritative — `setServiceInfo` is never called at
 * runtime, and there is no tree cache: we read on demand.
 */
class PhoneAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PhoneAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * Roots of every interactive window, each starting at depth 0 (SPEC.md
     * section 5), the active window read through the door that re-reads.
     *
     * ## Why the active window is not read from `windows`
     *
     * On Flyme / Android 16, `AccessibilityWindowInfo.getRoot()` hands back a
     * **cached snapshot** of that window. A panel that expands *inside* the
     * window already on screen never appears in it — measured on the real phone:
     * pressing 高级设置 drew three `EditText`s, and `tree` kept reporting zero
     * of them after one second, after six seconds, and after a scroll; only
     * switching away and back (`key home` then `am start`) made them visible.
     * `rootInActiveWindow` is re-read every time, so it is the door the active
     * window has to come through.
     *
     * That door only answers for **one** window, and `tree` promises all of
     * them (IME, system dialogs, floating panels), so the two sources are merged
     * by [WindowPicker]: active window first, then the rest, never the same
     * window twice — a duplicate would double every match count and make
     * `click apply <text>` ambiguous about a node that exists once.
     *
     * Each root is also `refresh()`ed, which asks the framework to re-pull that
     * node's subtree; it is allowed to fail (the node may have gone away between
     * the two calls) and a failure only means the node is used as-is.
     *
     * Must be called on the main thread.
     */
    fun roots(): List<NodeView> {
        var activeRoot: AccessibilityNodeInfo? = null
        var activeRootId: Int? = null
        try {
            val root: AccessibilityNodeInfo? = rootInActiveWindow
            if (root != null) {
                refreshQuietly(root)
                activeRoot = root
                activeRootId = root.windowId
            }
        } catch (t: Throwable) {
            // Fall through with no fresh root: the windows list is still worth reading.
            activeRoot = null
            activeRootId = null
        }

        val stubs = ArrayList<WindowPicker.WindowStub>()
        val byId = HashMap<Int, AccessibilityWindowInfo>()
        try {
            val allWindows: List<AccessibilityWindowInfo>? = windows
            if (allWindows != null) {
                for (window in allWindows) {
                    val id = window.id
                    stubs.add(WindowPicker.WindowStub(id, window.isActive, window.layer))
                    byId[id] = window
                }
            }
        } catch (t: Throwable) {
            stubs.clear()
            byId.clear()
        }

        val out = ArrayList<NodeView>(stubs.size + 1)
        for (id in WindowPicker.merge(activeRootId, stubs)) {
            if (id == activeRootId && activeRoot != null) {
                out.add(AccessibilityNodeAdapter(activeRoot))
                continue
            }
            val window = byId[id] ?: continue
            try {
                val root: AccessibilityNodeInfo? = window.root
                if (root != null) {
                    refreshQuietly(root)
                    out.add(AccessibilityNodeAdapter(root))
                }
            } catch (t: Throwable) {
                // One unreadable window must not empty the whole answer.
            }
        }
        return out
    }

    /**
     * `refresh()` throws when the node is already gone, which is normal here:
     * the tree is read while the screen is moving. It returns false when the
     * framework declines, and both cases mean "use what we have".
     */
    private fun refreshQuietly(node: AccessibilityNodeInfo) {
        try {
            node.refresh()
        } catch (t: Throwable) {
            // Node invalidated between the read and the refresh; nothing to do.
        }
    }
}
