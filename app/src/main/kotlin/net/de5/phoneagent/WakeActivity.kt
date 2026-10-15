package net.de5.phoneagent

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle

/**
 * A translucent, one-shot Activity used by [Wake] as its third layer
 * (SPEC.md section 3.2).
 *
 * `WindowManager.LayoutParams.setTurnScreenOn(true)` is the API Android 27+
 * provides for "turning the screen on because of this activity", and
 * `setShowWhenLocked(true)` lets it appear over the keyguard. Together they wake
 * a screen that the wake lock could not, on ROMs that ignore the deprecated
 * bright wake lock.
 *
 * There is **no layout and no new resource file**: the theme comes from the
 * framework (`android.R.style.Theme_Translucent_NoTitleBar`), the window is
 * configured before `setContentView(null)`, and the activity finishes
 * immediately so it never appears as a task to the operator.
 *
 * ## Manifest
 *
 * This activity has to be declared in `AndroidManifest.xml` to be startable.
 * That file is frozen for this change, so the required entry is documented in
 * the delivery report instead of being applied here:
 *
 * ```xml
 * <activity
 *     android:name=".WakeActivity"
 *     android:exported="false"
 *     android:excludeFromRecents="true"
 *     android:noHistory="true"
 *     android:theme="@android:style/Theme.Translucent.NoTitleBar" />
 * ```
 *
 * Until that entry exists, `startActivity` throws
 * `ActivityNotFoundException`; [Wake.startWakeActivity] catches it and reports
 * `wake ok via=wakelock` (or `wake failed via=none`) rather than crashing.
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // Turn the panel on and show over the keyguard. Both are no-ops on
            // an already-awake screen, which is the common case here.
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } catch (t: Throwable) {
            // Not fatal: the layer simply does not help, and Wake re-checks the
            // power state itself.
            AgentLog.add("wake", "WakeActivity 窗口设置失败: " + describe(t))
        }
        dismissNonSecureKeyguard()
        try {
            setContentView(android.view.View(this))
        } catch (t: Throwable) {
            // Setting a view is cosmetic; finish regardless.
        }
        // One-shot: leave as soon as the window has had a chance to be created.
        try {
            finish()
        } catch (t: Throwable) {
            // ignore
        }
    }

    /**
     * Clear a non-secure keyguard (swipe-only lock).
     *
     * This lives here rather than in [Wake] because
     * `KeyguardManager.requestDismissKeyguard` only accepts an `Activity`, and
     * this is the only Activity the agent has. A **secure** keyguard is skipped:
     * it can never be dismissed silently, and asking would just raise the
     * lock screen. Failures are reported by `Wake`, never thrown.
     */
    private fun dismissNonSecureKeyguard() {
        try {
            val manager = getSystemService(KeyguardManager::class.java) ?: return
            if (manager.isKeyguardLocked && !manager.isKeyguardSecure) {
                manager.requestDismissKeyguard(this, null)
            }
        } catch (t: Throwable) {
            AgentLog.add("wake", "解锁请求失败: " + describe(t))
        }
    }
}
