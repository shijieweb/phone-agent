package net.de5.phoneagent

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 自己跟自己说话的那条 adb 通道。
 *
 * 手机上的「无线调试」本身就是 adbd 在 127.0.0.1 上开的端口，配对与连接都走
 * mDNS 广播（_adb-tls-pairing / _adb-tls-connect）。所以这里只做三件事：
 * 发现端口、用本机密钥配对/连接、在 shell: 流上跑命令。
 *
 * 三条不能破的规矩：
 *
 * 1. **只连 127.0.0.1**。绝不拿局域网 IP 去连——那会把「自己连自己」变成
 *    「暴露一个可被同网段扫描的调试端口」，而且魅族的 adbd 默认只监听回环。
 * 2. **失败必须说清是哪一步失败**。未配对、未连接、端口没发现是三种完全不同
 *    的处理路径，静默降级成「命令没输出」会让老板以为命令本身有问题。
 * 3. **用完只 disconnect，绝不 close**。AbsAdbConnectionManager.close() 会
 *    销毁私钥对象，下次还得重新生成，等于每次命令换一把钥匙。
 *
 * 线程模型：这里全是阻塞调用（仓库零第三方依赖，没有协程），由 Executor 的
 * 后台线程调用，不会碰主线程。
 */
internal object SelfAdb {

    private const val SETTINGS_NAMESPACE = "global"
    private const val ADB_WIFI_KEY = "adb_wifi_enabled"
    private const val BOUNCE_OFF_MS = 1_500L
    private const val READ_CHUNK = 8 * 1024

    /** PTY 轮询间隔：10ms 足够快（提示符几十毫秒内到），又不会把 CPU 烧起来。 */
    private const val PROMPT_POLL_MS = 10L

    /** 与 AgentLog 同一个 tag，logcat -s PhoneAgent:I 就能同时看到两边的行。 */
    private const val LOGCAT_TAG = "PhoneAgent"

    // 密钥文件很小（私钥 ~1.7 KB，公钥 ~0.7 KB），200 KB 已经宽松到不可能误伤，
    // 又能挡住「URL 指到一个大文件」这种把内存吃满的情况。
    private const val FETCH_CONNECT_TIMEOUT_MS = 15_000
    private const val FETCH_READ_TIMEOUT_MS = 30_000
    private const val FETCH_MAX_BYTES = 200 * 1024
    @Volatile
    private var app: Context? = null

    @Volatile
    private var manager: Manager? = null

    @Volatile
    private var material: SelfAdbKeys.Material? = null

    /**
     * 当前走的是哪条通道。
     *
     * 只有 PTY 开流失败才会降级到 RAW，降级之后这个标志跟着变——
     * shinfo 里显示的必须是「实际走的」，不是「打算走的」。
     */
    @Volatile
    private var ptyChannel: Boolean = true

    @Volatile
    private var lastNote: String? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    // ------------------------------------------------------------ 状态

    fun hasKey(): Boolean {
        val ctx = app ?: return false
        return SelfAdbKeys.hasKey(ctx)
    }

    fun isConnected(): Boolean = manager?.isConnected() == true

    fun lastNote(): String? = lastNote

    /** 换密钥或断线重连前清掉内存里的连接与密钥，避免用旧证书握手。 */
    fun reset() {
        try {
            manager?.disconnect()
        } catch (t: Throwable) {
            // 已经断了
        }
        manager = null
        material = null
    }

    /** `shkey` 的对账输出：公钥那一行原文 + 指纹。 */
    fun keyReport(): String {
        val ctx = app ?: return SelfAdbPlan.keyReport(null, null)
        val keys = ensureMaterial() ?: return SelfAdbPlan.keyReport(null, null)
        val line = try {
            SelfAdbKeys.publicFile(ctx).readText().trim()
        } catch (t: Throwable) {
            null
        }
        return SelfAdbPlan.keyReport(line, SelfAdbKeys.fingerprint(keys.publicKey))
    }

    /**
     * `shkey regen apply` — 删旧密钥、生成新密钥。
     *
     * 必须连内存里的连接与密钥一起清掉：SslUtils 的 SSLContext 是进程级缓存，
     * 不清就会拿着旧 KeyPair 去握手，表现为「配对成功却连不上」。
     */
    fun regenerate(): String {
        val ctx = app ?: return SelfAdbPlan.keyFailed()
        reset()
        SelfAdbKeys.delete(ctx)
        val fresh = SelfAdbKeys.generate(ctx) ?: return SelfAdbPlan.keyFailed()
        material = fresh
        lastNote = null
        return SelfAdbPlan.regenDone(SelfAdbKeys.fingerprint(fresh.publicKey))
    }

    /** `shkey show`：来源（自生成/导入）+ 公钥 + 指纹，只读。 */
    fun showKey(): String {
        val ctx = app ?: return SelfAdbPlan.showReport(null, null, null)
        val line = try {
            val file = SelfAdbKeys.publicFile(ctx)
            if (file.isFile) file.readText().trim().ifBlank { null } else null
        } catch (t: Throwable) {
            null
        }
        val loaded = ensureMaterial()
        return SelfAdbPlan.showReport(
            SelfAdbKeys.origin(ctx),
            line,
            if (loaded == null) null else SelfAdbKeys.fingerprint(loaded.publicKey)
        )
    }

    /**
     * `shkey import URL [PUBURL]` — 下载一把现成密钥并落盘。
     *
     * 下载失败必须说清是哪一段失败（私钥 / 公钥 / 网络），因为这条命令是
     * 操作员在配对走不通时唯一的出路，含糊的报错等于把人堵死。
     */
    fun importKey(pemUrl: String, pubUrl: String?): String {
        val ctx = app ?: return SelfAdbPlan.importFailed("应用上下文未就绪")
        val pem = try {
            fetchText(pemUrl)
        } catch (t: Throwable) {
            return SelfAdbPlan.importFailed("下载私钥失败（" + describe(t) + "）")
        }
        val pub = try {
            fetchText(pubUrl ?: (pemUrl + ".pub"))
        } catch (t: Throwable) {
            return SelfAdbPlan.importFailed("下载公钥失败（" + describe(t) + "）")
        }
        return when (val result = SelfAdbKeys.import(ctx, pem, pub)) {
            is SelfAdbKeys.KeyImport.Ok -> {
                reset()
                material = SelfAdbKeys.load(ctx)
                lastNote = null
                SelfAdbPlan.importOk(result.name, result.fingerprint)
            }
            is SelfAdbKeys.KeyImport.Failed -> result.message
        }
    }

    /** `shkey adopt`：user 版系统上不可能成功，只回一条能指路的说明。 */
    fun adopt(): String = SelfAdbPlan.adoptUnsupported()

    /** 取一个 http(s) 文本资源；只接受 200，且不超过 [FETCH_MAX_BYTES]。 */
    private fun fetchText(url: String): String {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = FETCH_CONNECT_TIMEOUT_MS
            connection.readTimeout = FETCH_READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            val code = connection.responseCode
            if (code != 200) throw java.io.IOException("HTTP " + code)
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size > FETCH_MAX_BYTES) {
                throw java.io.IOException("内容过大 " + bytes.size + " 字节")
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun describe(t: Throwable): String {
        val message = t.message
        return if (message.isNullOrBlank()) t.javaClass.simpleName else message
    }

    fun status(): String {
        val ctx = app
        val keyed = ctx != null && SelfAdbKeys.hasKey(ctx)
        val fingerprint = if (keyed) {
            val loaded = ensureMaterial()
            if (loaded != null) SelfAdbKeys.fingerprint(loaded.publicKey) else null
        } else {
            null
        }
        return SelfAdbPlan.status(keyed, fingerprint, isConnected(), lastNote, ptyChannel)
    }

    // ------------------------------------------------------------ 配对

    /**
     * 用六位配对码配对。
     *
     * 顺序刻意是「先发现端口，再配对」：配对码只有手机屏幕上那一个，
     * 端口却每次开无线调试都可能变，所以端口必须现找。
     */
    fun pair(code: String): String {
        val ctx = app ?: return SelfAdbPlan.notPaired()
        if (!SelfAdbPlan.validPairCode(code)) return SelfAdbPlan.badPairCode()
        val keys = ensureMaterial() ?: return SelfAdbPlan.keyFailed()
        val found = discoverPort(ctx, SelfAdbPlan.SERVICE_PAIRING, SelfAdbPlan.PAIR_BUDGET_MS)
        val port = found.port
            ?: return note(SelfAdbPlan.noPort(SelfAdbPlan.SERVICE_PAIRING, found.ignored))
        return try {
            val session = Manager(keys)
            session.pair(SelfAdbPlan.HOST, port, code)
            lastNote = null
            SelfAdbPlan.pairOk()
        } catch (t: Throwable) {
            // hidden API 拦截和普通握手失败必须分开报：前者再怎么重试都没用，
            // 唯一出路是换一把已被手机授权的密钥（shkey import）。
            val why = if (SelfAdbPlan.isHiddenApiBlock(t)) {
                SelfAdbPlan.hiddenApiBlocked()
            } else {
                SelfAdbPlan.pairFailed(SelfAdbPlan.errorText(t))
            }
            note(why)
        }
    }

    // ------------------------------------------------------------ 连接

    /** 连上本机 adbd；已经连着就直接回成功（幂等，方便命令里反复调用）。 */
    fun connect(): String {
        val ctx = app ?: return SelfAdbPlan.notPaired()
        if (!SelfAdbKeys.hasKey(ctx)) return SelfAdbPlan.notPaired()
        val existing = manager
        if (existing != null && existing.isConnected()) return SelfAdbPlan.connectOk()
        val keys = ensureMaterial() ?: return SelfAdbPlan.keyFailed()
        val found = discoverPort(ctx, SelfAdbPlan.SERVICE_CONNECT, SelfAdbPlan.CONNECT_BUDGET_MS)
        val port = found.port
            ?: return note(SelfAdbPlan.noPort(SelfAdbPlan.SERVICE_CONNECT, found.ignored))
        return try {
            val session = Manager(keys)
            val ok = session.connect(SelfAdbPlan.HOST, port)
            if (!ok) {
                session.disconnect()
                return note(SelfAdbPlan.connectFailed("端口 $port 上没有建立连接"))
            }
            manager = session
            lastNote = null
            SelfAdbPlan.connectOk()
        } catch (t: Throwable) {
            note(SelfAdbPlan.connectFailed(SelfAdbPlan.errorText(t)))
        }
    }

    // ------------------------------------------------------------ 执行

    /**
     * 跑一条 shell 命令。
     *
     * 默认走 PTY（[shellPty]）。只有 PTY 连开流都失败时才降级到 raw
     * （shell:COMMAND + 哨兵）——魅族上 raw 必挂，先试 raw 等于白等一次超时。
     * 降级会改 ptyChannel，shinfo 里看得出来。
     */
    fun shell(command: String, timeoutMs: Long): SelfAdbPlan.Exit {
        val ctx = app ?: return precondition(SelfAdbPlan.notPaired())
        if (!SelfAdbKeys.hasKey(ctx)) return precondition(SelfAdbPlan.notPaired())
        val session = manager
        if (session == null || !session.isConnected()) {
            return precondition(SelfAdbPlan.notConnected())
        }
        val pty = shellPty(command, timeoutMs)
        if (ptyChannel) return pty
        // PTY 开流就失败了，才走这里。pty.text 里已经是降级前的错误，不再往外抛。
        return shellRaw(session, command, timeoutMs)
    }

    /** raw 通道（shell:COMMAND）：只在 PTY 不可用时使用，魅族上必然失败。 */
    private fun shellRaw(session: Manager, command: String, timeoutMs: Long): SelfAdbPlan.Exit {
        var stream: AdbStream? = null
        val timedOut = AtomicBoolean(false)
        return try {
            stream = session.open(SelfAdbPlan.shellCommand(command))
            val raw = readAll(stream, timeoutMs, timedOut)
            if (timedOut.get()) {
                SelfAdbPlan.Exit(null, SelfAdbPlan.timeoutText(timeoutMs))
            } else {
                SelfAdbPlan.parseExit(raw)
            }
        } catch (t: Throwable) {
            // 全栈进 logcat：给用户的文案只能带一句人话，排查要的是类名与行号。
            // 真机上唯一能看到它的通道是 AgentLog（logcat -s PhoneAgent:I）。
            AgentLog.add("sh", "raw fail " + t.javaClass.name + ": " + (t.message ?: "(无 message)"))
            android.util.Log.e(LOGCAT_TAG, "sh raw fail dest=" + SelfAdbPlan.showDest(SelfAdbPlan.shellCommand(command)), t)
            if (timedOut.get()) {
                SelfAdbPlan.Exit(null, SelfAdbPlan.timeoutText(timeoutMs))
            } else {
                SelfAdbPlan.Exit(null, SelfAdbPlan.errorText(t))
            }
        } finally {
            try {
                stream?.close()
            } catch (t: Throwable) {
                // 已经关了
            }
        }
    }

    /**
     * 走 PTY 跑一条命令（默认路径）。
     *
     * 为什么是 PTY：魅族 Flyme（Android 16）上 adbd 对所有带命令的 destination
     * （kRaw 子进程路径）都在回 OKAY 之后立刻 CLSE，真机 A/B 实测 exec:id /
     * shell:id / shell,raw:id / shell,v2,raw:id 全是 IOException: Stream closed.，
     * 唯一活着的是空命令 shell: —— 它走 kPty，会给出一个真正的交互提示符。
     * PTY 是 POSIX 标准能力，所有 ROM 都支持，比 raw 更可靠。
     *
     * 交互式执行的三步，少一步就会「看起来成功但结果错」：
     * 1. 先等提示符（1.5s 预算）：不等就写，命令可能被 shell 吞掉；
     * 2. 写命令 + 换行 + 哨兵行：哨兵带双引号，保证输出是一整行 __RC=N；
     * 3. 读到哨兵出现就收手：PTY 不会自己 EOF，等 EOF 等于等到超时。
     *
     * 代价：PTY 把 stdout 和 stderr 混在一个流里，分不开。文档里写明了这一点，
     * 所以 [SelfAdbPlan.Exit] 的 text 是「合并输出」。
     */
    fun shellPty(command: String, timeoutMs: Long): SelfAdbPlan.Exit {
        val ctx = app ?: return precondition(SelfAdbPlan.notPaired())
        if (!SelfAdbKeys.hasKey(ctx)) return precondition(SelfAdbPlan.notPaired())
        val session = manager
        if (session == null || !session.isConnected()) {
            return precondition(SelfAdbPlan.notConnected())
        }
        var stream: AdbStream? = null
        val out = ByteArrayOutputStream(READ_CHUNK)
        return try {
            stream = session.open(SelfAdbPlan.DEST_PTY)
            ptyChannel = true
            awaitPrompt(stream)
            val payload = SelfAdbPlan.ptyWrite(command).toByteArray(Charsets.UTF_8)
            stream.write(payload, 0, payload.size)
            stream.flush()
            readUntilSentinel(stream, out, timeoutMs)
            val raw = out.toString("UTF-8")
            if (!SelfAdbPlan.hasSentinel(raw)) {
                // 没等到哨兵：退出码未知，但已经收到的内容必须原样交回去，
                // 丢掉它就等于把「命令跑到一半」误报成「什么也没发生」。
                val partial = SelfAdbPlan.parsePty(raw, command)
                return SelfAdbPlan.Exit(null, partial.text)
            }
            SelfAdbPlan.parsePty(raw, command)
        } catch (t: Throwable) {
            AgentLog.add("sh", "pty fail " + t.javaClass.name + ": " + (t.message ?: "(无 message)"))
            android.util.Log.e(LOGCAT_TAG, "sh pty fail", t)
            SelfAdbPlan.Exit(null, SelfAdbPlan.errorText(t))
        } finally {
            try {
                stream?.close()
            } catch (t: Throwable) {
                // 已经关了
            }
        }
    }

    /**
     * 等 PTY 吐出提示符。
     *
     * 用 available() 轮询而不是阻塞 read：读要能随时放弃，否则一个不回提示符的
     * ROM 会把这条命令钉死在这里，连超时都走不到。
     */
    private fun awaitPrompt(stream: AdbStream) {
        val deadline = System.currentTimeMillis() + SelfAdbPlan.PROMPT_WAIT_MS
        val buffer = ByteArray(READ_CHUNK)
        val seen = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            val ready = try {
                stream.available()
            } catch (t: IOException) {
                return
            }
            if (ready <= 0) {
                Thread.sleep(PROMPT_POLL_MS)
                continue
            }
            val count = stream.read(buffer, 0, minOf(buffer.size, ready))
            if (count <= 0) continue
            seen.append(String(buffer, 0, count, Charsets.UTF_8))
            if (SelfAdbPlan.looksLikePrompt(seen.toString())) return
        }
    }

    /** 读到哨兵出现或到点；PTY 不会自己 EOF，等 EOF 等于等到超时。 */
    private fun readUntilSentinel(stream: AdbStream, out: ByteArrayOutputStream, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArray(READ_CHUNK)
        while (System.currentTimeMillis() < deadline) {
            val ready = try {
                stream.available()
            } catch (t: IOException) {
                return
            }
            if (ready <= 0) {
                Thread.sleep(PROMPT_POLL_MS)
                continue
            }
            val count = stream.read(buffer, 0, minOf(buffer.size, ready))
            if (count <= 0) continue
            out.write(buffer, 0, count)
            if (SelfAdbPlan.hasSentinel(out.toString("UTF-8"))) return
        }
    }

    // ------------------------------------------------------------ 调试

    /**
     * `shdest DEST` —— 调试专用：原样打开一条 destination，把收到的字节打出来。
     *
     * 存在的理由：`sh` 会把命令拼成 `shell:<cmd>\necho __RC=$?`，一旦失败，
     * 「是 destination 形式不对」和「是拼接里的换行不对」两件事就分不开。
     * 这条命令不做任何拼接、不加哨兵，专门用来逐条试形式。
     *
     * 它不是给日常用的：能跑 shell 之后就没有再试的必要，留在命令面上是为了
     * 下一次 adbd 行为变化时不必重新发一版带诊断的 APK。
     */
    fun dest(destination: String): String {
        val ctx = app ?: return SelfAdbPlan.notPaired()
        if (!SelfAdbKeys.hasKey(ctx)) return SelfAdbPlan.notPaired()
        val session = manager
        if (session == null || !session.isConnected()) return SelfAdbPlan.notConnected()
        val timedOut = AtomicBoolean(false)
        var stream: AdbStream? = null
        val out = ByteArrayOutputStream(READ_CHUNK)
        return try {
            stream = session.open(destination)
            val buffer = ByteArray(READ_CHUNK)
            val watchdog = Thread {
                try {
                    Thread.sleep(SelfAdbPlan.SHDEST_TIMEOUT_MS)
                    timedOut.set(true)
                    try {
                        stream.close()
                    } catch (t: Throwable) {
                        // 已经结束
                    }
                } catch (interrupted: InterruptedException) {
                    // 正常结束
                }
            }
            watchdog.isDaemon = true
            watchdog.name = "rc-adb-dest-watchdog"
            watchdog.start()
            try {
                while (true) {
                    val count = try {
                        stream.read(buffer, 0, buffer.size)
                    } catch (t: IOException) {
                        if (timedOut.get()) break
                        throw t
                    }
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
            } finally {
                watchdog.interrupt()
            }
            val bytes = out.toByteArray()
            if (timedOut.get()) {
                SelfAdbPlan.destReportTimeout(destination, bytes, SelfAdbPlan.SHDEST_TIMEOUT_MS)
            } else {
                SelfAdbPlan.destReportOk(destination, bytes)
            }
        } catch (t: Throwable) {
            AgentLog.add("shdest", "fail " + t.javaClass.name + ": " + (t.message ?: "(无 message)"))
            android.util.Log.e(LOGCAT_TAG, "shdest fail dest=" + SelfAdbPlan.showDest(destination), t)
            SelfAdbPlan.destReportFail(destination, t)
        } finally {
            try {
                stream?.close()
            } catch (t: Throwable) {
                // 已经关了
            }
        }
    }

    // ------------------------------------------------------------ 内部

    private fun ensureMaterial(): SelfAdbKeys.Material? {
        val cached = material
        if (cached != null) return cached
        val ctx = app ?: return null
        val loaded = SelfAdbKeys.load(ctx) ?: SelfAdbKeys.generate(ctx) ?: return null
        material = loaded
        return loaded
    }

    private fun note(text: String): String {
        lastNote = text
        return text
    }

    private fun precondition(text: String): SelfAdbPlan.Exit =
        SelfAdbPlan.Exit(null, note(text), precondition = true)

    /** 一次发现的结论：端口，加上被闸丢掉的其它设备广播（给报错用）。 */
    private class Discovery(val port: Int?, val ignored: List<String> = emptyList())

    /**
     * 发现 mDNS 广播的端口。
     *
     * 两级：先让系统 NsdManager 找（快、稳），找不到再自己发裸 mDNS 查询
     * （魅族刚翻无线调试开关时，系统缓存里可能还没有记录，裸查询能抢到）。
     * 两级都空就按 AdbWifi 的经验把 adb_wifi_enabled 关一下再开，
     * 逼 adbd 重发广播，然后重试一轮。
     *
     * 每一级找到的每个候选都要过 [MdnsGate]：mDNS 是局域网广播，同一网段
     * 每台开了无线调试的设备都在播，平板实测拿到的正是**手机**的端口
     * （192.168.0.106:40157），于是 shconnect 稳定超时。闸不过的候选只记进
     * [Discovery.ignored]，绝不当成「差不多就用它」——宁可报没找到，
     * 也不拿别人的端口去握手。
     */
    private fun discoverPort(ctx: Context, serviceType: String, budgetMs: Long): Discovery {
        val rejected = CopyOnWriteArrayList<String>()
        val first = nsdPort(ctx, serviceType, budgetMs, rejected)
        if (first != null) return Discovery(first)
        val raw = RawMdns.discover(ctx, serviceType, budgetMs / 2)
        if (raw.port != null) return Discovery(raw.port)
        for (entry in raw.ignored) {
            if (!rejected.contains(entry)) rejected.add(entry)
        }
        if (!bounce(ctx)) return Discovery(null, rejected.toList())
        return Discovery(nsdPort(ctx, serviceType, budgetMs, rejected), rejected.toList())
    }

    /**
     * NsdManager 这一级：预算内**持续收集**候选，不是第一个就返回。
     *
     * 老实现拿到第一个端口就 latch.countDown()，而回调里的 host 被直接丢掉 ——
     * 这两件事合起来就是平板那个 bug。现在每个候选都过 [MdnsGate.check]，
     * 第一个通过两道闸的才返回，都不通过就继续等到预算耗尽。
     */
    private fun nsdPort(
        ctx: Context,
        serviceType: String,
        budgetMs: Long,
        rejected: MutableList<String>
    ): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return null
        var mdns: AdbMdns? = null
        val latch = CountDownLatch(1)
        val port = AtomicReference<Int>(-1)
        // 一次发现只读一次本机地址：闸里的地址比对全靠它。
        val localIp = AdbWifi.localIpv4(ctx)
        return try {
            mdns = AdbMdns(ctx, serviceType) { host: InetAddress?, found: Int ->
                if (found > 0) {
                    val decision = MdnsGate.check(localIp, host?.hostAddress, found)
                    if (decision.pass) {
                        if (port.compareAndSet(-1, found)) latch.countDown()
                    } else {
                        val why = decision.rejected
                        if (why != null && !rejected.contains(why)) rejected.add(why)
                    }
                }
            }
            mdns.start()
            if (!latch.await(budgetMs, TimeUnit.MILLISECONDS)) return null
            val value = port.get()
            if (value > 0) value else null
        } catch (t: Throwable) {
            null
        } finally {
            try {
                mdns?.stop()
            } catch (t: Throwable) {
                // 没起来
            }
        }
    }

    /** 关一下再开无线调试，逼 adbd 重发广播。没有 WRITE_SECURE_SETTINGS 就放弃。 */
    private fun bounce(ctx: Context): Boolean {
        if (!Admin.canWriteSecure(ctx)) return false
        val off = Admin.setSetting(ctx, SETTINGS_NAMESPACE, ADB_WIFI_KEY, "0")
        if (off.startsWith(Admin.ERROR_PREFIX)) return false
        Thread.sleep(BOUNCE_OFF_MS)
        val on = Admin.setSetting(ctx, SETTINGS_NAMESPACE, ADB_WIFI_KEY, "1")
        return !on.startsWith(Admin.ERROR_PREFIX)
    }

    /**
     * 读到流结束或超时。
     *
     * AdbStream.read() 会一直阻塞在 wait() 上，没有超时参数，所以另起一个
     * 看门狗线程：到点就 close 流（close 会发 CLSE，对端 adbd 会收掉命令），
     * 阻塞的 read 立刻抛 "Stream closed."，调用方按超时处理。
     */
    private fun readAll(stream: AdbStream, timeoutMs: Long, timedOut: AtomicBoolean): String {
        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMs)
                timedOut.set(true)
                try {
                    stream.close()
                } catch (t: Throwable) {
                    // 已经结束
                }
            } catch (interrupted: InterruptedException) {
                // 正常结束，看门狗退出
            }
        }
        watchdog.isDaemon = true
        watchdog.name = "rc-adb-watchdog"
        watchdog.start()
        val out = ByteArrayOutputStream(READ_CHUNK)
        val buffer = ByteArray(READ_CHUNK)
        try {
            while (true) {
                val count = try {
                    stream.read(buffer, 0, buffer.size)
                } catch (t: IOException) {
                    if (timedOut.get()) break
                    throw t
                }
                if (count < 0) break
                out.write(buffer, 0, count)
            }
        } finally {
            watchdog.interrupt()
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** 只在这里实现 libadb 的三个抽象方法，别处不碰连接细节。 */
    private class Manager(private val keys: SelfAdbKeys.Material) : AbsAdbConnectionManager() {

        override fun getPrivateKey(): PrivateKey = keys.privateKey

        override fun getCertificate(): Certificate = keys.certificate

        override fun getDeviceName(): String = SelfAdbPlan.DEVICE_NAME

        fun open(destination: String): AdbStream = openStream(destination)
    }
}
