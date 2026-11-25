package net.de5.phoneagent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** What every command reports back to the relay. */
data class ExecutionResult(
    val exit: Int,
    val stdout: String,
    val stderr: String
)

/** The relay truncates stdout at 200 000 chars; stay below that (SPEC.md §2). */
const val MAX_STDOUT_CHARS = 190_000

/**
 * Uploads one named blob to the relay.
 *
 * A one-method interface rather than a direct [RcClient] dependency so the
 * executor stays testable and so a missing relay client degrades to the base64
 * path instead of failing.
 */
fun interface BlobUploader {
    fun upload(name: String, bytes: ByteArray): BlobUpload
}

internal fun batteryPercent(context: Context): Int {
    return try {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    } catch (t: Throwable) {
        -1
    }
}

internal fun isScreenOn(context: Context): Boolean {
    return try {
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        manager?.isInteractive ?: false
    } catch (t: Throwable) {
        false
    }
}

/** Free-form `env` field, e.g. `android=16;model=meizu22;acc=on;bat=87;scr=on`. */
internal fun deviceEnv(context: Context): String {
    val acc = if (PhoneAccessibilityService.instance != null) "on" else "off"
    val scr = if (isScreenOn(context)) "on" else "off"
    return "android=" + Build.VERSION.RELEASE +
        ";model=" + Build.MODEL.replace(' ', '_') +
        ";acc=" + acc +
        ";bat=" + batteryPercent(context) +
        ";scr=" + scr +
        ";opt=" + Battery.flag(context) +
        adbWifiField()
}

/**
 * `adbwifi=<port>` appears only while a port was discovered within the last
 * 10 minutes; a stale cache is omitted rather than sent as a guess, so the
 * operator never sees a port that has already moved.
 */
private fun adbWifiField(): String {
    val field = AdbWifi.cachedEnvField() ?: return ""
    return ";" + field
}

/**
 * Turns a parsed [Command] into an [ExecutionResult].
 *
 * Runs on the poll thread. Everything that has to touch the accessibility
 * service is either dispatched to the main thread ([onMain]) or posts the
 * framework call itself (screenshots, gestures), so the main thread never
 * blocks for longer than a single framework call.
 */
class CommandExecutor(
    private val context: Context,
    private val locations: LocationHelper,
    private val onLocationFix: (LocationFix) -> Unit,
    private val onStopRequested: () -> Unit,
    /**
     * Blob transport for `shot`, or null when the caller has no relay client.
     * A null uploader simply keeps every screenshot on the base64 path.
     */
    private val blobs: BlobUploader? = null
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        // 自连 ADB 要 context 才能读 filesDir 里的密钥、才能起 mDNS 发现。
        // 在这里注入而不是每个命令里传：Executor 是常驻对象，生命周期正好对得上。
        SelfAdb.init(context)
    }

    fun execute(command: Command): ExecutionResult = clip(
        when (command) {
            is Command.Ping ->
                ExecutionResult(EXIT_OK, "pong", "")

            is Command.Status ->
                status()

            is Command.Shot ->
                shot(command)

            is Command.RcShot ->
                rcShot(command)

            is Command.RcUnknown ->
                ExecutionResult(EXIT_USAGE, ShotBlob.error("未知内置动作：" + command.verb), "")

            is Command.RcBadPayload ->
                ExecutionResult(EXIT_USAGE, ShotBlob.error("bad #rc payload: " + command.detail), "")

            is Command.Tree ->
                tree(command)

            is Command.Tap ->
                tap(command)

            is Command.LongPress ->
                longPress(command)

            is Command.Swipe ->
                swipe(command)

            is Command.Key ->
                key(command)

            is Command.Click ->
                elementClick(command)

            is Command.LongClick ->
                elementLongClick(command)

            is Command.Scroll ->
                elementScroll(command)

            is Command.ImeEnter ->
                imeEnter()

            is Command.Wake ->
                Wake.wake(context)

            is Command.Text ->
                inputText(command)

            is Command.Apps ->
                apps()

            is Command.App ->
                launchApp(command)

            is Command.Loc ->
                loc()

            is Command.LocLoop ->
                locLoop(command)

            is Command.LocStop ->
                locStop()

            is Command.Install ->
                install(command)

            is Command.Uninstall ->
                uninstall(command)

            is Command.Settings ->
                settings(command)

            is Command.Logs ->
                logs(command)

            is Command.LogsSize ->
                logsSize()

            is Command.LogsTrim ->
                logsTrim(command)

            is Command.Confirm ->
                confirm(command)

            is Command.AdminStatus ->
                adminStatus()

            is Command.AdminRelease ->
                adminRelease()

            is Command.A11yHeal ->
                a11yHeal()

            is Command.A11yRescue ->
                a11yRescue()

            is Command.A11yStatus ->
                a11yStatus()

            is Command.Lock ->
                lockNow()

            is Command.Reboot ->
                reboot()

            is Command.AdbInfo ->
                adbInfo()

            is Command.AdbHeal ->
                adbHeal(command)

            is Command.Dl ->
                dl(command)

            is Command.FilesList ->
                filesList(command)

            is Command.FilesCat ->
                filesCat(command)

            is Command.FilesInfo ->
                filesInfo(command)

            is Command.FilesRemove ->
                filesRemove(command)

            is Command.FilesMkdir ->
                filesMkdir(command)

            is Command.FilesFind ->
                filesFind(command)

            is Command.FilesDf ->
                filesDf()

            is Command.FilesPull ->
                filesPull(command)

            is Command.NotifList ->
                notifList(command)

            is Command.Say ->
                say(command)

            is Command.ScreenStayOn ->
                screenStayOn(command)

            is Command.ScreenTimeout ->
                screenTimeout(command)

            is Command.ScreenOn ->
                screenOn()

            is Command.ScreenOff ->
                screenOff()

            is Command.SmsList ->
                smsList(command)

            is Command.SmsSend ->
                smsSend(command)

            is Command.AnimGet ->
                animGet()

            is Command.AnimSet ->
                animSet(command)

            is Command.AnimSave ->
                animSave()

            is Command.AnimRestore ->
                animRestore()

            is Command.CallDial ->
                callDial(command)

            is Command.CallAnswer ->
                callAnswer()

            is Command.CallHangup ->
                callHangup()

            is Command.CallState ->
                callState()

            is Command.CallSpeaker ->
                callSpeaker(command)

            is Command.ShInfo ->
                shInfo()

            is Command.Sh ->
                sh(command)

            is Command.ShPair ->
                shPair(command)

            is Command.ShConnect ->
                shConnect()

            is Command.ShKey ->
                shKey(command)

            is Command.ShKeyShow ->
                ExecutionResult(EXIT_OK, SelfAdb.showKey(), "")

            is Command.ShKeyImport ->
                shKeyImport(command)

            is Command.ShKeyAdopt ->
                ExecutionResult(EXIT_PERMISSION, "", SelfAdb.adopt())

            is Command.ShDest ->
                shDest(command)

            is Command.Watchdog ->
                watchdog(command)

            is Command.Stop ->
                stopAgent()

            is Command.Unknown ->
                ExecutionResult(EXIT_USAGE, "", "unknown command: ${command.name}")

            is Command.BadUsage ->
                ExecutionResult(EXIT_USAGE, "", command.detail)
        }
    )

    private fun status(): ExecutionResult {
        val json = JSONObject()
        json.put("ok", true)
        json.put("ver", BuildConfig.VERSION_NAME)
        json.put("acc", PhoneAccessibilityService.instance != null)
        json.put("svc", RcAgentService.running)
        json.put("scr", isScreenOn(context))
        json.put("bat", batteryPercent(context))
        json.put("loc", locations.hasPermission())
        json.put("loop", locations.loopInterval)
        json.put("opt", Battery.flag(context))
        json.put("queue", 0)
        // 新增字段（v0.5.5）：守护进程的缓存状态。既有字段一个都没改名 ——
        // 控制器那侧按名字取值，改一个就是断一次。真值用 `watchdog status`。
        json.put("guard", Watchdog.state())
        return ExecutionResult(EXIT_OK, json.toString(), "")
    }

    /**
     * \`shot [b64] [maxPx] [quality]\` (SPEC.md section 4).
     *
     * Preferred path: capture one JPEG, PUT it to \`/agent/blob/\`, print a short
     * one-line JSON on stdout. The base64 payload used to be squeezed through
     * the relay's 200 000 char stdout budget, which silently truncated big
     * screenshots; a blob has no such ceiling.
     *
     * Every failure still yields a screenshot: the already-encoded JPEG is
     * base64'd in place (no second capture) and the reason goes to stderr.
     * \`shot b64\` and a missing [blobs] uploader go straight to the old ladder
     * path, byte for byte.
     */
    private fun shot(command: Command.Shot): ExecutionResult {
        val service = PhoneAccessibilityService.instance ?: return notConnected()
        val uploader = blobs
        if (command.forceBase64 || uploader == null) {
            return ScreenShot.capture(service, command.maxPx, command.quality)
        }
        return when (val captured = ScreenShot.captureJpeg(service, command.maxPx, command.quality)) {
            is ShotCapture.Failed -> captured.result
            is ShotCapture.Ok -> deliverShot(service, command, captured.jpeg, uploader)
        }
    }

    private fun deliverShot(
        service: AccessibilityService,
        command: Command.Shot,
        jpeg: ShotJpeg,
        uploader: BlobUploader
    ): ExecutionResult {
        val name = ShotBlob.name(System.currentTimeMillis(), ShotBlob.randomSuffix())
        val upload = uploader.upload(name, jpeg.bytes)
        if (upload.ok) {
            return ExecutionResult(
                EXIT_OK,
                ShotBlob.json(name, jpeg.size, jpeg.width, jpeg.height, jpeg.quality),
                "w=" + jpeg.width + " h=" + jpeg.height + " bytes=" + jpeg.size + " blob=" + name
            )
        }
        return base64Fallback(service, command, jpeg, upload.error ?: "unknown error")
    }

    /**
     * Reuse the JPEG we already have instead of taking a second screenshot: a
     * fresh capture would race the platform's rate limiter. Only when the base64
     * of that JPEG would still overflow the relay budget do we fall back to a
     * full [ScreenShot.capture], which owns the re-encode ladder.
     */
    private fun base64Fallback(
        service: AccessibilityService,
        command: Command.Shot,
        jpeg: ShotJpeg,
        reason: String
    ): ExecutionResult {
        val note = "blob upload failed: " + reason + "，已回退 base64"
        val encoded = Base64.encodeToString(jpeg.bytes, Base64.NO_WRAP)
        if (encoded.length <= ShotLadder.MAX_BASE64_CHARS) {
            return ExecutionResult(
                EXIT_OK,
                encoded,
                "w=" + jpeg.width + " h=" + jpeg.height + " bytes=" + jpeg.size +
                    " b64=" + encoded.length + " (" + note + ")"
            )
        }
        val fresh = ScreenShot.capture(service, command.maxPx, command.quality)
        if (fresh.exit != EXIT_OK) return fresh
        return ExecutionResult(fresh.exit, fresh.stdout, fresh.stderr + " (" + note + ")")
    }

    /**
     * \`#rc: shot {...}\` — the controller's built-in action protocol (SPEC.md
     * section 4.2). That contract forbids the base64 fallback: the caller parses
     * the one-line JSON and then fetches the blob, so a silent switch to base64
     * would look like a corrupt reply. Failures are \`{"ok":false}\` with exit 5.
     */
    private fun rcShot(command: Command.RcShot): ExecutionResult {
        val service = PhoneAccessibilityService.instance
            ?: return rcFailure("blob upload failed: accessibility service not connected")
        val uploader = blobs ?: return rcFailure("blob upload failed: relay client unavailable")
        return when (val captured = ScreenShot.captureJpeg(service, command.maxPx, command.quality)) {
            is ShotCapture.Failed -> rcFailure(captured.result.stderr)
            is ShotCapture.Ok -> {
                val jpeg = captured.jpeg
                val name = ShotBlob.name(System.currentTimeMillis(), ShotBlob.randomSuffix())
                val upload = uploader.upload(name, jpeg.bytes)
                if (!upload.ok) {
                    rcFailure("blob upload failed: " + (upload.error ?: "unknown error"))
                } else {
                    ExecutionResult(
                        EXIT_OK,
                        ShotBlob.json(name, jpeg.size, jpeg.width, jpeg.height, jpeg.quality),
                        "w=" + jpeg.width + " h=" + jpeg.height + " bytes=" + jpeg.size + " blob=" + name
                    )
                }
            }
        }
    }

    /** The relay protocol's action-failure code is 5, not "not found". */
    private fun rcFailure(reason: String): ExecutionResult =
        ExecutionResult(EXIT_NOT_FOUND, ShotBlob.error(reason), reason)

    /**
     * `tree`: read the screen, then read it once more [TreeSettle.SETTLE_MS]
     * later and report the later answer when the two disagree.
     *
     * The second read is what makes a layout that landed mid-read visible; the
     * root-level fix in [PhoneAccessibilityService.roots] is what stops the
     * cached-snapshot case, and this covers the rest without turning `tree` into
     * a slow command — one retry, 150 ms, nothing more (SPEC.md section 5).
     */
    private fun tree(command: Command.Tree): ExecutionResult {
        if (command.viaUia) return treeViaUia(command.maxNodes)
        val service = PhoneAccessibilityService.instance ?: return notConnected()
        val first = readTree(service, command.maxNodes)
            ?: return ExecutionResult(EXIT_TIMEOUT, "", "tree timed out")
        try {
            Thread.sleep(TreeSettle.SETTLE_MS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            return ExecutionResult(EXIT_OK, first, "")
        }
        val second = readTree(service, command.maxNodes)
        return ExecutionResult(EXIT_OK, TreeSettle.pick(first, second), "")
    }

    /** @return the formatted tree, or null when the main thread never answered. */
    private fun readTree(service: PhoneAccessibilityService, maxNodes: Int): String? =
        onMain(TREE_TIMEOUT_MS) { UiTree.format(service.roots(), maxNodes) }

    /**
     * `tree uia` — read the screen as uid 2000 through uiautomator (v0.6.1-pad12).
     *
     * The escape hatch for the accessibility channel going stale: on this phone
     * `rootInActiveWindow` keeps answering with the window as it was at the last
     * window event, while `uiautomator dump` reads the live view tree at the same
     * moment. Same line format either way ([UiaDump] hands its tree to
     * [UiTree.format]), so a caller can swap one for the other.
     *
     * Needs the self-ADB link: only uid 2000 may run uiautomator. Without it the
     * answer names the fix instead of quietly falling back — a silent fallback
     * would return the very stale tree this command exists to escape.
     */
    private fun treeViaUia(maxNodes: Int): ExecutionResult {
        if (!SelfAdb.isConnected()) {
            return ExecutionResult(
                EXIT_PERMISSION,
                "",
                Admin.ERROR_PREFIX + "tree uia 走 uid 2000 的 uiautomator，需要自连 ADB：" +
                    SelfAdbPlan.notConnected()
            )
        }
        val result = offMain(UIA_TIMEOUT_MS + SH_SLACK_MS) {
            SelfAdb.shell(UiaDump.dumpCommand(), UIA_TIMEOUT_MS)
        } ?: return ExecutionResult(EXIT_TIMEOUT, "", SelfAdbPlan.timeoutText(UIA_TIMEOUT_MS))
        if (result.precondition) return ExecutionResult(EXIT_PERMISSION, "", result.text)
        if (result.code == null) return ExecutionResult(EXIT_TIMEOUT, "", result.text)
        if (UiaDump.failed(result.text)) {
            return ExecutionResult(
                EXIT_PERMISSION,
                "",
                Admin.ERROR_PREFIX + "uiautomator dump 失败（uid 2000 也读不到这份视图树）；" +
                    "回执: " + SelfAdbPlan.output(result.text)
            )
        }
        val tree = UiaDump.parse(result.text, maxNodes)
        if (tree.isEmpty()) {
            return ExecutionResult(
                EXIT_NOT_FOUND,
                "",
                Admin.ERROR_PREFIX + "uiautomator dump 没给出可解析的 <hierarchy>（文件 " +
                    UiaDump.DUMP_PATH + "，留着可手动取证）"
            )
        }
        return ExecutionResult(EXIT_OK, tree, "")
    }

    private fun tap(command: Command.Tap): ExecutionResult {
        val service = PhoneAccessibilityService.instance ?: return notConnected()
        return if (Gestures.tap(service, command.x, command.y)) {
            ExecutionResult(EXIT_OK, "tap ${command.x} ${command.y}", "")
        } else {
            ExecutionResult(EXIT_TIMEOUT, "", "gesture did not complete")
        }
    }

    private fun longPress(command: Command.LongPress): ExecutionResult {
        val service = PhoneAccessibilityService.instance ?: return notConnected()
        return if (Gestures.longPress(service, command.x, command.y, command.ms)) {
            ExecutionResult(EXIT_OK, "long ${command.x} ${command.y} ${command.ms}", "")
        } else {
            ExecutionResult(EXIT_TIMEOUT, "", "gesture did not complete")
        }
    }

    private fun swipe(command: Command.Swipe): ExecutionResult {
        val service = PhoneAccessibilityService.instance ?: return notConnected()
        val ok = Gestures.swipe(
            service,
            command.x1,
            command.y1,
            command.x2,
            command.y2,
            command.ms
        )
        return if (ok) {
            ExecutionResult(
                EXIT_OK,
                "swipe ${command.x1} ${command.y1} ${command.x2} ${command.y2} ${command.ms}",
                ""
            )
        } else {
            ExecutionResult(EXIT_TIMEOUT, "", "gesture did not complete")
        }
    }

    private fun key(command: Command.Key): ExecutionResult {
        val service = PhoneAccessibilityService.instance ?: return notConnected()
        return if (Gestures.globalAction(service, command.name)) {
            ExecutionResult(EXIT_OK, "key ${command.name}", "")
        } else {
            ExecutionResult(EXIT_USAGE, "", "global action failed: ${command.name}")
        }
    }

    /**
     * `click SEL` (SPEC.md section 3.1). The tree read and `ACTION_CLICK` run on
     * the main thread; the gesture fallback is dispatched from *this* (the poll)
     * thread, because [Gestures] blocks its caller while it posts to the main
     * thread — doing that on the main thread would deadlock the looper.
     */
    private fun elementClick(command: Command.Click): ExecutionResult {
        val service = PhoneAccessibilityService.instance ?: return notConnected()
        return ElementActions.click(
            service,
            command.selector,
            mainDispatch(ELEMENT_TIMEOUT_MS),
            command.apply
        )
    }

    private fun elementLongClick(command: Command.LongClick): ExecutionResult {
        val service = PhoneAccessibilityService.instance ?: return notConnected()
        return ElementActions.longClick(
            service,
            command.selector,
            mainDispatch(ELEMENT_TIMEOUT_MS),
            apply = command.apply
        )
    }

    /**
     * A [ElementActions.MainDispatch] that runs framework reads on the main
     * thread while the gesture fallback stays on the caller's thread.
     */
    private fun mainDispatch(timeoutMs: Long): ElementActions.MainDispatch =
        object : ElementActions.MainDispatch {
            override fun <T> run(block: () -> T): T? = onMain(timeoutMs) { block() }
        }

    /**
     * `scroll` only calls `performAction`, which is a plain framework call on the
     * node — it needs the main thread but has no gesture fallback, so the whole
     * operation can be posted as one piece.
     */
    private fun elementScroll(command: Command.Scroll): ExecutionResult {
        val service = PhoneAccessibilityService.instance ?: return notConnected()
        return onMain(ELEMENT_TIMEOUT_MS) {
            ElementActions.scroll(service, command, service.roots())
        } ?: ExecutionResult(EXIT_TIMEOUT, "", "scroll timed out")
    }

    private fun imeEnter(): ExecutionResult {
        val service = PhoneAccessibilityService.instance ?: return notConnected()
        return onMain(TEXT_TIMEOUT_MS) { ElementActions.imeEnter(service.roots()) }
            ?: ExecutionResult(EXIT_TIMEOUT, "", "ime enter timed out")
    }

    private fun inputText(command: Command.Text): ExecutionResult {
        val service = PhoneAccessibilityService.instance ?: return notConnected()
        val outcome = onMain(TEXT_TIMEOUT_MS) { setText(service, command.value) }
        return when (outcome) {
            null -> ExecutionResult(EXIT_TIMEOUT, "", "text input timed out")
            SET_TEXT_OK -> ExecutionResult(EXIT_OK, "text " + command.value.length, "")
            else -> ExecutionResult(EXIT_NOT_FOUND, "", "no editable text field")
        }
    }

    private fun apps(): ExecutionResult {
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = manager.queryIntentActivities(intent, 0)
        val lines = ArrayList<String>(resolved.size)
        for (info in resolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            lines.add(pkg + "\t" + info.loadLabel(manager).toString())
        }
        lines.sort()
        return ExecutionResult(EXIT_OK, lines.joinToString("\n"), "")
    }

    private fun launchApp(command: Command.App): ExecutionResult {
        val manager = context.packageManager
        val launch = manager.getLaunchIntentForPackage(command.pkg)
            ?: return ExecutionResult(EXIT_NOT_FOUND, "", "package not found: ${command.pkg}")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launch)
            ExecutionResult(EXIT_OK, "launched ${command.pkg}", "")
        } catch (t: Throwable) {
            ExecutionResult(EXIT_NOT_FOUND, "", "cannot launch ${command.pkg}: ${t.message}")
        }
    }

    private fun loc(): ExecutionResult {
        if (!locations.hasPermission()) {
            return ExecutionResult(EXIT_PERMISSION, "", LOCATION_PERMISSION_DENIED)
        }
        val fix = locations.oneShot(LocationHelper.DEFAULT_TIMEOUT_MS)
            ?: return ExecutionResult(EXIT_TIMEOUT, "", "no location fix")
        return ExecutionResult(EXIT_OK, locationJson(fix), "")
    }

    private fun locLoop(command: Command.LocLoop): ExecutionResult {
        if (!locations.hasPermission()) {
            return ExecutionResult(EXIT_PERMISSION, "", LOCATION_PERMISSION_DENIED)
        }
        locations.startLoop(command.interval, onLocationFix)
        return ExecutionResult(EXIT_OK, "locloop " + locations.loopInterval, "")
    }

    private fun locStop(): ExecutionResult {
        locations.stopLoop()
        return ExecutionResult(EXIT_OK, "locstop", "")
    }

    // ------------------------------------------------------------ privileged

    /**
     * install URL — download, verify the PK header, then start the system
     * installer on the file.
     *
     * The cached APK is deliberately **not** deleted here. A silent session
     * could unlink the file right after committing, but the installer UI reads
     * the `content://` lazily, possibly after this command has already returned;
     * deleting now would break an install the operator just approved. The file
     * stays in `cacheDir/apk` under a name unique per download, and the system
     * reclaims cache whenever it wants the space.
     */
    private fun install(command: Command.Install): ExecutionResult {
        val downloaded = Downloader.fetch(context, command.url)
        if (downloaded is Download.Failed) {
            return ExecutionResult(EXIT_NOT_FOUND, "", downloaded.message)
        }
        val file = (downloaded as Download.Ok).file
        return adminResult(Admin.launchInstall(context, file.absolutePath))
    }

    /**
     * confirm [t=SECONDS] TEXT — press the button the installer is showing.
     *
     * Runs on the poll thread, so retrying here does not block the main thread;
     * each attempt reads the tree through [mainDispatch] and reports the outcome
     * of the attempt that finally matched.
     */
    private fun confirm(command: Command.Confirm): ExecutionResult {
        val service = PhoneAccessibilityService.instance ?: return notConnected()
        return ElementActions.confirm(
            service,
            command.text,
            command.timeoutSeconds,
            mainDispatch(ELEMENT_TIMEOUT_MS),
            command.apply
        )
    }

    private fun uninstall(command: Command.Uninstall): ExecutionResult =
        adminResult(Admin.launchUninstall(context, command.pkg))

    private fun settings(command: Command.Settings): ExecutionResult =
        adminResult(Admin.setSetting(context, command.namespace, command.key, command.value))

    /**
     * logs [n] — 自连 ADB 优先（v0.5.9-pad12）。
     *
     * Android 16 给 logcat 加了运行时「日志访问」授权：App 以自己的 uid 起
     * logcat，系统就弹 `LogAccessDialogActivity`，只给「允许一次」，**每次都要
     * 重弹**。无人值守的机器上没人点那个框，所以 App 自读这条路在真机上等于
     * 不可用（`pm grant READ_LOGS` 也救不了，权限 granted=true 弹框照旧）。
     *
     * uid 2000 的 logcat 不需要这个授权，所以连上自连 ADB 就走它；没连上才
     * 退回 App 自读，并把「为什么这条路是死的、出路在哪」写清楚（[LogSource]）。
     */
    private fun logs(command: Command.Logs): ExecutionResult =
        when (val plan = LogSource.plan(SelfAdb.isConnected(), command.lines)) {
            is LogSource.Plan.Adb -> logsViaAdb(plan.command)
            is LogSource.Plan.Local -> {
                val text = Admin.readLogcat(command.lines)
                if (text.startsWith(Admin.ERROR_PREFIX)) {
                    ExecutionResult(EXIT_PERMISSION, "", text)
                } else {
                    ExecutionResult(EXIT_OK, text, "")
                }
            }
        }

    /**
     * 走自连 ADB 读日志（uid 2000，不弹框）。
     *
     * 失败要区分「链路没通」和「命令超时」：前者是操作员该去 shconnect 的事，
     * 后者是等一会儿重试的事，混成一句话会让人一直重连。
     */
    private fun logsViaAdb(command: String): ExecutionResult {
        val result = offMain(LOGS_ADB_TIMEOUT_MS + SH_SLACK_MS) {
            SelfAdb.shell(command, LOGS_ADB_TIMEOUT_MS)
        } ?: return ExecutionResult(EXIT_TIMEOUT, "", SelfAdbPlan.timeoutText(LOGS_ADB_TIMEOUT_MS))
        if (result.precondition) return ExecutionResult(EXIT_PERMISSION, "", result.text)
        if (result.code == null) return ExecutionResult(EXIT_TIMEOUT, "", result.text)
        return ExecutionResult(EXIT_OK, SelfAdbPlan.output(result.text), "")
    }

    /**
     * `logs size` — 三处账本：内存环形缓冲、守护日志、缓存目录里的 APK 残留（v0.5.8-pad12）。
     *
     * 三处必须分开报，因为它们由三个不同的东西管：环形缓冲在进程里，守护日志在
     * /data/local/tmp（只有 self-adb 读得到），APK 在 cacheDir。合成一个总数等于
     * 告诉操作员「日志有点大」，然后什么也修不了。
     */
    private fun logsSize(): ExecutionResult {
        val guard = GuardProbe.read()
        return ExecutionResult(
            EXIT_OK,
            LogSizes.sizeReport(
                guard.first,
                guard.second,
                AgentLog.size(),
                AgentLog.droppedCount(),
                Downloader.cached(context)
            ),
            ""
        )
    }

    /**
     * `logs trim` — 裁守护日志 + 清超 24 小时的 APK 残留。
     *
     * 默认 dry-run，只报「将裁掉什么」；`logs trim apply` 才真删。判定只有一份
     * （[LogSizes.stale] 与 [WatchdogPlan.LOG_MAX_BYTES]），dry-run 与 apply 差
     * 的只是动不动手，所以报告不会出现「预览说会删、执行时却按另一套标准」。
     */
    private fun logsTrim(command: Command.LogsTrim): ExecutionResult {
        val before = GuardProbe.read()
        val apks = Downloader.cached(context)
        val stale = LogSizes.stale(apks, System.currentTimeMillis())
        var after: LogSizes.Guard? = null
        if (command.apply) {
            // 只有真拿到回执才回读；没跑成就不回读，报告里那一段会写「没回读到」，
            // 而不是替裁剪成功吹牛。
            if (GuardProbe.trim()) after = GuardProbe.read().first
            Downloader.discardStale(context, stale)
        }
        return ExecutionResult(
            EXIT_OK,
            LogSizes.trimReport(
                before.first,
                before.second,
                stale,
                apks.size - stale.size,
                command.apply,
                after
            ),
            ""
        )
    }

    private fun adminStatus(): ExecutionResult = ExecutionResult(
        EXIT_OK,
        Admin.stateReport(context),
        ""
    )

    private fun adminRelease(): ExecutionResult = adminResult(Admin.releaseOwner(context))

    /**
     * `a11y heal` — inspect, then run the repair that fits.
     *
     * v0.4.2 called [Admin.enableAccessibilitySelfHeal] unconditionally, which
     * only appends a missing entry; on a phone whose entry is present and whose
     * binding is dead it answered "无障碍条目已存在" and changed nothing. That is
     * the failure this command was written for, so the state is read first and a
     * dead binding goes to the rescue path.
     */
    private fun a11yHeal(): ExecutionResult {
        val live = PhoneAccessibilityService.instance != null
        val listed = Admin.isAccessibilityListed(context)
        val text = offMain(A11Y_TIMEOUT_MS) {
            Admin.repairAccessibility(context, listed, live)
        } ?: return ExecutionResult(EXIT_TIMEOUT, "", "无障碍修复超时（" + A11Y_TIMEOUT_MS + "ms）")
        return adminResult(text)
    }

    /**
     * `a11y rescue` — always the toggle.
     *
     * Runs off the poll thread: the sequence deliberately sleeps ~2 s between
     * releasing and re-adding the component (see [A11yOps.RESCUE_SLEEP_MS]), and
     * blocking the poll loop would delay the result POST for every other command.
     */
    private fun a11yRescue(): ExecutionResult {
        val text = offMain(A11Y_TIMEOUT_MS) {
            Admin.forceReconnectAccessibility(context)
        } ?: return ExecutionResult(EXIT_TIMEOUT, "", "强制重绑超时（" + A11Y_TIMEOUT_MS + "ms）")
        return adminResult(text)
    }

    /** `a11y status` — read-only, so it always answers with exit 0. */
    private fun a11yStatus(): ExecutionResult = ExecutionResult(
        EXIT_OK,
        A11yPlan.report(
            Admin.accessibilityEnabled(context),
            Admin.isAccessibilityListed(context),
            PhoneAccessibilityService.instance != null,
            AgentLog.lastLine("a11y")
        ),
        ""
    )

    private fun lockNow(): ExecutionResult = adminResult(Admin.lockNow(context))

    private fun reboot(): ExecutionResult = adminResult(Admin.rebootNow(context))

    /**
     * Admin reports failures as text prefixed with [Admin.ERROR_PREFIX] instead
     * of throwing; the permission exit code is the closest honest mapping.
     */
    private fun adminResult(text: String): ExecutionResult =
        if (text.startsWith(Admin.ERROR_PREFIX)) {
            ExecutionResult(EXIT_PERMISSION, "", text)
        } else {
            ExecutionResult(EXIT_OK, text, "")
        }

    /**
     * `adbinfo` — read-only: never writes a setting, never starts anything.
     *
     * The discovery budget is short on purpose; this is a query, and a query
     * that blocks for 20 s is useless in a script.
     */
    private fun adbInfo(): ExecutionResult = adbReport(enable = false, budgetMs = ADBINFO_BUDGET_MS)

    /** `adbheal` — flips wireless debugging on when needed, then reports. */
    private fun adbHeal(command: Command.AdbHeal): ExecutionResult =
        adbReport(enable = true, budgetMs = command.timeoutSeconds * 1000L)

    /**
     * A refused settings write comes back as [Admin.ERROR_PREFIX] text and maps
     * to `exit=6`; everything else is a successful, honest report — including
     * "no broadcast found", which is an answer, not a failure.
     */
    private fun adbReport(enable: Boolean, budgetMs: Long): ExecutionResult {
        val text = try {
            AdbWifi.report(context, enable, budgetMs)
        } catch (t: Throwable) {
            Admin.ERROR_PREFIX + "无线调试自愈异常: " + describe(t)
        }
        return adminResult(text)
    }

    /**
     * `dl` — fetch a file into `/sdcard/Movies` for a media player.
     *
     * The name is resolved here (not in the downloader) so the exact file name
     * the operator will see is decided by pure, testable code, and the stamp
     * fallback is taken from the wall clock at command time.
     */
    private fun dl(command: Command.Dl): ExecutionResult {
        val name = DownloadPlan.resolveName(command.url, command.name, System.currentTimeMillis())
        val outcome = try {
            DlDownloader.download(context, command.url, name, command.timeoutSeconds)
        } catch (t: Throwable) {
            DlOutcome.Failed(LOCAL_ERROR_PREFIX + describe(t), 0L, DlFail.LOCAL)
        }
        return when (outcome) {
            is DlOutcome.Ok ->
                ExecutionResult(EXIT_OK, DownloadPlan.success(name, outcome.bytes), "")

            is DlOutcome.Failed -> {
                val message = DownloadPlan.failure(outcome.reason, outcome.written)
                val exit = when (outcome.kind) {
                    DlFail.TIMEOUT -> EXIT_TIMEOUT
                    DlFail.REMOTE -> EXIT_NOT_FOUND
                    DlFail.LOCAL -> EXIT_PERMISSION
                }
                ExecutionResult(exit, "", message)
            }
        }
    }


    /**
     * The seven device-surface groups (SPEC.md sections 3.7-3.13).
     *
     * Every one of them is a thin adapter: the wording and the clamps live in
     * the *Plan / *Ops pair, so a test can prove the text without a device. The
     * only decision made here is which exit code a sentence maps to, and that is
     * one helper ([fromOps]) for all of them.
     */
    private fun fromOps(text: String, denied: Int = EXIT_PERMISSION): ExecutionResult =
        if (text.startsWith(Admin.ERROR_PREFIX)) {
            ExecutionResult(denied, "", text)
        } else {
            ExecutionResult(EXIT_OK, text, "")
        }

    private fun filesList(command: Command.FilesList): ExecutionResult =
        FileOps.list(command.path, command.limit).let { result ->
            ExecutionResult(result.exit, result.stdout, result.stderr)
        }

    private fun filesCat(command: Command.FilesCat): ExecutionResult =
        FileOps.cat(command.path).let { result ->
            ExecutionResult(result.exit, result.stdout, result.stderr)
        }

    private fun filesInfo(command: Command.FilesInfo): ExecutionResult =
        FileOps.info(command.path).let { result ->
            ExecutionResult(result.exit, result.stdout, result.stderr)
        }

    private fun filesRemove(command: Command.FilesRemove): ExecutionResult =
        FileOps.remove(command.path, command.apply).let { result ->
            ExecutionResult(result.exit, result.stdout, result.stderr)
        }

    private fun filesMkdir(command: Command.FilesMkdir): ExecutionResult =
        FileOps.mkdir(command.path).let { result ->
            ExecutionResult(result.exit, result.stdout, result.stderr)
        }

    private fun filesFind(command: Command.FilesFind): ExecutionResult =
        FileOps.find(command.path, command.keyword, command.limit).let { result ->
            ExecutionResult(result.exit, result.stdout, result.stderr)
        }

    private fun filesDf(): ExecutionResult =
        FileOps.df().let { result ->
            ExecutionResult(result.exit, result.stdout, result.stderr)
        }

    /**
     * `files pull PATH` — the only way bytes leave the phone.
     *
     * The blob name is generated before the upload so the answer can name what
     * landed even when the relay rejects it; a missing uploader is its own
     * sentence, because "no relay configured" is not a file problem.
     */
    private fun filesPull(command: Command.FilesPull): ExecutionResult {
        val uploader = blobs
            ?: return ExecutionResult(
                EXIT_PERMISSION,
                "",
                "relay 客户端未就绪（files pull 依赖 blob 通道）"
            )
        val payload = FileOps.pullPayload(
            command.path,
            System.currentTimeMillis(),
            ShotBlob.randomSuffix()
        )
        if (payload.exit != EXIT_OK) {
            return ExecutionResult(payload.exit, "", payload.message)
        }
        val bytes = payload.bytes ?: return ExecutionResult(
            EXIT_PERMISSION,
            "",
            "读取失败: " + payload.path
        )
        val upload = uploader.upload(payload.name, bytes)
        if (!upload.ok) {
            return ExecutionResult(EXIT_PERMISSION, "", PullPlan.failure(upload.error ?: "unknown error"))
        }
        AgentLog.add("files", "已上传 " + payload.path + " -> " + payload.name)
        return ExecutionResult(EXIT_OK, PullPlan.json(payload.name, upload.bytes, payload.path), "")
    }

    /**
     * `notif` — the listener is the grant, so "not connected" is reported with
     * the exact adb line rather than a permission error: an operator who has not
     * run it yet has nothing to fix in settings.
     */
    private fun notifList(command: Command.NotifList): ExecutionResult {
        val items = NotifOps.snapshot()
        val text = NotifPlan.report(items, command.limit, command.query)
        return ExecutionResult(EXIT_OK, text, "")
    }

    private fun say(command: Command.Say): ExecutionResult = fromOps(
        SayOps.speak(
            context,
            SayPlan.clipText(command.text),
            command.rate,
            command.pitch,
            command.lang
        )
    )

    /**
     * `screen stayon [on|off]` — the sticky switch.
     *
     * Reported as `exit=6` when the write is refused, because the only reason it
     * can be refused is a missing WRITE_SECURE_SETTINGS: the operator has
     * something to run, and the message names it.
     */
    private fun screenStayOn(command: Command.ScreenStayOn): ExecutionResult =
        fromOps(ScreenOps.stayOn(context, command.on))

    private fun screenTimeout(command: Command.ScreenTimeout): ExecutionResult =
        fromOps(ScreenOps.setTimeout(context, command.ms))

    private fun screenOn(): ExecutionResult = fromOps(ScreenOps.turnOn(context))

    private fun screenOff(): ExecutionResult = fromOps(ScreenOps.turnOff(context))

    private fun smsList(command: Command.SmsList): ExecutionResult {
        val items = SmsOps.list(context, command.limit)
        if (items.isEmpty() && !Admin.granted(context, android.Manifest.permission.READ_SMS)) {
            return ExecutionResult(EXIT_PERMISSION, "", SmsPlan.denied("READ_SMS 未授予"))
        }
        return ExecutionResult(EXIT_OK, SmsPlan.report(items, command.limit), "")
    }

    /**
     * `sms send [apply] NUMBER TEXT` — dry run by default.
     *
     * The dry run is the whole safety mechanism: the body goes to a real person
     * and cannot be recalled, so the operator sees the exact number and the
     * exact text before anything is sent.
     */
    private fun smsSend(command: Command.SmsSend): ExecutionResult {
        if (!command.apply) {
            return ExecutionResult(
                EXIT_OK,
                "将发送短信到 " + command.number + ": " + SmsPlan.clip(command.text) +
                    "（dry-run：未发送，确认后执行 sms send apply " + command.number + " " +
                    SmsPlan.clip(command.text) + "）",
                ""
            )
        }
        if (!Admin.granted(context, android.Manifest.permission.SEND_SMS)) {
            return ExecutionResult(
                EXIT_PERMISSION,
                "",
                "发送短信需要 SEND_SMS（未授予：adb shell pm grant net.de5.phoneagent " +
                    "android.permission.SEND_SMS）"
            )
        }
        val failure = SmsOps.send(context, command.number, SmsPlan.clip(command.text))
        if (failure != null) return ExecutionResult(EXIT_PERMISSION, "", failure)
        return ExecutionResult(EXIT_OK, SmsPlan.sent(command.number, SmsPlan.clip(command.text).length), "")
    }

    private fun animGet(): ExecutionResult = ExecutionResult(EXIT_OK, AnimOps.state(context), "")

    private fun animSet(command: Command.AnimSet): ExecutionResult = fromOps(AnimOps.set(context, command.value))

    private fun animSave(): ExecutionResult = ExecutionResult(EXIT_OK, AnimOps.save(context), "")

    private fun animRestore(): ExecutionResult = fromOps(AnimOps.restore(context))

    /**
     * `call [apply] NUMBER` — dry run by default, same reasoning as `sms send`:
     * a wrong digit rings a stranger.
     */
    private fun callDial(command: Command.CallDial): ExecutionResult {
        if (!command.apply) {
            return ExecutionResult(EXIT_OK, CallPlan.dryRun(command.number), "")
        }
        return fromOps(CallOps.dial(context, command.number))
    }

    private fun callAnswer(): ExecutionResult = fromOps(CallOps.answer(context))

    private fun callHangup(): ExecutionResult = fromOps(CallOps.hangUp(context))

    private fun callState(): ExecutionResult = ExecutionResult(EXIT_OK, CallOps.state(context), "")

    private fun callSpeaker(command: Command.CallSpeaker): ExecutionResult =
        fromOps(CallOps.speaker(context, command.on))

    private fun shInfo(): ExecutionResult = ExecutionResult(EXIT_OK, SelfAdb.status(), "")

    /**
     * `sh [t=SECONDS] [apply] COMMAND`.
     *
     * 默认 dry run：这条命令能以 uid 2000 跑任何东西，而它又是最容易在日志里
     * 被随手重放的一条——不设闸门，一次误重放就可能 `rm -rf` 掉整个 /sdcard。
     *
     * 退出码语义（写进 SPEC，别让操作员猜）：
     * - 命令跑完 → 0，远端退出码在 stdout 的 `exit=N` 里；
     * - 没配对/没连接 → 6（EXIT_PERMISSION），不是「命令失败」；
     * - 超时 → 4（EXIT_TIMEOUT）。
     *
     * 远端退出码不直接当 exit 用：2/3/4/5/6/7 在本协议里都有既定含义，
     * 混用会让「adb shell 里 exit 4」看起来像我们自己的超时。
     */
    private fun sh(command: Command.Sh): ExecutionResult {
        if (!command.apply) {
            return ExecutionResult(EXIT_OK, SelfAdbPlan.dryRun(command.command), "")
        }
        // 多给 5 秒：哨兵要等命令自己结束，看门狗到点才收流，正好卡在边界上
        // 会把「刚好跑完」误报成超时。
        val result = offMain(command.timeoutMs + SH_SLACK_MS) {
            SelfAdb.shell(command.command, command.timeoutMs)
        } ?: return ExecutionResult(
            EXIT_TIMEOUT,
            "",
            SelfAdbPlan.timeoutText(command.timeoutMs)
        )
        if (result.precondition) return ExecutionResult(EXIT_PERMISSION, "", result.text)
        val code = result.code ?: return ExecutionResult(EXIT_TIMEOUT, "", result.text)
        return ExecutionResult(
            EXIT_OK,
            SelfAdbPlan.output(SelfAdbPlan.shellOk(code, result.text)),
            ""
        )
    }

    private fun shPair(command: Command.ShPair): ExecutionResult {
        val text = offMain(PAIR_TIMEOUT_MS) { SelfAdb.pair(command.code) }
            ?: return ExecutionResult(EXIT_TIMEOUT, "", "配对超时（" + PAIR_TIMEOUT_MS + "ms 内没有结果）")
        if (text != SelfAdbPlan.pairOk()) {
            return ExecutionResult(EXIT_PERMISSION, "", text)
        }
        // 配对只交换密钥，连接是另一步；顺手连一次，省掉操作员一条命令。
        val connected = offMain(CONNECT_TIMEOUT_MS) { SelfAdb.connect() }
        val suffix = when {
            connected == null -> "\n（自动连接超时，稍后执行 shconnect）"
            connected == SelfAdbPlan.connectOk() -> "\n" + connected
            else -> "\n（自动连接未成功：" + connected + "；稍后执行 shconnect 重试）"
        }
        return ExecutionResult(EXIT_OK, text + suffix, "")
    }

    private fun shConnect(): ExecutionResult {
        val text = offMain(CONNECT_TIMEOUT_MS) { SelfAdb.connect() }
            ?: return ExecutionResult(EXIT_TIMEOUT, "", "连接超时（" + CONNECT_TIMEOUT_MS + "ms 内没有结果）")
        return if (text == SelfAdbPlan.connectOk()) {
            ExecutionResult(EXIT_OK, text, "")
        } else {
            ExecutionResult(EXIT_PERMISSION, "", text)
        }
    }

    /**
     * `shdest DEST` —— 调试专用。
     *
     * 恒 exit 0：它的输出是给人看的证据（destination 原文 + hex + 文本 + 异常类名），
     * 不是一次「成功/失败」的判断。用退出码表达成败会让 A/B 结果被 exit code
     * 掩盖，而这正是它要查的东西。
     */
    private fun shDest(command: Command.ShDest): ExecutionResult {
        val text = offMain(SelfAdbPlan.SHDEST_TIMEOUT_MS + SH_SLACK_MS) {
            SelfAdb.dest(command.destination)
        } ?: return ExecutionResult(
            EXIT_OK,
            SelfAdbPlan.destReportTimeout(command.destination, ByteArray(0), SelfAdbPlan.SHDEST_TIMEOUT_MS),
            ""
        )
        return ExecutionResult(EXIT_OK, text, "")
    }

    /**
     * `watchdog [status|arm|disarm]` —— uid 2000 看门脚本。
     *
     * 三件事都走自连 ADB，所以每一次都可能卡在网络/进程上，统一 offMain 加超时。
     * status 恒 exit 0（只读报告）；arm/disarm 失败按 EXIT_PERMISSION，因为绝大多数
     * 失败原因是「自连 ADB 没连上」，那是权限/前置条件问题，不是命令写错。
     */
    private fun watchdog(command: Command.Watchdog): ExecutionResult {
        return when (command.action) {
            "arm" -> {
                val text = offMain(WATCHDOG_TIMEOUT_MS) { Watchdog.arm() }
                    ?: return ExecutionResult(EXIT_TIMEOUT, "", "守护启用超时（" + WATCHDOG_TIMEOUT_MS / 1000 + "s）")
                if (text.startsWith(Admin.ERROR_PREFIX)) ExecutionResult(EXIT_PERMISSION, "", text)
                else ExecutionResult(EXIT_OK, text, "")
            }
            "disarm" -> {
                val text = offMain(WATCHDOG_TIMEOUT_MS) { Watchdog.disarm() }
                    ?: return ExecutionResult(EXIT_TIMEOUT, "", "守护停止超时（" + WATCHDOG_TIMEOUT_MS / 1000 + "s）")
                if (text.startsWith(Admin.ERROR_PREFIX)) ExecutionResult(EXIT_PERMISSION, "", text)
                else ExecutionResult(EXIT_OK, text, "")
            }
            else -> {
                val text = offMain(WATCHDOG_TIMEOUT_MS) { Watchdog.status() }
                    ?: return ExecutionResult(EXIT_TIMEOUT, "", "守护状态查询超时（" + WATCHDOG_TIMEOUT_MS / 1000 + "s）")
                ExecutionResult(EXIT_OK, text, "")
            }
        }
    }

    private fun shKey(command: Command.ShKey): ExecutionResult {
        if (!command.regen) return ExecutionResult(EXIT_OK, SelfAdb.keyReport(), "")
        if (!command.apply) return ExecutionResult(EXIT_OK, SelfAdbPlan.regenDryRun(), "")
        return ExecutionResult(EXIT_OK, SelfAdb.regenerate(), "")
    }

    /**
     * `shkey import` 不需要 apply：它不点屏、不删用户数据，只覆盖自己的密钥文件。
     *
     * 但下载要走网络，可能卡住，所以套一层超时，超时按 EXIT_TIMEOUT 报，
     * 别让操作员对着一条永远不返回的命令猜。
     */
    private fun shKeyImport(command: Command.ShKeyImport): ExecutionResult {
        val text = offMain(IMPORT_TIMEOUT_MS) {
            SelfAdb.importKey(command.pemUrl, command.pubUrl)
        } ?: return ExecutionResult(EXIT_TIMEOUT, "", "导入超时（" + IMPORT_TIMEOUT_MS / 1000 + "s 内没下完）")
        return if (text.startsWith(IMPORT_FAILED_PREFIX)) {
            ExecutionResult(EXIT_PERMISSION, "", text)
        } else {
            ExecutionResult(EXIT_OK, text, "")
        }
    }

    private fun stopAgent(): ExecutionResult {
        onStopRequested()
        return ExecutionResult(EXIT_OK, "stopping", "")
    }

    /** @return SET_TEXT_OK when the text landed, SET_TEXT_NO_FIELD otherwise. */
    private fun setText(service: PhoneAccessibilityService, value: String): Int {
        val root = service.rootInActiveWindow ?: return SET_TEXT_NO_FIELD
        val target = findEditable(root) ?: return SET_TEXT_NO_FIELD
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            value
        )
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            SET_TEXT_OK
        } else {
            SET_TEXT_NO_FIELD
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        firstMatch(node, true) ?: firstMatch(node, false)

    private fun firstMatch(node: AccessibilityNodeInfo?, requireFocused: Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && (!requireFocused || node.isFocused)) return node
        var i = 0
        val count = node.childCount
        while (i < count) {
            val hit = firstMatch(node.getChild(i), requireFocused)
            if (hit != null) return hit
            i++
        }
        return null
    }

    private fun notConnected(): ExecutionResult =
        ExecutionResult(EXIT_NO_ACCESSIBILITY, "", "accessibility service not connected")

    private fun clip(result: ExecutionResult): ExecutionResult {
        if (result.stdout.length <= MAX_STDOUT_CHARS) return result
        return ExecutionResult(
            result.exit,
            result.stdout.substring(0, MAX_STDOUT_CHARS),
            result.stderr + " (stdout truncated)"
        )
    }

    /**
     * Run [block] on the main thread and wait for it.
     *
     * @return the block's result, or null when it timed out or threw.
     */
    private fun <T> onMain(timeoutMs: Long, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try {
                block()
            } catch (t: Throwable) {
                null
            }
        }
        val latch = CountDownLatch(1)
        val holder = AtomicReference<T?>(null)
        mainHandler.post {
            holder.set(
                try {
                    block()
                } catch (t: Throwable) {
                    null
                }
            )
            latch.countDown()
        }
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        return holder.get()
    }

    /**
     * Run [block] on a worker thread and wait for it.
     *
     * For repairs that must sleep (the accessibility rescue waits ~2 s for the
     * framework to release the binding): doing that inline would stall the poll
     * loop, so the result POST for every other queued command would be late too.
     *
     * @return the block's result, or null when it timed out or threw.
     */
    private fun <T> offMain(timeoutMs: Long, block: () -> T): T? {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<T?>(null)
        val thread = Thread {
            holder.set(
                try {
                    block()
                } catch (t: Throwable) {
                    null
                }
            )
            latch.countDown()
        }
        thread.isDaemon = true
        thread.name = "rc-repair"
        try {
            thread.start()
        } catch (t: Throwable) {
            return null
        }
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        return holder.get()
    }

    companion object {
        private const val TREE_TIMEOUT_MS = 15_000L
        /** `tree uia` — one dump plus the cat back, measured at 1-2 s on the phone. */
        private const val UIA_TIMEOUT_MS = 15_000L
        /** The accessibility rescue sleeps ~2 s on purpose; leave room for it. */
        private const val A11Y_TIMEOUT_MS = 15_000L
        private const val TEXT_TIMEOUT_MS = 10_000L
        /** Element actions walk the tree and may dispatch a gesture. */
        private const val ELEMENT_TIMEOUT_MS = 15_000L
        private const val SET_TEXT_OK = 1
        private const val SET_TEXT_NO_FIELD = 0
        private const val LOCATION_PERMISSION_DENIED = "location permission not granted"
        /** `adbinfo` is a query: one mDNS round is enough to answer it. */
        private const val ADBINFO_BUDGET_MS = 6_000L

        /** Prefix for downloader failures that never reached the network. */
        private const val LOCAL_ERROR_PREFIX = "下载异常: "

        /** `sh` 的看门狗余量：哨兵要等命令自己结束，边界上不能误判超时。 */
        private const val SH_SLACK_MS = 5_000L

    /**
     * 自连 ADB 读日志的预算。logcat -d 是 dump 完就退出，30 秒足够；
     * 给太短会把「日志有点多」误报成链路故障。
     */
    private const val LOGS_ADB_TIMEOUT_MS = 30_000L


        /** 配对/连接的外部预算比发现预算各多给 10 秒，留出 TLS 握手时间。 */
        private const val PAIR_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 30_000L

        /** 两次 HTTP 各给 15s 连接 + 30s 读取，40s 足够，再久就是网真的不通。 */
        private const val IMPORT_TIMEOUT_MS = 40_000L

        /** 与 SelfAdbPlan.importFailed 的前缀保持一致，用来判定失败退出码。 */
        private const val IMPORT_FAILED_PREFIX = "导入失败："

        /**
         * 守护命令的预算。写脚本 + setsid + 回读进程表是三次 shell 往返，
         * 单次 10 秒（Watchdog.RUN_TIMEOUT_MS），这里给三倍余量。
         */
        private const val WATCHDOG_TIMEOUT_MS = 35_000L
    }
}
