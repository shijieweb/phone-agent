package net.de5.phoneagent

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.telephony.SmsManager

/**
 * The ContentResolver / SmsManager glue for `sms` (SPEC.md section 3.12).
 *
 * Reading needs READ_SMS, sending needs SEND_SMS. Both are runtime permissions
 * that pm grant can hand out, so the refusal message names that exact line
 * instead of telling the operator to hunt through settings.
 */
internal object SmsOps {

    private val INBOX: Uri = Uri.parse("content://sms")

    /**
     * The newest [limit] messages inside the last [SmsPlan.DAYS_DEFAULT] days.
     *
     * Sorted by date descending in the query, not in memory: the inbox can hold
     * tens of thousands of rows and we only ever want the head.
     */
    fun list(context: Context, limit: Int): List<SmsPlan.Item> {
        val cutoff = System.currentTimeMillis() - SmsPlan.DAYS_DEFAULT * 24L * 60L * 60L * 1000L
        val projection = arrayOf("address", "body", "date", "type")
        val selection = "date > ?"
        val args = arrayOf(cutoff.toString())
        val items = ArrayList<SmsPlan.Item>()
        val resolver: ContentResolver = context.contentResolver
        try {
            resolver.query(INBOX, projection, selection, args, "date DESC")?.use { cursor ->
                val addressIndex = cursor.getColumnIndex("address")
                val bodyIndex = cursor.getColumnIndex("body")
                val dateIndex = cursor.getColumnIndex("date")
                val typeIndex = cursor.getColumnIndex("type")
                val cap = SmsPlan.clampLimit(limit)
                while (cursor.moveToNext() && items.size < cap) {
                    val address = if (addressIndex >= 0) cursor.getString(addressIndex) else null
                    val body = if (bodyIndex >= 0) cursor.getString(bodyIndex) else null
                    val date = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L
                    val type = if (typeIndex >= 0) cursor.getInt(typeIndex) else MESSAGE_TYPE_INBOX
                    items.add(
                        SmsPlan.Item(
                            address ?: "?",
                            body ?: "",
                            date,
                            type == MESSAGE_TYPE_SENT
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            AgentLog.add("sms", Admin.ERROR_PREFIX + "读取短信失败: " + describe(t))
        }
        return items
    }

    /**
     * Send one message.
     *
     * @return null on success, or the sentence to report.
     */
    fun send(context: Context, number: String, text: String): String? {
        val manager = try {
            SmsManager.getDefault()
        } catch (t: Throwable) {
            return SmsPlan.failure("无法取得 SmsManager: " + describe(t))
        }
        return try {
            // The API splits anything over 160 GSM chars itself, but doing it
            // here keeps the multipart behaviour identical across ROMs.
            val parts = manager.divideMessage(text)
            if (parts.size <= 1) {
                manager.sendTextMessage(number, null, text, null, null)
            } else {
                manager.sendMultipartTextMessage(number, null, parts, null, null)
            }
            AgentLog.add("sms", "已发送短信到 " + number)
            null
        } catch (t: Throwable) {
            SmsPlan.failure(describe(t))
        }
    }

    /** The framework's own values for the type column of content://sms. */
    private const val MESSAGE_TYPE_INBOX = 1
    private const val MESSAGE_TYPE_SENT = 2
}
