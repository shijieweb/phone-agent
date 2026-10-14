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
     * Roots of every interactive window, each starting at depth 0, falling back
     * to the active window when `windows` is empty (SPEC.md section 5).
     *
     * Must be called on the main thread.
     */
    fun roots(): List<NodeView> {
        val out = ArrayList<NodeView>()
        try {
            val allWindows: List<AccessibilityWindowInfo>? = windows
            if (allWindows != null) {
                for (window in allWindows) {
                    val root: AccessibilityNodeInfo? = window.root
                    if (root != null) out.add(AccessibilityNodeAdapter(root))
                }
            }
        } catch (t: Throwable) {
            out.clear()
        }
        if (out.isEmpty()) {
            try {
                val root: AccessibilityNodeInfo? = rootInActiveWindow
                if (root != null) out.add(AccessibilityNodeAdapter(root))
            } catch (t: Throwable) {
                // Leave the list empty: the caller reports an empty tree.
            }
        }
        return out
    }
}
