package net.de5.phoneagent

/**
 * When the agent is allowed to come back by itself (SPEC.md section 7).
 *
 * Pure on purpose: the decision is the whole feature, and it has to be testable
 * without an Activity or a receiver.
 */
internal object StartPolicy {

    /** What one press of the start/stop button means. */
    data class Decision(val start: Boolean, val autoStart: Boolean)

    /**
     * Stop means stop: pressing it also clears auto-start, so a reboot, an app
     * update or `START_STICKY` cannot bring the agent back behind the user's
     * back. Starting means start, and re-arms auto-start with it.
     */
    fun onToggle(wasRunning: Boolean): Decision =
        if (wasRunning) Decision(start = false, autoStart = false) else Decision(start = true, autoStart = true)

    /** Both conditions are required; a factory-fresh install stays quiet. */
    fun shouldAutoStart(autoStart: Boolean, configured: Boolean): Boolean = autoStart && configured

    /**
     * 界面被打开时该不该顺手把服务拉起来。
     *
     * 存在的原因：Flyme 不投 MY_PACKAGE_REPLACED（v0.4.3 实测零条 [boot]），静默
     * 安装（`pm install`）之后 App 不会自己起来，唯一可靠的信号是「有人把界面拉
     * 起来」——而 uid 2000 的 `am start` 能拉起界面，所以守护脚本靠它复活。
     *
     * autoStart 是硬门：老板点过停止就是真停（v0.2.9 的语义），界面被打开也不能
     * 复活；running 为真时不动，避免把已经在跑的服务重启一遍。
     */
    fun shouldStartOnOpen(configured: Boolean, autoStart: Boolean, running: Boolean): Boolean =
        configured && autoStart && !running

    /** Shown under the button; the behaviour is surprising enough to spell out. */
    const val HINT =
        "停止后不会自动启动，除非重新点「启动」；勾选「开机自动启动」时，重启或装新版会自己拉起。"
}
