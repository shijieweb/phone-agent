package net.de5.phoneagent

/**
 * `call` — place, answer and hang up (SPEC.md §3.13).
 *
 * Placing a call is irreversible in the only way that matters (a real phone
 * rings on the other end), so it is dry-run by default: `call NUMBER` reports
 * what it *would* dial, and only `call apply NUMBER` touches the dialer.
 */
internal object CallPlan {

    const val USAGE =
        "usage: call [apply] NUMBER | call answer | call hangup | call state | " +
            "call speaker [on|off]"

    /** Android's dialer intent caps the number far below this. */
    const val NUMBER_MAX = 40

    /**
     * The same loose normalisation as [SmsPlan.normalizeNumber]: spaces, dashes
     * and parentheses are stripped; a leading + survives; anything else is a
     * typo worth refusing.
     */
    fun normalize(raw: String?): String? {
        val trimmed = (raw ?: "").trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > NUMBER_MAX) return null
        val sb = StringBuilder()
        for (ch in trimmed) {
            when {
                ch.isDigit() -> sb.append(ch)
                ch == '+' && sb.isEmpty() -> sb.append(ch)
                ch == ' ' || ch == '-' || ch == '(' || ch == ')' -> Unit
                else -> return null
            }
        }
        if (sb.count { it.isDigit() } < 3) return null
        return sb.toString()
    }

    /** What a dry run says: never "已拨号", because nothing was dialled. */
    fun dryRun(number: String): String =
        "将拨号 " + number + "（dry-run：未真正拨出，确认后执行 call apply " + number + "）"

    fun dialed(number: String): String = "已拨号 " + number

    fun answered(): String = "已接听"

    fun hungUp(): String = "已挂断"

    fun state(offHook: Boolean, ringing: Boolean): String =
        "通话状态: " + when {
            ringing -> "响铃中"
            offHook -> "通话中"
            else -> "空闲"
        }

    /** `call speaker` report form: the current route plus the call state. */
    fun speaker(on: Boolean, offHook: Boolean): String =
        "免提: " + (if (on) "开" else "关") + "（" + stateOf(offHook) + "）"

    fun speakerSet(on: Boolean, offHook: Boolean): String =
        "已" + (if (on) "开启" else "关闭") + "免提（" + stateOf(offHook) + "）"

    fun speakerDenied(): String =
        "切换免提要 MODIFY_AUDIO_SETTINGS（未授予：adb shell pm grant " +
            "net.de5.phoneagent android.permission.MODIFY_AUDIO_SETTINGS）"

    /** The call state alone, for the parentheses above. */
    fun stateOf(offHook: Boolean): String = if (offHook) "通话中" else "当前无通话"

    fun failure(reason: String): String = "拨号失败: " + reason

    fun answerDenied(): String =
        "接听需要 ANSWER_PHONE_CALLS 或 MODIFY_PHONE_STATE（未授予：adb shell pm grant " +
            "net.de5.phoneagent android.permission.ANSWER_PHONE_CALLS）"
}
