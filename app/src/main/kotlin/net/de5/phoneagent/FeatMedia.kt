package net.de5.phoneagent

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/**
 * `media play|pause|next|prev|stop` —— 媒体键（v0.8.0-pad12）。
 *
 * 主路是 `input keyevent`（需要 uid 2000，走自连 ADB；真机验证过 87/88 可用）。
 * 没连自连 ADB 时退一步用 `AudioManager.dispatchMediaKeyEvent`——这条路
 * **不保证生效**（部分 ROM 会吞掉），所以回执里写明走的是哪条通道，别让操作员
 * 以为按了没反应是命令的错。
 *
 * 不动既有 `KEY_NAMES`（back/home/recents/notifications/quicksettings）：
 * 媒体键不是全局动作，混进去会改坏 `key` 的语义。
 */
internal object FeatMedia : FeatureModule {

    override val verb: String = "media"

    override fun parse(rest: String): Parsed? {
        val action = rest.trim().lowercase()
        if (action.isEmpty()) return Parsed.Bad(MediaPlan.USAGE)
        return if (MediaPlan.keyCode(action) == null) Parsed.Bad(MediaPlan.USAGE) else Parsed.Ok(action)
    }

    override fun execute(ctx: FeatureContext, args: String): ExecutionResult {
        val action = args.trim().lowercase()
        val code = MediaPlan.keyCode(action) ?: return ExecutionResult(EXIT_USAGE, "", MediaPlan.USAGE)

        val out = ctx.runShell("input keyevent " + code, TIMEOUT_MS)
        if (out != null && out.ok()) {
            return ExecutionResult(EXIT_OK, MediaPlan.shellLine(action, code), "")
        }
        if (out != null && !out.precondition && out.code != null) {
            // 连上了但远端报错：这条命令确实没按下去，别退回 AudioManager 装作成功。
            return ExecutionResult(EXIT_PERMISSION, "", MediaPlan.failedLine(action, out.code, out.text))
        }

        // 自连 ADB 没配对/没连上（out 为 null 或 precondition）：AudioManager 尽力而为。
        val dispatched = ctx.onMain(TIMEOUT_MS) { dispatch(ctx.context, code) }
        if (dispatched == null) {
            return ExecutionResult(EXIT_TIMEOUT, "", SelfAdbPlan.timeoutText(TIMEOUT_MS))
        }
        return ExecutionResult(EXIT_OK, MediaPlan.audioLine(action), "")
    }

    /** 主线程发一对 down/up；返回 false 表示这台机器没有 AudioManager。 */
    private fun dispatch(context: Context, code: Int): Boolean {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return true
    }

    private const val TIMEOUT_MS = 5_000L
}

/** [FeatMedia] 的纯逻辑：动作表、keycode、文案。 */
internal object MediaPlan {

    const val USAGE = "usage: media play|pause|next|prev|stop"

    /** 动作 → Android keycode（KEYCODE_MEDIA_*）。 */
    private val CODES: Map<String, Int> = linkedMapOf(
        "play" to 126,
        "pause" to 127,
        "next" to 87,
        "prev" to 88,
        "stop" to 86
    )

    fun actions(): List<String> = CODES.keys.toList()

    fun keyCode(action: String): Int? = CODES[action.lowercase()]

    fun shellLine(action: String, code: Int): String = "media " + action + " keyevent=" + code

    fun audioLine(action: String): String =
        "media " + action + " via=audiomanager（未连自连 ADB，这条通道不保证生效；要稳就用 shconnect）"

    fun failedLine(action: String, code: Int, text: String): String =
        Admin.ERROR_PREFIX + "media " + action + " 没发出去：input keyevent 返回 " + code +
            (if (text.isBlank()) "" else "（" + text.trim() + "）")
}
