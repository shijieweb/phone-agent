package net.de5.phoneagent

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
        ";opt=" + Battery.flag(context)
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
    private val onStopRequested: () -> Unit
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

    private fun shot(command: Command.Shot): ExecutionResult {
        val service = PhoneAccessibilityService.instance ?: return notConnected()
        return ScreenShot.capture(service, command.maxPx, command.quality)
    }

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
        return ElementActions.click(service, command.selector, mainDispatch(ELEMENT_TIMEOUT_MS))
    }

    private fun elementLongClick(command: Command.LongClick): ExecutionResult {
        val service = PhoneAccessibilityService.instance ?: return notConnected()
        return ElementActions.longClick(
            service,
            command.selector,
            mainDispatch(ELEMENT_TIMEOUT_MS)
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
    }
}
