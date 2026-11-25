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

    @Volatile
    private var app: Context? = null

    @Volatile
    private var manager: Manager? = null

    @Volatile
    private var material: SelfAdbKeys.Material? = null

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

    fun status(): String {
        val ctx = app
        val keyed = ctx != null && SelfAdbKeys.hasKey(ctx)
        val fingerprint = if (keyed) {
            val loaded = ensureMaterial()
            if (loaded != null) SelfAdbKeys.fingerprint(loaded.publicKey) else null
        } else {
            null
        }
        return SelfAdbPlan.status(keyed, fingerprint, isConnected(), lastNote)
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
        val port = discoverPort(ctx, SelfAdbPlan.SERVICE_PAIRING, SelfAdbPlan.PAIR_BUDGET_MS)
            ?: return note(SelfAdbPlan.noPort(SelfAdbPlan.SERVICE_PAIRING))
        return try {
            val session = Manager(keys)
            session.pair(SelfAdbPlan.HOST, port, code)
            lastNote = null
            SelfAdbPlan.pairOk()
        } catch (t: Throwable) {
            note(SelfAdbPlan.pairFailed(SelfAdbPlan.errorText(t)))
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
        val port = discoverPort(ctx, SelfAdbPlan.SERVICE_CONNECT, SelfAdbPlan.CONNECT_BUDGET_MS)
            ?: return note(SelfAdbPlan.noPort(SelfAdbPlan.SERVICE_CONNECT))
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
     * exit code 靠哨兵法：命令后面追加 `echo __RC=$?`，取最后一行。
     * 不这么做就只能拿到输出文本——adb 的 shell 协议本身不回退出码。
     */
    fun shell(command: String, timeoutMs: Long): SelfAdbPlan.Exit {
        val ctx = app ?: return precondition(SelfAdbPlan.notPaired())
        if (!SelfAdbKeys.hasKey(ctx)) return precondition(SelfAdbPlan.notPaired())
        val session = manager
        if (session == null || !session.isConnected()) {
            return precondition(SelfAdbPlan.notConnected())
        }
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

    /**
     * 发现 mDNS 广播的端口。
     *
     * 两级：先让系统 NsdManager 找（快、稳），找不到再自己发裸 mDNS 查询
     * （魅族刚翻无线调试开关时，系统缓存里可能还没有记录，裸查询能抢到）。
     * 两级都空就按 AdbWifi 的经验把 adb_wifi_enabled 关一下再开，
     * 逼 adbd 重发广播，然后重试一轮。
     */
    private fun discoverPort(ctx: Context, serviceType: String, budgetMs: Long): Int? {
        val first = nsdPort(ctx, serviceType, budgetMs)
        if (first != null) return first
        val raw = RawMdns.discover(ctx, serviceType, budgetMs / 2)
        if (raw.port != null) return raw.port
        if (!bounce(ctx)) return null
        return nsdPort(ctx, serviceType, budgetMs)
    }

    private fun nsdPort(ctx: Context, serviceType: String, budgetMs: Long): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return null
        var mdns: AdbMdns? = null
        val latch = CountDownLatch(1)
        val port = AtomicReference<Int>(-1)
        return try {
            mdns = AdbMdns(ctx, serviceType) { host: InetAddress?, found: Int ->
                if (found > 0) {
                    port.set(found)
                    latch.countDown()
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
