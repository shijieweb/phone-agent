package net.de5.phoneagent

import android.util.Log

/**
 * 守护进程的 Android 侧：把 [WatchdogPlan] 生成的命令交给自连 ADB 执行。
 *
 * 全部走 uid 2000 的 shell，因为 App 自己没有权限起一个能活过 force-stop 的进程
 * （真机实测：adbd 下 setsid 起的脚本能活，App 里起的线程会被一起收走）。
 *
 * 三条规矩：
 * 1. 任何失败都不抛：守护是锦上添花，不能让它把服务启动流程搞挂。
 * 2. 自连 ADB 没连上时只记一行日志「守护未启用」，不报错刷屏（老板不看 logcat，
 *    但看 agent log；刷屏会把真正的错误顶掉）。
 * 3. 停止必须真的停止：disarm 先写标记再 pkill，两道保险。
 */
internal object Watchdog {

    private const val LOGCAT_TAG = "PhoneAgent"

    /** 单次 shell 往返的上限：脚本写盘 + setsid 都很快，10 秒足够。 */
    private const val RUN_TIMEOUT_MS = 10_000L

    /** 最近一次操作的结论，给 shinfo / 诊断用。 */
    @Volatile
    private var lastNote: String? = null

    /** 自动 arm 的重试间隔：5 分钟。自连 ADB 可能在服务起来之后才连上。 */
    private const val ARM_RETRY_MS = 5 * 60 * 1000L

    @Volatile
    private var lastArmMs: Long = 0L

    /**
     * 心跳里的自动补 arm，5 分钟最多一次。
     *
     * 存在的理由：服务起来的那一刻自连 ADB 往往还没连上（要 adbheal + shconnect），
     * 只 arm 一次就会永久停在「守护未启用」。重试放在心跳线程上，它本来就在每 15 秒
     * 醒一次，多一次 shell 往返不影响轮询。
     */
    fun ensureArmed() {
        val now = System.currentTimeMillis()
        if (now - lastArmMs < ARM_RETRY_MS) return
        lastArmMs = now
        armQuietly()
    }

    fun lastNote(): String? = lastNote

    /**
     * 最近一次已知的守护状态：running / off / unavailable / unknown。
     *
     * 存在的理由：`status` 命令要回一个 JSON，但查一次真实状态要一次 shell 往返
     * （可能 10 秒），不能塞进每 30 秒被问一次的 status 里。这里给的是缓存，
     * 真值请用 `watchdog status`。
     */
    @Volatile
    private var state: String = "unknown"

    fun state(): String = state

    /** 只读状态：一次 shell 往返拿进程表、日志末行、停止标记。 */
    fun status(): String {
        if (!SelfAdb.isConnected()) return WatchdogPlan.status(false, emptyList(), null, false)
        val exit = run(WatchdogPlan.statusCommand())
        if (exit == null || exit.code != 0) {
            val why = exit?.text ?: "自连 ADB 未连接"
            return WatchdogPlan.status(false, emptyList(), null, false) + "\n说明: " + why
        }
        val parsed = WatchdogPlan.parseStatus(exit.text)
        state = if (parsed.pids.isEmpty()) "off" else "running"
        return WatchdogPlan.status(true, parsed.pids, parsed.lastRestart, parsed.offMarker)
    }

    /**
     * 手动 arm。与 [armQuietly] 的区别只有一个：失败要说出来（命令有回显）。
     *
     * 顺序：查进程 + 查脚本版本 → （旧脚本先强杀）→ 写脚本 → setsid 起 → 回读进程号。
     * 回读是为了「不撒谎」：setsid 是异步的，只看命令退出码会把没起来也说成成功。
     *
     * v0.6.3-pad12 加了版本比对：以前发现进程在跑就直接回「已在运行」，真机上
     * 于是留着一份 v0.5.8 的旧脚本永远换不掉（日志封不了顶）。现在版本对不上
     * 就走「强杀 → 重写 → 重起」，杀不掉就明确报错，绝不让两个脚本同时跑。
     */
    fun arm(): String {
        if (!SelfAdb.isConnected()) {
            lastNote = WatchdogPlan.armUnavailable()
            return lastNote!!
        }
        // v0.6.5-pad12：probe 之前先清 App 侧停止标记，否则新脚本第一轮就自杀。
        if (WatchdogPlan.prepareArm()) AgentLog.add("watchdog", "arm 前清掉了上次停止留下的标记")
        val before = WatchdogPlan.parsePids(runText(WatchdogPlan.probeCommand()))
        val current = WatchdogPlan.parseScriptCurrent(runText(WatchdogPlan.stampCommand()))
        if (before.isNotEmpty() && current) {
            lastNote = WatchdogPlan.armAlready(before)
            return lastNote!!
        }
        if (!RcAgentService.running) {
            lastNote = WatchdogPlan.armNeedsService()
            return lastNote!!
        }
        if (before.isNotEmpty()) {
            // 旧脚本还在跑：先按 disarm 那条路强杀（标记 + kill -9 三遍）。
            val killed = run(WatchdogPlan.disarmCommand())
            if (killed == null || killed.code != 0) {
                lastNote = WatchdogPlan.armFailed((killed?.text ?: "自连 ADB 未连接").trim())
                AgentLog.add("watchdog", lastNote!!)
                return lastNote!!
            }
            val left = WatchdogPlan.parsePids(runText(WatchdogPlan.probeCommand()))
            if (left.isNotEmpty()) {
                lastNote = WatchdogPlan.armStuck(left)
                AgentLog.add("watchdog", lastNote!!)
                return lastNote!!
            }
        }
        val written = run(WatchdogPlan.writeCommand())
        if (written == null || written.code != 0) {
            val why = written?.text ?: "自连 ADB 未连接"
            lastNote = WatchdogPlan.armFailed(why.trim())
            return lastNote!!
        }
        val armed = run(WatchdogPlan.armCommand())
        if (armed == null || armed.code != 0) {
            val why = armed?.text ?: "自连 ADB 未连接"
            lastNote = WatchdogPlan.armFailed(why.trim())
            return lastNote!!
        }
        val after = WatchdogPlan.parsePids(runText(WatchdogPlan.probeCommand()))
        lastNote = if (after.isEmpty()) {
            WatchdogPlan.armFailed("脚本已写入但没有进程，检查 /data/local/tmp 是否可执行")
        } else if (before.isNotEmpty()) {
            WatchdogPlan.armReplaced(after)
        } else {
            WatchdogPlan.armOk(after)
        }
        AgentLog.add("watchdog", lastNote!!)
        return lastNote!!
    }

    /**
     * 服务起来后自动 arm；失败只记一行，不打扰任何人。
     *
     * 由 [RcAgentService] 的独立线程调用，绝不能在主线程或轮询线程上跑：
     * 一次 shell 往返可能等 10 秒。
     */
    fun armQuietly() {
        try {
            if (!SelfAdb.isConnected()) {
                lastNote = WatchdogPlan.adbUnavailable()
                AgentLog.add("watchdog", lastNote!!)
                return
            }
            val existing = WatchdogPlan.parsePids(runText(WatchdogPlan.probeCommand()))
            // 版本对不上也算「该 arm」：arm() 会强杀旧脚本再重写（v0.6.3-pad12）。
            val current = WatchdogPlan.parseScriptCurrent(runText(WatchdogPlan.stampCommand()))
            if (!WatchdogPlan.shouldArm(existing, current, RcAgentService.running)) {
                if (existing.isNotEmpty()) {
                    lastNote = WatchdogPlan.armAlready(existing)
                    AgentLog.add("watchdog", lastNote!!)
                }
                return
            }
            val text = arm()
            if (text.startsWith(Admin.ERROR_PREFIX)) Log.w(LOGCAT_TAG, text)
        } catch (t: Throwable) {
            lastNote = WatchdogPlan.armFailed(describe(t))
            AgentLog.add("watchdog", lastNote!!)
            Log.w(LOGCAT_TAG, "watchdog arm failed", t)
        }
    }

    /** 停止：写标记 + pkill + 回读确认。 */
    fun disarm(): String {
        if (!SelfAdb.isConnected()) {
            lastNote = WatchdogPlan.disarmUnavailable()
            return lastNote!!
        }
        val exit = run(WatchdogPlan.disarmCommand())
        if (exit == null || exit.code != 0) {
            val why = exit?.text ?: "自连 ADB 未连接"
            lastNote = WatchdogPlan.disarmFailed(why.trim())
            AgentLog.add("watchdog", lastNote!!)
            return lastNote!!
        }
        val left = WatchdogPlan.parsePids(runText(WatchdogPlan.probeCommand()))
        lastNote = if (left.isEmpty()) {
            WatchdogPlan.disarmOk()
        } else {
            WatchdogPlan.disarmFailed("还有进程 " + left.joinToString(",") + "（停止标记已写，下一轮会自己退出）")
        }
        AgentLog.add("watchdog", lastNote!!)
        return lastNote!!
    }

    /** 停止时调用：只在 autoStart 被清掉时动手（见 [WatchdogPlan.shouldDisarm]）。 */
    fun disarmIfNeeded(autoStart: Boolean) {
        if (!WatchdogPlan.shouldDisarm(autoStart)) return
        try {
            disarm()
        } catch (t: Throwable) {
            lastNote = WatchdogPlan.disarmFailed(describe(t))
            AgentLog.add("watchdog", lastNote!!)
        }
    }

    /** 一次 shell 往返；失败返回 null（调用方自己决定说什么）。 */
    private fun run(command: String): SelfAdbPlan.Exit? = try {
        SelfAdb.shell(command, RUN_TIMEOUT_MS)
    } catch (t: Throwable) {
        AgentLog.add("watchdog", "shell fail " + t.javaClass.name + ": " + (t.message ?: "(无 message)"))
        null
    }

    private fun runText(command: String): String = run(command)?.text ?: ""
}