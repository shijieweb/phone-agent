package net.de5.phoneagent

/**
 * `sms` — read the inbox, and send only when explicitly told to (SPEC.md §3.12).
 *
 * Pure formatting here; the SmsManager / ContentResolver glue lives in
 * [SmsOps]. Splitting them keeps the two dangerous parts (a wrong number, a
 * wrong body) provable without a phone.
 */
internal object SmsPlan {

    const val LIMIT_DEFAULT = 20
    const val LIMIT_MAX = 100

    const val BODY_MAX = 200

    /** The inbox is personal: only the last N days are ever listed. */
    const val DAYS_DEFAULT = 7
    const val DAYS_MAX = 90

    const val USAGE = "usage: sms [list [n]] | sms send NUMBER TEXT"

    class Item(val address: String, val body: String, val millis: Long, val sent: Boolean)

    fun clampLimit(value: Int): Int = value.coerceIn(1, LIMIT_MAX)

    fun clampDays(value: Int): Int = value.coerceIn(1, DAYS_MAX)

    fun clip(value: String?): String {
        val flat = (value ?: "").replace('\n', ' ').replace('\r', ' ').trim()
        if (flat.length <= BODY_MAX) return flat
        return flat.substring(0, BODY_MAX - 1) + "…"
    }

    /**
     * A phone number as a recipient, or null.
     *
     * Deliberately loose (+ and digits only): the operator may paste a number
     * with spaces or dashes, and stripping those is safe; anything else in the
     * field (letters, quotes) is a typo worth refusing before an SMS leaves.
     */
    fun normalizeNumber(raw: String?): String? {
        val trimmed = (raw ?: "").trim()
        if (trimmed.isEmpty()) return null
        val sb = StringBuilder()
        for (ch in trimmed) {
            when {
                ch.isDigit() -> sb.append(ch)
                ch == '+' && sb.isEmpty() -> sb.append(ch)
                ch == ' ' || ch == '-' || ch == '(' || ch == ')' -> Unit
                else -> return null
            }
        }
        val digits = sb.count { it.isDigit() }
        if (digits < 3) return null
        return sb.toString()
    }

    fun line(item: Item): String {
        val mark = if (item.sent) ">" else "<"
        return mark + " " + item.address + " " + clip(item.body)
    }

    fun report(items: List<Item>, limit: Int): String {
        if (items.isEmpty()) return "没有短信"
        val cap = clampLimit(limit)
        val shown = if (items.size <= cap) items else items.subList(0, cap)
        val sb = StringBuilder()
        sb.append("短信 ").append(items.size).append(" 条")
        if (shown.size < items.size) sb.append("（只显示前 ").append(shown.size).append(" 条）")
        for (item in shown) sb.append('\n').append(line(item))
        return sb.toString()
    }

    fun sent(number: String, length: Int): String =
        "已发送短信到 " + number + "（" + length + " 字）"

    fun failure(reason: String): String = "发送失败: " + reason

    fun denied(reason: String): String =
        "读取短信被拒绝（" + reason + "）：需要 SEND_SMS/READ_SMS，可执行 " +
            "adb shell pm grant net.de5.phoneagent android.permission.READ_SMS"
}
