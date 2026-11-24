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

            is Command.Confirm ->
                confirm(command)

            is Command.AdminStatus ->
                adminStatus()

            is Command.AdminRelease ->
                adminRelease()

            is Command.A11yHeal ->
                a11yHeal()

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

    private fun tree(command: Command.Tree): ExecutionResult {
        val service = PhoneAccessibilityService.instance ?: return notConnected()
        val text = onMain(TREE_TIMEOUT_MS) { UiTree.format(service.roots(), command.maxNodes) }
            ?: return ExecutionResult(EXIT_TIMEOUT, "", "tree timed out")
        return ExecutionResult(EXIT_OK, text, "")
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
     * logs [n] — no Device Owner needed to *try*; the framework decides whether
     * READ_LOGS is held and logcat prints its own refusal, which we pass through.
     */
    private fun logs(command: Command.Logs): ExecutionResult {
        val text = Admin.readLogcat(command.lines)
        if (text.startsWith(Admin.ERROR_PREFIX)) {
            return ExecutionResult(EXIT_PERMISSION, "", text)
        }
        return ExecutionResult(EXIT_OK, text, "")
    }

    private fun adminStatus(): ExecutionResult = ExecutionResult(
        EXIT_OK,
        Admin.stateReport(context),
        ""
    )

    private fun adminRelease(): ExecutionResult = adminResult(Admin.releaseOwner(context))

    private fun a11yHeal(): ExecutionResult =
        adminResult(Admin.enableAccessibilitySelfHeal(context))

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

    companion object {
        private const val TREE_TIMEOUT_MS = 15_000L
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
    }
}
