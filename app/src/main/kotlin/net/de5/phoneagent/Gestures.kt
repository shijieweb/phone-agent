package net.de5.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Touch injection through `dispatchGesture` (SPEC.md section 3).
 *
 * Every entry point must be called from a **background** thread: the
 * `dispatchGesture` call itself is posted to the main thread (accessibility
 * calls belong there) and the caller blocks until the result callback fires.
 */
object Gestures {

    /** A tap is a very short stroke, not a zero-length one. */
    private const val TAP_DURATION_MS = 60L
    private const val DISPATCH_SLACK_MS = 5_000L

    fun tap(service: AccessibilityService, x: Int, y: Int): Boolean {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        path.lineTo(x.toFloat(), y.toFloat())
        return dispatch(service, path, TAP_DURATION_MS)
    }

    fun longPress(service: AccessibilityService, x: Int, y: Int, ms: Int): Boolean {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        path.lineTo(x.toFloat(), y.toFloat())
        return dispatch(service, path, ms.toLong().coerceIn(1L, 5000L))
    }

    fun swipe(
        service: AccessibilityService,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        ms: Int
    ): Boolean {
        val path = Path()
        path.moveTo(x1.toFloat(), y1.toFloat())
        path.lineTo(x2.toFloat(), y2.toFloat())
        return dispatch(service, path, ms.toLong().coerceIn(1L, 3000L))
    }

    /** `back`, `home`, `recents`, `notifications`, `quicksettings`. */
    fun globalAction(service: AccessibilityService, name: String): Boolean {
        val action = when (name) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quicksettings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        return try {
            service.performGlobalAction(action)
        } catch (t: Throwable) {
            false
        }
    }

    private fun dispatch(service: AccessibilityService, path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()

        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)

        val posted = MainThread.post {
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    completed.set(false)
                    latch.countDown()
                }
            }
            try {
                // A false return means the gesture was rejected outright.
                if (!service.dispatchGesture(gesture, callback, null)) latch.countDown()
            } catch (t: Throwable) {
                latch.countDown()
            }
        }

        if (!posted) return false
        try {
            latch.await(durationMs + DISPATCH_SLACK_MS, TimeUnit.MILLISECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return completed.get()
    }
}
