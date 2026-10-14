package net.de5.phoneagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * The only way text leaves this device is through the operator copying it, so
 * putting a report on the clipboard is a first-class feature (SPEC.md section 6).
 *
 * The clipboard API must run on a looper thread; callers are the poll worker and
 * the UI, so this always posts to the main thread and never blocks.
 */
internal object Clipboard {

    fun copy(context: Context, text: String) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                val manager = app.getSystemService(ClipboardManager::class.java)
                manager?.setPrimaryClip(ClipData.newPlainText("Phone Agent", text))
            } catch (t: Throwable) {
                AgentLog.add("clip", "写入剪贴板失败: " + describe(t))
            }
        }
    }
}

/** `ClassName: message`, or just the class name when there is no message. */
internal fun describe(t: Throwable): String {
    val message = t.message
    return if (message.isNullOrEmpty()) {
        t.javaClass.name
    } else {
        t.javaClass.name + ": " + message
    }
}

/**
 * A short stack trace: the first frames only, because a clipboard report has to
 * stay pasteable. `getStackTrace` is used instead of `printStackTrace` so the
 * output cannot interleave with anything else.
 */
internal fun stackOf(t: Throwable, maxFrames: Int = 12): String {
    val sb = StringBuilder()
    for (frame in t.stackTrace.take(maxFrames)) {
        sb.append("    at ").append(frame.toString()).append('\n')
    }
    val cause = t.cause
    if (cause != null) {
        sb.append("  caused by ").append(describe(cause)).append('\n')
        for (frame in cause.stackTrace.take(maxFrames / 2)) {
            sb.append("    at ").append(frame.toString()).append('\n')
        }
    }
    return sb.toString()
}
