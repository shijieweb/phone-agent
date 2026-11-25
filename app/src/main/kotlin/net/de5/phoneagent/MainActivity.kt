package net.de5.phoneagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Minimal config screen, built in code (no XML layout): relay URL, agent id,
 * secret, start/stop, permission + settings shortcuts and a status line that
 * refreshes every 2 s from a static snapshot (SPEC.md section 6).
 *
 * Nothing here touches the network.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var relayEdit: EditText
    private lateinit var idEdit: EditText
    private lateinit var secretEdit: EditText
    private lateinit var toggleButton: Button
    private lateinit var autoStartBox: CheckBox
    private lateinit var stayOnBox: CheckBox
    private lateinit var statusView: TextView
    private lateinit var versionView: TextView

    /** Five presses inside three seconds open the debug screen (see onCreate). */
    private val debugGate = TapGate(5, 3_000L)

    private val uiHandler = Handler(Looper.getMainLooper())
    private var askedBackgroundLocation = false

    private val refresher = object : Runnable {
        override fun run() {
            refreshStatus()
            uiHandler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 48, 48, 48)

        addLabel(root, "Relay base URL")
        relayEdit = addField(root, prefs.relayBase)

        addLabel(root, "Agent id")
        idEdit = addField(root, prefs.agentId)

        addLabel(root, "Secret")
        secretEdit = addField(root, prefs.secret, password = true)

        addButton(root, "保存") { save() }
        toggleButton = addButton(root, "启动") { toggleAgent() }
        autoStartBox = addCheckBox(root, "开机自动启动", prefs.autoStart) { checked ->
            prefs.autoStart = checked
            refreshStatus()
        }
        // Same switch the `screen stayon` command flips, so the operator can set
        // it while holding the phone and still find it in force after a reboot.
        stayOnBox = addCheckBox(root, "屏幕常亮", prefs.stayOn) { checked ->
            applyStayOn(checked)
        }
        addLabel(root, "屏幕常亮会关闭自动熄屏（需要 WRITE_SECURE_SETTINGS）")
        addLabel(root, StartPolicy.HINT)
        addButton(root, "请求权限") { requestAgentPermissions() }
        addButton(root, "打开无障碍设置") { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        addButton(root, "加入电池白名单") { requestBatteryExemption() }
        addButton(root, "打开电池优化设置") {
            openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        addButton(root, "诊断并复制（推荐）") { runDiagnostics() }
        addButton(root, "复制日志") { copyLog() }
        addButton(root, "清空日志") { AgentLog.clear(); refreshStatus() }

        statusView = TextView(this)
        root.addView(statusView)

        scroll.addView(root)
        setContentView(scroll)

        // Same check the service runs on start: opening the settings screen after
        // an update is a normal way to notice "everything looks on but tree
        // fails", and the repair is cheap. Asynchronous, so the UI never waits on
        // the ~2 s rescue sequence.
        A11yHeal.checkNow(this)

        // 装完自己起来（v0.5.5）。Flyme 不投 MY_PACKAGE_REPLACED，静默安装之后
        // 没有任何广播会到；界面被打开是这个 App 唯一能拿到的「刚装完」信号。
        // autoStart 为 false 时一行都不动——老板点过停止就是真停。
        if (StartPolicy.shouldStartOnOpen(prefs.isConfigured(), prefs.autoStart, RcAgentService.running)) {
            try {
                startForegroundService(Intent(this, RcAgentService::class.java))
            } catch (t: Throwable) {
                AgentLog.add("boot", "打开界面时自启服务被拒: " + describe(t))
            }
        }

        // 版本号 + 调试页的门（v0.5.7-pad12）。页面上不写任何提示：这一行对
        // 用户就只是版本号，3 秒内连点 5 次才进得去调试页。计数用单调时钟，
        // 系统时间被改过也不会把门锁死。
        versionView = TextView(this)
        versionView.text = "v" + BuildConfig.VERSION_NAME
        versionView.setPadding(0, 32, 0, 0)
        versionView.setOnClickListener {
            if (debugGate.tap(SystemClock.elapsedRealtime())) {
                startActivity(Intent(this, DebugActivity::class.java))
            }
        }
        root.addView(versionView)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        uiHandler.postDelayed(refresher, REFRESH_MS)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Second pass: "Allow all the time" is a separate grant on Android 11+.
        if (requestCode == REQ_PERMISSIONS && !askedBackgroundLocation) {
            askedBackgroundLocation = true
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                REQ_BACKGROUND_LOCATION
            )
        }
    }

    private fun save() {
        prefs.relayBase = relayEdit.text.toString().trim()
        prefs.agentId = idEdit.text.toString().trim()
        prefs.secret = secretEdit.text.toString().trim()
        refreshStatus()
    }

    /**
     * Start/stop, plus the auto-start flag — both directions, one decision.
     *
     * The flag is written *before* the service is touched: if the stop is slow
     * or throws, auto-start is already off and a reboot cannot resurrect the
     * agent the operator just switched off.
     */
    private fun toggleAgent() {
        val decision = StartPolicy.onToggle(RcAgentService.running)
        prefs.autoStart = decision.autoStart
        if (decision.start) {
            if (!prefs.isConfigured()) {
                statusView.text = "未配置 relay 地址或口令"
                syncAutoStartBox()
                return
            }
            try {
                startForegroundService(Intent(this, RcAgentService::class.java))
            } catch (t: Throwable) {
                statusView.text = "启动失败: " + (t.message ?: "")
            }
        } else {
            try {
                stopService(Intent(this, RcAgentService::class.java))
            } catch (t: Throwable) {
                statusView.text = "停止失败: " + (t.message ?: "")
            }
        }
        syncAutoStartBox()
        refreshStatus()
    }

    /** The checkbox mirrors the stored flag, so a button press never lies about it. */
    private fun syncAutoStartBox() {
        autoStartBox.isChecked = prefs.autoStart
    }

    /**
     * The checkbox is a view of [ScreenOps], never a second source of truth: it
     * asks the same code the command does and then re-reads what actually
     * happened, so a refused write leaves the box unchecked instead of lying.
     */
    private fun applyStayOn(checked: Boolean) {
        val result = try {
            ScreenOps.stayOn(this, checked)
        } catch (t: Throwable) {
            "错误: " + describe(t)
        }
        statusView.text = result
        stayOnBox.isChecked = prefs.stayOn
        refreshStatus()
    }

    private fun requestAgentPermissions() {
        askedBackgroundLocation = false
        // POST_NOTIFICATIONS only exists from API 33. On 31/32 it is granted at
        // install time; naming it in a runtime request makes some ROMs drop the
        // whole array, which would also lose the location grants.
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            wanted.add(0, Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(wanted.toTypedArray(), REQ_PERMISSIONS)
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (t: Throwable) {
            statusView.text = "无法打开设置: $action"
        }
    }

    /**
     * Ask the OS to stop freezing us when the screen is off.
     *
     * Without this the long poll dies in Doze and the relay sees "offline" — the
     * exact symptom the operator reported. The dialog is a system one, so the
     * user still has to confirm; if the ROM refuses the intent we fall back to
     * the settings list, which at least lets them find it by hand.
     */
    private fun requestBatteryExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
            statusView.text = "请在系统弹窗里选择「允许」"
        } catch (t: Throwable) {
            statusView.text = "本机不支持直接申请，已打开设置列表"
            openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
    }

    private fun refreshStatus() {
        statusView.text = RcAgentService.snapshot() +
            "\n电池白名单: " + Battery.flag(this)
        toggleButton.text = if (RcAgentService.running) "停止" else "启动"
        if (autoStartBox.isChecked != prefs.autoStart) autoStartBox.isChecked = prefs.autoStart
        if (stayOnBox.isChecked != prefs.stayOn) stayOnBox.isChecked = prefs.stayOn
    }

    /**
     * Walk the network layers and copy the result. Runs off the UI thread
     * because it makes real connections with short timeouts.
     */
    private fun runDiagnostics() {
        statusView.text = "正在诊断…（最多约 1 分钟）"
        Thread(Runnable {
            val text = try {
                Diagnostics.run(this, prefs)
            } catch (t: Throwable) {
                "诊断本身失败: " + describe(t) + "\n" + stackOf(t)
            }
            Clipboard.copy(this, text)
            uiHandler.post {
                statusView.text = "诊断结果已复制到剪贴板（" +
                    text.length + " 字符），粘贴发回即可。\n\n" + RcAgentService.snapshot()
            }
        }, "diagnostics").start()
    }

    private fun copyLog() {
        val text = Diagnostics.stateOnly(prefs)
        Clipboard.copy(this, text)
        statusView.text = "状态与日志已复制到剪贴板（" +
            text.length + " 字符）。\n\n" + RcAgentService.snapshot()
    }

    private fun addLabel(parent: LinearLayout, text: String) {
        val view = TextView(this)
        view.text = text
        parent.addView(view)
    }

    private fun addField(parent: LinearLayout, value: String, password: Boolean = false): EditText {
        val edit = EditText(this)
        edit.setSingleLine(true)
        edit.setText(value)
        if (password) {
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        parent.addView(
            edit,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return edit
    }

    private fun addCheckBox(
        parent: LinearLayout,
        text: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ): CheckBox {
        val box = CheckBox(this)
        box.text = text
        box.isChecked = checked
        box.setOnCheckedChangeListener { _, value -> onChanged(value) }
        parent.addView(
            box,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return box
    }

    private fun addButton(parent: LinearLayout, text: String, onClick: () -> Unit): Button {
        val button = Button(this)
        button.text = text
        button.setOnClickListener { onClick() }
        parent.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return button
    }

    companion object {
        private const val REFRESH_MS = 2_000L
        private const val REQ_PERMISSIONS = 100
        private const val REQ_BACKGROUND_LOCATION = 101
    }
}
