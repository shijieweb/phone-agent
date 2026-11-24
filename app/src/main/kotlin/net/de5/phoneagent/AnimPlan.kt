package net.de5.phoneagent

/**
 * The three global animation scales and what `anim` accepts (SPEC.md §3.11).
 *
 * Android stores them as **floats written as strings** in Settings.Global, so
 * every value here is a string on purpose: "0" and "0.0" are the same scale but
 * a different row, and re-encoding through a Float would turn a value the
 * operator set by hand into a different one.
 */
internal object AnimPlan {

    /** The keys, spelled exactly as Settings.Global expects them. */
    const val WINDOW = "window_animation_scale"
    const val TRANSITION = "transition_animation_scale"
    const val ANIMATOR = "animator_duration_scale"

    val KEYS = listOf(WINDOW, TRANSITION, ANIMATOR)

    /** "off" is the reason this command exists: it is what makes the UI snap. */
    const val OFF = "0"
    const val ON = "1"
    const val DEFAULT = "1.0"

    const val MAX = 10.0

    const val USAGE =
        "usage: anim [get] | anim set on|off|SCALE | anim save | anim restore"

    /**
     * Parse one scale value.
     *
     * `on`/`off` are the operator's words; anything else must be a number in
     * 0..10 and is normalised to one decimal ("0.5"), because a scale of
     * "0.4999999" on screen looks like a bug.
     */
    fun scale(raw: String?): String? {
        val value = raw?.trim()?.lowercase() ?: return null
        if (value.isEmpty()) return null
        if (value == "off") return OFF
        if (value == "on") return ON
        val number = value.toDoubleOrNull() ?: return null
        if (number < 0.0 || number > MAX) return null
        if (number == 0.0) return OFF
        return format(number)
    }

    /** One decimal, no locale: "1.0", not "1,0". */
    fun format(value: Double): String {
        val tenths = Math.round(value * 10.0)
        return (tenths / 10).toString() + "." + (tenths % 10).toString()
    }

    /** A value read back from settings, normalised for display (null stays "?")。 */
    fun shown(value: String?): String = value ?: "?"

    /** "0" / "1.0" / "0.5" — the three rows on one line. */
    fun state(window: String?, transition: String?, animator: String?): String =
        "window=" + shown(window) + " transition=" + shown(transition) +
            " animator=" + shown(animator)

    /** What `anim save` reports: the values it just remembered. */
    fun saved(window: String?, transition: String?, animator: String?): String =
        "已保存当前动画设置（" + state(window, transition, animator) + "），可用 anim restore 还原"

    fun applied(value: String): String = "已把三项动画缩放设为 " + value

    fun restored(window: String, transition: String, animator: String): String =
        "已还原动画设置（" + state(window, transition, animator) + "）"
}
