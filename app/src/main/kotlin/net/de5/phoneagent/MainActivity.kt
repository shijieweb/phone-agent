package net.de5.phoneagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The config screen, built in code (no XML) — the only screen a non-technical
 * user ever sees (SPEC.md section 6).
 *
 * v0.5.8-pad12 cut it down from twelve buttons to one card, one main button, two
 * switches, one permission wizard and a collapsed advanced section (v0.6.3-pad12
 * dropped the 开机自动启动 switch, leaving one). The reason
 * is not taste: twelve buttons had piled up for two different people on one
 * screen. 启动/停止, the two switches and 一键设置权限 are what the phone's owner
 * actually needs; the relay URL, the secret and the log tools belong to whoever
 * debugs it, and the log tools now live on [DebugActivity] (five presses on the
 * version line) together with the command panel.
 *
 * Nothing here touches the network, and every string the operator clicks by is
 * defined once in [UiText] so `click apply <text>` cannot hit the wrong control.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var relayEdit: EditText
    private lateinit var idEdit: EditText
    private lateinit var secretEdit: EditText
    private lateinit var toggleButton: Button
    private lateinit var stayOnBox: CheckBox
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView

    /** 24dp 主灯 + 三盏 12dp 小灯（v0.6.2-pad12）。 */
    private lateinit var mainLamp: View
    private lateinit var lampA11y: View
    private lateinit var lampBattery: View
    private lateinit var lampAdb: View
    private lateinit var permHint: TextView
    private lateinit var noticeView: TextView
    private lateinit var advancedBox: LinearLayout
    private lateinit var versionView: TextView

    /** Five presses inside three seconds open the debug screen (see onCreate). */
    private val debugGate = TapGate(5, 3_000L)

    private val uiHandler = Handler(Looper.getMainLooper())
    private var askedBackgroundLocation = false

    /**
     * 只在 onResume 与 onPause 之间为 true。
     *
     * onPause 里的 removeCallbacks 已经能停掉轮询链，但一个**已经排进消息队列**
     * 的 run 还会照跑一次；那一跑就是「页面已经不在前台却还在探」，加这个开关让它
     * 直接变成空操作，而不是靠删回调的时机刚好对上。
     */
    @Volatile
    private var foreground = false

    private val refresher = object : Runnable {
        override fun run() {
            if (!foreground) return
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

        // ---- 1. the status card: one lamp, one big word, one small line ----
        // v0.6.2-pad12：这一页是给妈妈看的，她要的不是「四个字段各是什么值」，
        // 而是「现在到底好不好」。所以主灯 + 一个大词给结论，副行只列有问题的项，
        // 三盏小灯指出是哪一项不对。判定全在 [HealthPlan] 里，这里只负责画。
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        mainLamp = circle(MAIN_LAMP_DP)
        card.addView(mainLamp)

        statusTitle = TextView(this)
        statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        statusTitle.setPadding(dp(12), 0, 0, 0)
        card.addView(statusTitle)
        root.addView(card)

        statusDetail = TextView(this)
        statusDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        statusDetail.setTextColor(COLOR_DETAIL)
        statusDetail.setPadding(0, dp(6), 0, dp(10))
        root.addView(statusDetail)

        root.addView(lampRow())

        // ---- 2. the one button that matters ----
        toggleButton = addButton(root, UiText.START) { toggleAgent() }

        // Anything that went wrong or needs a human answer lands here instead of
        // overwriting the status card: the card is a live snapshot, a failure is
        // a sentence, and mixing them means neither is readable.
        noticeView = TextView(this)
        noticeView.setPadding(0, 8, 0, 16)
        root.addView(noticeView)

        // ---- 3. the one switch ----
        // v0.6.3-pad12 去掉了「开机自动启动」：它的生命周期本来就跟启动/停止按钮
        // 绑在一起（按启动置位、按停止清零），单独一个复选框只会让人以为还有第三
        // 种状态。老板的原话是「开机不用考虑自启，默认不启动，要人工点启动」。
        //
        // Same switch the `screen stayon` command flips, so the operator can set
        // it while holding the phone and still find it in force after a reboot.
        stayOnBox = addCheckBox(root, UiText.STAY_ON, prefs.stayOn) { checked ->
            applyStayOn(checked)
        }
        addLabel(root, "屏幕常亮会关闭自动熄屏（需要 WRITE_SECURE_SETTINGS）")
        addLabel(root, StartPolicy.HINT)

        // ---- 4. one wizard instead of four buttons ----
        // Four buttons asked the owner to know which permission was missing; the
        // wizard asks [PermPlan] instead and does exactly the next missing thing,
        // so pressing it repeatedly walks the list and nothing else.
        addButton(root, UiText.PERMISSION) { advancePermission() }
        permHint = TextView(this)
        permHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        permHint.setPadding(0, 4, 0, 24)
        root.addView(permHint)

        // ---- 5. the advanced section, collapsed by default ----
        // Deliberately not persisted: every visit starts collapsed, so a stray
        // tap on the phone cannot leave the secret field on screen.
        addButton(root, UiText.ADVANCED) { toggleAdvanced() }
        advancedBox = LinearLayout(this)
        advancedBox.orientation = LinearLayout.VERTICAL
        advancedBox.visibility = View.GONE
        addLabel(advancedBox, UiText.RELAY_LABEL)
        relayEdit = addField(advancedBox, prefs.relayBase)
        addLabel(advancedBox, UiText.ID_LABEL)
        idEdit = addField(advancedBox, prefs.agentId)
        addLabel(advancedBox, UiText.SECRET_LABEL)
        secretEdit = addField(advancedBox, prefs.secret, password = true)
        addButton(advancedBox, UiText.SAVE) { save() }
        root.addView(advancedBox)

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
        // 进来先真探一次（全是本地读，很快），再按 3 秒节拍轮询；onPause 停。
        foreground = true
        refreshStatus()
        // 先摘再挂：onResume 可能被连着调用两次，不摘就会叠出两条轮询链。
        uiHandler.removeCallbacks(refresher)
        uiHandler.postDelayed(refresher, REFRESH_MS)
    }

    override fun onPause() {
        foreground = false
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
     *
     * v0.6.3-pad12：这个标志不再有复选框显示（界面上看不到了），但**必须**继续
     * 写——onDestroy 靠它决定收不收守护、打开界面靠它决定拉不拉服务。它现在的
     * 唯一含义就是「最后一次按的是启动还是停止」。
     */
    private fun toggleAgent() {
        val decision = StartPolicy.onToggle(RcAgentService.running)
        prefs.autoStart = decision.autoStart
        if (decision.start) {
            if (!prefs.isConfigured()) {
                noticeView.text = "未配置 relay 地址或口令"
                return
            }
            // 启动前清掉 App 侧停止标记，否则守护脚本（如果还在跑）会继续按「停止」处理。
            StopFlag.clear(this)
            try {
                startForegroundService(Intent(this, RcAgentService::class.java))
            } catch (t: Throwable) {
                noticeView.text = "启动失败: " + (t.message ?: "")
            }
        } else {
            // v0.6.5-pad12：先落 App 侧停止标记再停服务。这个标记 /sdcard 上，不需要
            // 自连 ADB 就能写——走蜂窝时正是靠它让守护脚本自己退出，界面不再被拉回前台。
            StopFlag.set(this)
            try {
                stopService(Intent(this, RcAgentService::class.java))
            } catch (t: Throwable) {
                noticeView.text = "停止失败: " + (t.message ?: "")
            }
        }
        refreshStatus()
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
        noticeView.text = result
        stayOnBox.isChecked = prefs.stayOn
        refreshStatus()
    }

    /**
     * Do exactly the next missing thing, then say what is still missing.
     *
     * The pressed text is written *before* the action so a specific message from
     * the action (a refused intent, "pick 允许 in the dialog") wins over it.
     */
    private fun advancePermission() {
        val step = currentStep()
        noticeView.text = PermPlan.pressed(step)
        when (step) {
            PermPlan.Step.RUNTIME -> requestAgentPermissions()
            PermPlan.Step.ACCESSIBILITY -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            PermPlan.Step.BATTERY -> requestBatteryExemption()
            PermPlan.Step.DONE -> Unit
        }
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
            noticeView.text = "无法打开设置: $action"
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
            noticeView.text = "请在系统弹窗里选择「允许」"
        } catch (t: Throwable) {
            noticeView.text = "本机不支持直接申请，已打开设置列表"
            openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
    }

    /** Which permission the wizard would do next, asked fresh every refresh. */
    private fun currentStep(): PermPlan.Step = PermPlan.next(
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED,
        PhoneAccessibilityService.instance != null,
        Battery.isExempt(this)
    )

    private fun toggleAdvanced() {
        advancedBox.visibility =
            if (advancedBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /**
     * 四个探测每次刷新都重做，一个都不缓存（v0.6.2-pad12）。
     *
     * 缓存过就一定会骗人：无障碍被系统关掉、电池白名单被 ROM 清掉，这两件事都不
     * 会通知本 App，只有重新读一次才知道。四次读全是本地调用（volatile 字段、
     * Settings、PowerManager、ADB 连接状态），不碰网络，所以 3 秒一次不会跟服务
     * 里的心跳线程抢东西——那条线程在等 relay 的 socket，这里一次 IO 都不做。
     */
    private fun refreshStatus() {
        val snap = probe()
        val health = HealthPlan.evaluate(snap)
        statusTitle.text = health.title
        statusTitle.setTextColor(
            when (health.level) {
                HealthPlan.Level.OK -> COLOR_RUNNING
                HealthPlan.Level.WARN -> COLOR_WARN
                HealthPlan.Level.DOWN -> COLOR_STOPPED
            }
        )
        statusDetail.text = health.detail
        paintMain(health.level)
        paintLamp(lampA11y, HealthPlan.lampOn(snap, UiText.LAMP_A11Y))
        paintLamp(lampBattery, HealthPlan.lampOn(snap, UiText.LAMP_BATTERY))
        paintLamp(lampAdb, HealthPlan.lampOn(snap, UiText.LAMP_ADB))
        toggleButton.text = if (snap.serviceRunning) UiText.STOP else UiText.START
        if (stayOnBox.isChecked != prefs.stayOn) stayOnBox.isChecked = prefs.stayOn
        permHint.text = PermPlan.hint(currentStep())
    }

    /** 每次都真的去问一遍系统，见 [refreshStatus] 的注释。 */
    private fun probe(): HealthPlan.Snapshot = HealthPlan.Snapshot(
        serviceRunning = RcAgentService.running,
        accessibilityOn = Admin.accessibilityEnabled(this),
        batteryWhitelisted = Battery.isExempt(this),
        selfAdbConnected = SelfAdb.isConnected()
    )

    /** 三盏小灯横排等宽：哪一项不对就哪一盏灰着，扫一眼就知道去补什么。 */
    private fun lampRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, 0, 0, dp(18))
        lampA11y = addLamp(row, UiText.LAMP_A11Y)
        lampBattery = addLamp(row, UiText.LAMP_BATTERY)
        lampAdb = addLamp(row, UiText.LAMP_ADB)
        return row
    }

    private fun addLamp(row: LinearLayout, label: String): View {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.HORIZONTAL
        cell.gravity = Gravity.CENTER
        val dot = circle(SMALL_LAMP_DP)
        cell.addView(dot)
        val text = TextView(this)
        text.text = label
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        text.setTextColor(COLOR_DETAIL)
        text.setPadding(dp(6), 0, 0, 0)
        cell.addView(text)
        row.addView(
            cell,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        return dot
    }

    /**
     * 一个圆点。用 [GradientDrawable] 而不是 drawable 资源：这个 App 没有 XML
     * 布局，也不该为一个圆点多一个资源文件。
     */
    private fun circle(sizeDp: Int): View {
        val dot = View(this)
        val shape = GradientDrawable()
        shape.shape = GradientDrawable.OVAL
        shape.setColor(COLOR_LAMP_OFF)
        dot.background = shape
        dot.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return dot
    }

    private fun paintLamp(dot: View, on: Boolean) {
        (dot.background as? GradientDrawable)?.setColor(if (on) COLOR_LAMP_ON else COLOR_LAMP_OFF)
    }

    private fun paintMain(level: HealthPlan.Level) {
        (mainLamp.background as? GradientDrawable)?.setColor(
            when (level) {
                HealthPlan.Level.OK -> COLOR_RUNNING
                HealthPlan.Level.WARN -> COLOR_WARN
                HealthPlan.Level.DOWN -> COLOR_STOPPED
            }
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

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
        /** 3 秒：比 2 秒省一半唤醒，肉眼看不出延迟，又能在拔掉 ADB 后几秒内变灰。 */
        private const val REFRESH_MS = 3_000L
        private const val REQ_PERMISSIONS = 100
        private const val REQ_BACKGROUND_LOCATION = 101

        private const val MAIN_LAMP_DP = 24
        private const val SMALL_LAMP_DP = 12

        /** 运行中/已停止的两种颜色：光看字在太阳底下不够，颜色要跟着变。 */
        private val COLOR_RUNNING = Color.parseColor("#2E7D32")
        private val COLOR_STOPPED = Color.parseColor("#C62828")

        /** 部分异常用琥珀色：不是绿的（别以为没事），也不是红的（服务还在跑）。 */
        private val COLOR_WARN = Color.parseColor("#F9A825")

        /** 小灯的亮/灭，以及副行与灯标的灰字。 */
        private val COLOR_LAMP_ON = Color.parseColor("#2E7D32")
        private val COLOR_LAMP_OFF = Color.parseColor("#BDBDBD")
        private val COLOR_DETAIL = Color.parseColor("#757575")
    }
}
