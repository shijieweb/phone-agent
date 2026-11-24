package net.de5.phoneagent

import android.content.Context
import android.provider.Settings

/**
 * `anim` — the three global animation scales (SPEC.md section 3.11).
 *
 * Writing them goes through [Admin.setSetting], which already knows the two
 * supported routes (Device Owner's setGlobalSetting, otherwise
 * WRITE_SECURE_SETTINGS). That matters here: SET_ANIMATION_SCALE is a
 * signature|privileged permission with no development bit, so it is declared in
 * the manifest for completeness but is never the path this command relies on.
 *
 * `save` remembers the values in [Prefs] so `restore` can put the phone back —
 * a phone left at 0x animation looks broken to its owner, and "I forgot what it
 * was" is not an acceptable answer.
 */
internal object AnimOps {

    fun read(context: Context, key: String): String? = try {
        Settings.Global.getString(context.contentResolver, key)
    } catch (t: Throwable) {
        null
    }

    fun state(context: Context): String = AnimPlan.state(
        read(context, AnimPlan.WINDOW),
        read(context, AnimPlan.TRANSITION),
        read(context, AnimPlan.ANIMATOR)
    )

    /** Write all three; the first refusal is reported and the rest are skipped. */
    fun set(context: Context, value: String): String {
        for (key in AnimPlan.KEYS) {
            val outcome = Admin.setSetting(context, "global", key, value)
            if (outcome.startsWith(Admin.ERROR_PREFIX)) return outcome
        }
        AgentLog.add("anim", "动画缩放设为 " + value)
        return AnimPlan.applied(value) + "（" + state(context) + "）"
    }

    fun save(context: Context): String {
        val prefs = Prefs(context)
        prefs.animWindow = read(context, AnimPlan.WINDOW)
        prefs.animTransition = read(context, AnimPlan.TRANSITION)
        prefs.animAnimator = read(context, AnimPlan.ANIMATOR)
        AgentLog.add("anim", "已保存动画设置")
        return AnimPlan.saved(prefs.animWindow, prefs.animTransition, prefs.animAnimator)
    }

    /** Restore what `save` remembered; nothing saved is an error, not a no-op. */
    fun restore(context: Context): String {
        val prefs = Prefs(context)
        val window = prefs.animWindow
        val transition = prefs.animTransition
        val animator = prefs.animAnimator
        if (window == null || transition == null || animator == null) {
            return Admin.ERROR_PREFIX + "没有保存过动画设置，请先执行 anim save"
        }
        val pairs = listOf(
            AnimPlan.WINDOW to window,
            AnimPlan.TRANSITION to transition,
            AnimPlan.ANIMATOR to animator
        )
        for (pair in pairs) {
            val outcome = Admin.setSetting(context, "global", pair.first, pair.second)
            if (outcome.startsWith(Admin.ERROR_PREFIX)) return outcome
        }
        AgentLog.add("anim", "已还原动画设置")
        return AnimPlan.restored(window, transition, animator)
    }
}
