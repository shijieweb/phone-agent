package net.de5.phoneagent

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock

/**
 * Foreground service holding the outbound long poll: hello -> poll -> execute
 * -> result (SPEC.md sections 2 and 7).
 *
 * The loop runs on its own thread; the accessibility work it triggers is
 * dispatched to the main thread by [Gestures] / [ScreenShot] / [CommandExecutor].
 */
class RcAgentService : Service() {

    private var client: RcClient? = null
    private var executor: CommandExecutor? = null
    private var locations: LocationHelper? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pollThread: Thread? = null
    private var heartThread: Thread? = null

    /**
     * Liveness for *this* incarnation of the service. The worker threads check
     * this, never the shared [running] flag — see [LoopGuard] for the bug that
     * made a shared flag insufficient.
     */
    private var guard: LoopGuard? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-entry is decided by "does this instance already own workers", not by
        // the shared flag: an instance that is already running must not start a
        // second pair of threads.
        if (guard == null) start()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must not kill the agent: restart in 1 s.
        try {
            val restart = Intent(this, RcAgentService::class.java)
            val pending = PendingIntent.getService(
                this,
                RESTART_REQUEST_CODE,
                restart,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarms = getSystemService(AlarmManager::class.java)
            alarms?.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
                pending
            )
        } catch (t: Throwable) {
            lastError = "restart scheduling failed: " + (t.message ?: "")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Order matters. Stop this generation *first*, so a worker that is about
        // to wake up sees its own guard as dead; then interrupt it, so it leaves
        // the long poll instead of sitting in a 45 s socket read. Without the
        // interrupt the old thread would only notice on its next iteration —
        // which is how the duplicate-poll window opened in 0.5.0.
        val mine = guard
        guard = null
        mine?.stop()
        interrupt(pollThread)
        interrupt(heartThread)
        pollThread = null
        heartThread = null
        // Only the current instance may clear the shared state: a superseded
        // instance whose onDestroy lands late must not report the replacement as
        // stopped, nor blank out its registration.
        val isCurrent = current == this
        if (isCurrent) {
            running = false
        }
        try {
            locations?.stopLoop()
        } catch (t: Throwable) {
            // ignore
        }
        val lock = wakeLock
        wakeLock = null
        if (lock != null) {
            try {
                if (lock.isHeld) lock.release()
            } catch (t: Throwable) {
                // ignore
            }
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            // ignore
        }
        // Only deregister ourselves (see `isCurrent` above).
        if (isCurrent) current = null
        super.onDestroy()
    }

    private fun interrupt(thread: Thread?) {
        if (thread == null) return
        try {
            thread.interrupt()
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun start() {
        val prefs = Prefs(this)
        if (!prefs.isConfigured()) {
            note = "未配置 relay 地址或口令"
            lastError = note
            running = false
            stopSelf()
            return
        }

        note = null
        running = true
        current = this

        // Supersede any previous incarnation before spawning our own workers, so
        // a stale thread still blocked in `relay.poll()` is dead the moment it
        // wakes up, whatever the shared `running` flag says.
        val mine = LoopGuard()
        guard = mine
        val generation = mine.generation

        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } catch (t: Throwable) {
            lastError = "startForeground failed: " + (t.message ?: "")
        }

        acquireWakeLock()

        val info = AgentInfo(
            hostname = Build.DEVICE,
            user = Build.MODEL.replace(' ', '_'),
            os = "android-" + Build.VERSION.RELEASE,
            ver = BuildConfig.VERSION_NAME
        )
        client = RcClient(prefs.relayBase, prefs.secret, prefs.agentId, info)

        val helper = LocationHelper(this)
        locations = helper
        executor = CommandExecutor(
            context = this,
            locations = helper,
            onLocationFix = { fix -> submitFix(fix) },
            onStopRequested = { stopSelf() }
        )

        locationInterval = prefs.locationInterval

        pollThread = worker("rc-poll") { pollLoop(generation) }
        heartThread = worker("rc-heartbeat") { heartLoop(generation) }
    }

    private fun worker(name: String, body: () -> Unit): Thread {
        val thread = Thread(Runnable { body() }, name)
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private fun pollLoop(generation: Int) {
        var failures = 0
        var lastHelloAt = 0L
        var autoCollected = false
        // The guard is read through the field so a stopped instance is observed
        // immediately; the generation pins the loop to the instance that made it.
        while (guardOf(generation)) {
            try {
                renewWakeLock()
                val relay = client
                if (relay == null) {
                    sleep(1000L)
                    continue
                }

                val now = System.currentTimeMillis()
                if (now - lastHelloAt >= HELLO_INTERVAL_MS) {
                    val ok = relay.hello(deviceEnv(this))
                    lastHelloAt = System.currentTimeMillis()
                    lastHelloMs = lastHelloAt
                    if (!ok) {
                        AgentLog.add("hello", "relay 未确认（ok=false）")
                    }
                }

                val polled = relay.poll(POLL_WAIT_SECONDS)
                // `poll()` may have returned because we were interrupted during
                // onDestroy; re-check before acting on the (empty) result.
                if (!guardOf(generation)) return
                lastPollMs = System.currentTimeMillis()
                if (failures > 0) {
                    AgentLog.add("poll", "链路恢复（此前连续失败 " + failures + " 次）")
                }
                failures = 0
                lastError = null
                if (polled == null) continue

                val line = polled.cmd
                lastCommand = line
                AgentLog.add("cmd", line + "  cid=" + polled.cid)
                val command = Commands.parse(line)
                val started = System.currentTimeMillis()
                val result = executor?.execute(command)
                    ?: ExecutionResult(EXIT_INTERRUPTED, "", "agent service is stopping")
                val duration = (System.currentTimeMillis() - started) / 1000.0
                AgentLog.add(
                    "exec",
                    "exit=" + result.exit + " " + String.format(java.util.Locale.US, "%.1fs", duration)
                )

                // Always report, even on failure: the relay needs the cid closed.
                relay.result(polled.cid, line, result.exit, duration, result.stdout, result.stderr)

                if (command is Command.Stop) {
                    running = false
                    stopSelf()
                    return
                }
            } catch (t: Throwable) {
                // An interrupt means onDestroy() is unwinding us: leave at once
                // instead of treating it as a network failure and retrying.
                if (Thread.currentThread().isInterrupted) return
                failures++
                lastError = t.javaClass.simpleName + ": " + (t.message ?: "")
                AgentLog.add("error", "第 " + failures + " 次失败 " + describe(t))
                // Copy a report once per run, while the log still shows what
                // happened right before the failure. Deliberately not per
                // outage: a flat link would otherwise clobber the clipboard and
                // wipe out whatever the operator had not pasted yet.
                if (!autoCollected) {
                    autoCollected = true
                    autoCollect(this, t)
                }
                if (failures >= MAX_FAILURES_BEFORE_LONG_PAUSE) {
                    // Give the network a real chance, then re-register.
                    sleep(LONG_RETRY_PAUSE_MS)
                    failures = 0
                    lastHelloAt = 0L
                } else {
                    sleep(RETRY_PAUSE_MS)
                }
            }
        }
    }

    /**
     * True while [generation] is the live generation of this service instance.
     * The instance's own guard is consulted, so a service that has been destroyed
     * reports false even if a *newer* instance exists and is running.
     */
    private fun guardOf(generation: Int): Boolean {
        if (Thread.currentThread().isInterrupted) return false
        val mine = guard ?: return false
        return mine.isRunning(generation)
    }

    /**
     * On the very first failure of a run, copy a report to the clipboard so the
     * operator can paste it back. Only the first one: a flat network would
     * otherwise overwrite the clipboard every few seconds.
     */
    private fun autoCollect(context: android.content.Context, cause: Throwable) {
        try {
            val text = Diagnostics.stateOnly(Prefs(context)) +
                "\n--- 首个异常 ---\n" + describe(cause) + "\n" + stackOf(cause)
            Clipboard.copy(context, text)
            note = "已把失败报告复制到剪贴板（" +
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()) + "），请粘贴发回"
            AgentLog.add("diag", "已自动复制报告到剪贴板")
        } catch (t: Throwable) {
            AgentLog.add("diag", "自动复制失败: " + describe(t))
        }
    }

    /** `hello` is the heartbeat: never let it go stale for more than 60 s. */
    private fun heartLoop(generation: Int) {
        while (guardOf(generation)) {
            try {
                val relay = client
                if (relay != null && System.currentTimeMillis() - lastHelloMs >= HELLO_INTERVAL_MS) {
                    relay.hello(deviceEnv(this))
                    lastHelloMs = System.currentTimeMillis()
                }
            } catch (t: Throwable) {
                // The poll loop reports failures; the heartbeat just keeps trying.
            }
            sleep(HEARTBEAT_CHECK_MS)
        }
    }

    /** Unsolicited location report: a synthetic cid, as SPEC.md section 9 says. */
    private fun submitFix(fix: LocationFix) {
        val relay = client ?: return
        try {
            relay.result(
                "loc-" + System.currentTimeMillis(),
                "loc",
                EXIT_OK,
                0.0,
                locationJson(fix),
                ""
            )
        } catch (t: Throwable) {
            lastError = "location report failed: " + (t.message ?: "")
        }
    }

    fun loopSeconds(): Int = locations?.loopInterval ?: 0

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Phone Agent",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Agent")
            .setContentText("relay agent running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            val manager = getSystemService(PowerManager::class.java)
            val lock = manager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            lock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLock = lock
        } catch (t: Throwable) {
            lastError = "wake lock failed: " + (t.message ?: "")
        }
    }

    private fun renewWakeLock() {
        val lock = wakeLock ?: return
        try {
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "phone-agent"
        private const val WAKE_LOCK_TAG = "phoneagent:poll"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val RESTART_REQUEST_CODE = 4711
        private const val RESTART_DELAY_MS = 1_000L

        /** `wait` is clamped to 25 s by the server (SPEC.md section 2). */
        const val POLL_WAIT_SECONDS = 25
        const val HELLO_INTERVAL_MS = 60_000L
        const val HEARTBEAT_CHECK_MS = 15_000L
        const val RETRY_PAUSE_MS = 3_000L
        const val LONG_RETRY_PAUSE_MS = 30_000L
        const val MAX_FAILURES_BEFORE_LONG_PAUSE = 5

        @Volatile
        var running: Boolean = false

        @Volatile
        var lastPollMs: Long = 0L

        @Volatile
        var lastHelloMs: Long = 0L

        @Volatile
        var lastError: String? = null

        @Volatile
        var lastCommand: String? = null

        @Volatile
        var note: String? = null

        @Volatile
        var locationInterval: Int = Prefs.DEFAULT_LOCATION_INTERVAL

        @Volatile
        private var current: RcAgentService? = null

        /** Static snapshot for the UI: no network I/O, safe to call from anywhere. */
        fun snapshot(): String {
            val now = System.currentTimeMillis()
            val sb = StringBuilder()
            sb.append("服务: ").append(if (running) "运行中" else "已停止").append('\n')
            sb.append("无障碍: ")
                .append(if (PhoneAccessibilityService.instance != null) "已开启" else "未开启")
                .append('\n')
            sb.append("最近轮询: ").append(age(now, lastPollMs)).append('\n')
            sb.append("最近心跳: ").append(age(now, lastHelloMs)).append('\n')
            val loop = current?.loopSeconds() ?: 0
            sb.append("定位上报: ")
                .append(if (loop > 0) loop.toString() + "s" else "关闭")
                .append(" (配置 ").append(locationInterval).append("s)\n")
            sb.append("最近命令: ").append(lastCommand ?: "无").append('\n')
            sb.append("错误: ").append(lastError ?: "无")
            val extra = note
            if (extra != null) sb.append('\n').append(extra)
            return sb.toString()
        }

        private fun age(now: Long, then: Long): String {
            if (then <= 0L) return "从未"
            val seconds = (now - then) / 1000L
            if (seconds < 0L) return "0s 前"
            return if (seconds < 90L) {
                seconds.toString() + "s 前"
            } else {
                (seconds / 60L).toString() + "min 前"
            }
        }
    }
}
