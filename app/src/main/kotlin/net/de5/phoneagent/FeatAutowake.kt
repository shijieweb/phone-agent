package net.de5.phoneagent

import android.app.KeyguardManager
import android.content.Context

/**
 * `autowake on|off` —— 界面操作前自动亮屏（v0.8.0-pad12）。
 *
 * 开着的时候，`tap`/`click`/`longclick`/`swipe`/`tree`/`shot`/`where` 执行前
 * 若屏幕是黑的就先 [Wake.wake]；亮屏后锁屏还带密码的话**直接拒**（exit=6），
 * 而不是继续执行、最后回一句"没找到节点"——那种回执会让人以为选择器写错了。
 *
 * 默认关（[Prefs.KEY_AUTO_WAKE] 默认 false），所以既有行为一个字都没变。
 */
internal object FeatAutowake : FeatureModule {

    override val verb: String = "autowake"

    override fun parse(rest: String): Parsed? {
        val value = rest.trim().lowercase()
        if (value.isEmpty() || value == "on" || value == "off") return Parsed.Ok(value)
        return Parsed.Bad(AutowakePlan.USAGE)
    }

    override fun execute(ctx: FeatureContext, args: String): ExecutionResult {
        val prefs = Prefs(ctx.context)
        return when (args.trim().lowercase()) {
            "" -> ExecutionResult(EXIT_OK, AutowakePlan.line(prefs.autoWake), "")
            "on" -> {
                prefs.autoWake = true
                ExecutionResult(EXIT_OK, AutowakePlan.line(true), "")
            }
            "off" -> {
                prefs.autoWake = false
                ExecutionResult(EXIT_OK, AutowakePlan.line(false), "")
            }
            else -> ExecutionResult(EXIT_USAGE, "", AutowakePlan.USAGE)
        }
    }
}

/**
 * 亮屏闸门：被 [CommandExecutor] 与 [FeatWhere] 在动手前调用。
 *
 * 只在 autowake 开着时才做任何事；关着时 [preflight] 立刻返回一个空闸门，
 * 连 PowerManager 都不碰。
 */
internal object Autowake {

    /** 闸门结果：[refusal] 非空就直接把它当回执返回，[note] 非空就挂到回执上。 */
    class Guard(val refusal: ExecutionResult?, val note: String?)

    val NONE: Guard = Guard(null, null)

    fun preflight(context: Context): Guard {
        val prefs = Prefs(context)
        if (!prefs.autoWake) return NONE
        val decision = AutowakePlan.decide(true, isScreenOn(context), keyguardState(context))
        if (decision == AutowakePlan.REFUSE) return refuse()
        if (decision != AutowakePlan.WAKE) return NONE

        val woke = Wake.wake(context)
        if (woke.exit != EXIT_OK) return Guard(woke, null)
        // 亮屏之后锁屏才真正"挡住界面"（熄屏时 KeyguardManager 也可能报 locked）。
        if (keyguardState(context) == AutowakePlan.KEYGUARD_SECURE) return refuse()
        return Guard(null, AutowakePlan.note(AutowakePlan.viaOf(woke.stdout)))
    }

    /**
     * 与 [Wake] 内部同款的锁屏判定（那边是 private，这里自己读一次）。
     *
     * @return `none` / `locked`（没密码的锁屏）/ `secure`（有密码）。
     */
    fun keyguardState(context: Context): String {
        return try {
            val manager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                ?: return AutowakePlan.KEYGUARD_NONE
            when {
                !manager.isKeyguardLocked -> AutowakePlan.KEYGUARD_NONE
                manager.isKeyguardSecure -> AutowakePlan.KEYGUARD_SECURE
                else -> AutowakePlan.KEYGUARD_LOCKED
            }
        } catch (t: Throwable) {
            AgentLog.add("autowake", "读取锁屏状态失败: " + t.javaClass.simpleName)
            AutowakePlan.KEYGUARD_NONE
        }
    }

    private fun refuse(): Guard =
        Guard(ExecutionResult(EXIT_PERMISSION, "", AutowakePlan.refusalText()), null)
}

/** [FeatAutowake] / [Autowake] 的纯逻辑：决策、文案、`wake=via=` 解析。 */
internal object AutowakePlan {

    const val USAGE = "usage: autowake [on|off]"

    /** 什么都不做（没开 autowake，或屏幕本来就亮着）。 */
    const val SKIP = "skip"

    /** 先亮屏再干活。 */
    const val WAKE = "wake"

    /** 锁屏有密码，界面操作不可用，直接拒。 */
    const val REFUSE = "refuse"

    const val KEYGUARD_NONE = "none"
    const val KEYGUARD_LOCKED = "locked"
    const val KEYGUARD_SECURE = "secure"

    /**
     * 闸门决策。
     *
     * @param keyguard [KEYGUARD_NONE] / [KEYGUARD_LOCKED] / [KEYGUARD_SECURE]。
     */
    fun decide(autoWake: Boolean, screenOn: Boolean, keyguard: String): String = when {
        !autoWake -> SKIP
        keyguard == KEYGUARD_SECURE -> REFUSE
        screenOn -> SKIP
        else -> WAKE
    }

    fun line(on: Boolean): String = "autowake " + (if (on) "on" else "off")

    fun note(via: String): String = "wake=via=" + via

    fun refusalText(): String = "锁屏有密码，界面操作不可用；请先手动解锁"

    /**
     * 从 [Wake.wake] 的回执里取通道。
     *
     * 两种形态：`wake already-on scr=on kg=none` 与
     * `wake ok via=wakelock scr=on kg=none`（见 Wake.reportLine）。
     */
    fun viaOf(stdout: String): String {
        val text = stdout.trim()
        if (text.startsWith("wake already-on")) return "already-on"
        val marker = "via="
        val at = text.indexOf(marker)
        if (at < 0) return "unknown"
        val tail = text.substring(at + marker.length)
        val end = tail.indexOfFirst { it.isWhitespace() }
        return if (end < 0) tail else tail.substring(0, end)
    }
}
