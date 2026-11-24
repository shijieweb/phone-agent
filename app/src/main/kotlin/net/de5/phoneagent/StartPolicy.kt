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

    /** Shown under the button; the behaviour is surprising enough to spell out. */
    const val HINT =
        "停止后不会自动启动，除非重新点「启动」；勾选「开机自动启动」时，重启或装新版会自己拉起。"
}
