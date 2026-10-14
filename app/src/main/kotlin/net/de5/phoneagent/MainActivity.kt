package net.de5.phoneagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
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
    private lateinit var statusView: TextView

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
        addButton(root, "请求权限") { requestAgentPermissions() }
        addButton(root, "打开无障碍设置") { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
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

    private fun toggleAgent() {
        if (RcAgentService.running) {
            try {
                stopService(Intent(this, RcAgentService::class.java))
            } catch (t: Throwable) {
                statusView.text = "停止失败: " + (t.message ?: "")
            }
        } else {
            if (!prefs.isConfigured()) {
                statusView.text = "未配置 relay 地址或口令"
                return
            }
            try {
                startForegroundService(Intent(this, RcAgentService::class.java))
            } catch (t: Throwable) {
                statusView.text = "启动失败: " + (t.message ?: "")
            }
        }
        refreshStatus()
    }

    private fun requestAgentPermissions() {
        askedBackgroundLocation = false
        requestPermissions(
            arrayOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            REQ_PERMISSIONS
        )
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (t: Throwable) {
            statusView.text = "无法打开设置: $action"
        }
    }

    private fun refreshStatus() {
        statusView.text = RcAgentService.snapshot()
        toggleButton.text = if (RcAgentService.running) "停止" else "启动"
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
