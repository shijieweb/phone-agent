package net.de5.phoneagent

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The debug screen — the second Activity, and the one nobody but the operator
 * ever sees (reachable only by pressing the version line on the config page
 * five times; not exported, kept out of recents).
 *
 * It exists because twelve buttons had piled up on one screen for two different
 * people: the config page is for somebody who wants to type a URL and press
 * 启动, this page is for the operator who wants to run one command and read its
 * answer **without** a relay round trip. Same executor, different door — the
 * command goes through [Commands.parse] and the service's own
 * [CommandExecutor] via [RcAgentService.executeLocally], so what is tested here
 * is exactly what the relay drives.
 *
 * Built in code (no XML), four blocks top to bottom: summary, command panel,
 * quick actions, back. Nothing here touches the network on the UI thread.
 */
class DebugActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var summaryView: TextView
    private lateinit var commandEdit: EditText
    private lateinit var resultView: TextView

    /**
     * 守护日志 / APK 残留的最近一次测量（v0.5.8-pad12）。
     *
     * 这两个数要靠 self-adb 跑 shell，不能塞进 2 秒一次的那个刷新里：量一次要
     * 一次往返，而摘要每 2 秒重画一次。所以分两条节奏——摘要 2 秒、测量 30 秒，
     * 中间显示上一次量到的数（宁可显示旧数也不让界面卡住）。
     */
    private var guard: LogSizes.Guard? = null
    private var guardNote: String? = null
    private var apks: List<LogSizes.Apk> = emptyList()
    private var measuring = false

    private val uiHandler = Handler(Looper.getMainLooper())

    private val refresher = object : Runnable {
        override fun run() {
            refreshSummary()
            uiHandler.postDelayed(this, REFRESH_MS)
        }
    }

    private val guardRefresher = object : Runnable {
        override fun run() {
            measureLogs()
            uiHandler.postDelayed(this, GUARD_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(40, 40, 40, 40)

        // ---- A. summary: everything below is a static snapshot, no I/O ----
        addLabel(root, "摘要")
        summaryView = TextView(this)
        summaryView.typeface = Typeface.MONOSPACE
        summaryView.setTextIsSelectable(true)
        root.addView(summaryView)

        // ---- B. command panel ----
        addLabel(root, "命令（和 relay 收到的完全相同）")
        commandEdit = EditText(this)
        commandEdit.setSingleLine(true)
        commandEdit.setHint("status")
        root.addView(commandEdit, matchWidth())
        addButton(root, "执行") { runCommand(commandEdit.text.toString()) }
        addButton(root, "清空") {
            commandEdit.setText("")
            resultView.text = ""
        }

        resultView = TextView(this)
        resultView.typeface = Typeface.MONOSPACE
        resultView.setTextIsSelectable(true)
        root.addView(resultView)

        // ---- C. quick actions: the six commands used most by hand ----
        addLabel(root, "快捷动作")
        addButton(root, "截图 shot") { runCommand("shot") }
        addButton(root, "节点树 tree 30") { runCommand("tree 30") }
        // 无障碍那条路在 Flyme / Android 16 上会慢一拍（v0.6.0 真机实测），
        // 这个按钮走 uid 2000 的 uiautomator，用来对照同一时刻的另一份读数。
        addButton(root, "节点树 tree uia 30（uid 2000）") { runCommand("tree uia 30") }
        addButton(root, "日志 logs 200") { runCommand("logs 200") }
        // 日志占用与裁剪（v0.5.8-pad12）：dry-run 与真删分开两个按钮，
        // 免得手快点掉了一次真的删除。
        addButton(root, "日志占用 logs size") { runCommand("logs size") }
        addButton(root, "日志裁剪 logs trim（只看）") { runCommand("logs trim") }
        addButton(root, "日志裁剪并执行 logs trim apply") { runCommand("logs trim apply") }
        addButton(root, "诊断 Diagnostics.run") { runDiagnostics() }
        addButton(root, "状态与日志 Diagnostics.stateOnly") { copyState() }
        addButton(root, "清空日志") {
            AgentLog.clear()
            resultView.text = "已清空日志"
        }

        // ---- D. back to the config page ----
        addButton(root, "返回配置页") { finish() }

        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
        uiHandler.postDelayed(refresher, REFRESH_MS)
        uiHandler.post(guardRefresher)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refresher)
        uiHandler.removeCallbacks(guardRefresher)
        super.onPause()
    }

    /**
     * Version, service, accessibility, battery and self-ADB — the five answers
     * that decide whether a failing command is the command's fault at all.
     */
    private fun refreshSummary() {
        val battery = batteryPercent(this)
        summaryView.text = StringBuilder()
            .append("版本: v").append(BuildConfig.VERSION_NAME).append('\n')
            .append("服务: ").append(if (RcAgentService.running) "运行中" else "已停止").append('\n')
            .append("无障碍: ")
            .append(if (PhoneAccessibilityService.instance != null) "已开启" else "未开启")
            .append('\n')
            .append("电量: ").append(if (battery < 0) "未知" else battery.toString() + "%").append('\n')
            .append("自连 ADB: ").append(if (SelfAdb.isConnected()) "已连接" else "未连接")
            .append('\n')
            .append(LogSizes.summaryLine(AgentLog.size(), AgentLog.droppedCount(), guard, apks))
            .toString()
    }

    /**
     * 量一次日志占用：守护日志走 self-adb，APK 残留直接读 cacheDir。
     *
     * 在后台线程里跑，且同一时刻只允许一个在飞——self-adb 一次往返可能几十毫秒，
     * 也可能卡到超时，叠起来会把 shell 通道挤满。
     */
    private fun measureLogs() {
        if (measuring) return
        measuring = true
        Thread({
            val measured = try {
                GuardProbe.read()
            } catch (t: Throwable) {
                Pair(null, describe(t))
            }
            val files = try {
                Downloader.cached(this)
            } catch (t: Throwable) {
                emptyList<LogSizes.Apk>()
            }
            uiHandler.post {
                guard = measured.first
                guardNote = measured.second
                apks = files
                measuring = false
                refreshSummary()
            }
        }, "debug-guard").start()
    }

    /**
     * Run one command line through the live service and show its answer.
     *
     * Off the UI thread: `tree` waits on the main looper, `shot` uploads a blob
     * and `logs` walks logcat, so any of them on the UI thread would freeze the
     * screen. When the service was never started there is no executor and the
     * panel says so instead of pretending the command ran.
     */
    private fun runCommand(line: String) {
        val text = line.trim()
        if (text.isEmpty()) {
            resultView.text = "命令为空"
            return
        }
        resultView.text = "执行中… " + text
        Thread({
            AgentLog.add("debug", text)
            var shown: String
            try {
                val result = RcAgentService.executeLocally(text)
                shown = if (result == null) {
                    DebugPanel.NOT_RUNNING
                } else {
                    DebugPanel.formatResult(result.exit, result.stdout, result.stderr)
                }
            } catch (t: Throwable) {
                shown = "命令执行抛异常: " + describe(t) + "\n" + stackOf(t)
            }
            val finalText = shown
            uiHandler.post { resultView.text = finalText }
        }, "debug-cmd").start()
    }

    /**
     * The network walk takes up to about a minute, so it runs off the UI thread;
     * the report is copied in full and only previewed here, because the point of
     * the report is to be pasted somewhere, not read on a phone.
     */
    private fun runDiagnostics() {
        resultView.text = "正在诊断…（最多约 1 分钟）"
        Thread({
            val text = try {
                Diagnostics.run(this, prefs)
            } catch (t: Throwable) {
                "诊断本身失败: " + describe(t) + "\n" + stackOf(t)
            }
            Clipboard.copy(this, text)
            uiHandler.post {
                resultView.text = "诊断结果已复制到剪贴板（" + text.length + " 字符）\n\n" +
                    DebugPanel.clip(text)
            }
        }, "debug-diag").start()
    }

    /** Zero-network snapshot, same text the 复制日志 button produces on the config page. */
    private fun copyState() {
        val text = Diagnostics.stateOnly(prefs)
        Clipboard.copy(this, text)
        resultView.text = "状态与日志已复制到剪贴板（" + text.length + " 字符）\n\n" +
            DebugPanel.clip(text)
    }

    private fun addLabel(parent: LinearLayout, text: String) {
        val view = TextView(this)
        view.text = text
        parent.addView(view)
    }

    private fun addButton(parent: LinearLayout, text: String, onClick: () -> Unit) {
        val button = android.widget.Button(this)
        button.text = text
        button.setOnClickListener { onClick() }
        parent.addView(button, matchWidth())
    }

    private fun matchWidth(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    companion object {
        private const val REFRESH_MS = 2_000L

        /** 守护日志 30 秒量一次：它是低频数据，2 秒一次只会挤占 self-adb。 */
        private const val GUARD_REFRESH_MS = 30_000L
    }
}
