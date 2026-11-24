package net.de5.phoneagent

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Wireless-debugging self-heal, built on the only signal that actually moves:
 * the mDNS advertisement `_adb-tls-connect._tcp`.
 *
 * Hard-won facts this file is written around:
 *
 * - The port is **not** stored anywhere in `settings`. `adb_wifi_port` does not
 *   exist (measured: null), and the real port changes on every reboot
 *   (43133 -> 38181 in one afternoon), so it can only be discovered, never
 *   remembered.
 * - The pairing survives reboots, so `adb connect host:port` is enough - no
 *   re-pair needed. That is the whole reason this command exists.
 * - An app resolving the broadcast of its own device is a known weak spot:
 *   several ROMs filter a device's own advertisements. A miss is therefore
 *   reported as a miss, never smoothed over with a guessed port.
 * - The local IP must be read from the live network, not cached: this tablet
 *   moves between networks (and cities), so the address in the report has to be
 *   the one that is true right now.
 */
internal object AdbWifi {

    /** `global` key that turns wireless debugging on; a plain 0/1. */
    const val ENABLED_KEY = "adb_wifi_enabled"

    /** Whole `adbheal` budget: bounce the toggle, wait for the port, report. */
    const val HEAL_BUDGET_MS = 20_000L

    /** One NsdManager round. A fresh round is the only way to re-resolve. */
    private const val DISCOVER_ROUND_MS = 4_000L

    private const val MIN_ROUND_MS = 900L

    /**
     * Budget held back for the raw-query tier.
     *
     * Reserving it up front is what makes the fallback dependable: if NsdManager
     * were allowed to eat the whole budget there would be no time left to send
     * our own query, and the whole point is that the raw tier still runs when
     * NsdManager fails.
     */
    private const val RAW_RESERVE_MS = 2_500L

    /** The shortest raw-query tier worth attempting at all. */
    private const val MIN_RAW_MS = 2_000L

    /**
     * `adbheal` writes these and waits after the first one.
     *
     * 0 then 1, unconditionally: on the test tablet the advertisement is only
     * resolvable in the seconds right after the wireless-debugging daemon
     * starts, so an already-on toggle has to be restarted to become visible to
     * this device again. It drops any live adb session - the documented,
     * accepted price of running a self-heal command.
     */
    private val BOUNCE_SEQUENCE = listOf("0", "1")

    /** Time for the daemon to actually stop before switching it back on. */
    private const val BOUNCE_OFF_MS = 1_500L

    // ------------------------------------------------------------ port cache

    /** Last discovered port and when, for the heartbeat `env` field only. */
    @Volatile
    private var cachedPort: Int = 0

    @Volatile
    private var cachedAtMillis: Long = 0L

    /** `adbwifi=<port>` while the cache is fresh, otherwise null (never a guess). */
    fun cachedEnvField(nowMillis: Long = System.currentTimeMillis()): String? =
        AdbReport.envField(cachedPort, cachedAtMillis, nowMillis)

    /** Test seam + the value the report shows. */
    fun cachedPort(): Int = if (AdbReport.isFresh(cachedAtMillis, System.currentTimeMillis())) cachedPort else 0

    /** When the cache was last written (0 = never); the heartbeat's due check. */
    fun cachedAtMillis(): Long = cachedAtMillis

    private fun remember(port: Int) {
        if (port <= 0 || port > 65535) return
        cachedPort = port
        cachedAtMillis = System.currentTimeMillis()
    }

    // ------------------------------------------------- unattended self-heal

    /**
     * Post-boot heal: turn wireless debugging on **only if it is not already on**,
     * then discover and cache the port.
     *
     * Unlike [report] with `heal = true` this never bounces the toggle. Boot has
     * no live adb session to preserve, but it also has no reason to destroy one:
     * the daemon was just started by the system, so if the setting already reads
     * `1` the port is discoverable as-is.
     *
     * @return one short line for [AgentLog]; an [Admin.ERROR_PREFIX] line when
     *   the settings write was refused.
     */
    fun refresh(context: Context, enableIfNeeded: Boolean, budgetMs: Long): String {
        var enabled = readEnabled(context)
        if (enableIfNeeded && AdbReport.bootShouldEnable(enabled)) {
            val write = Admin.setSetting(context, "global", ENABLED_KEY, "1")
            if (write.startsWith(Admin.ERROR_PREFIX)) return write
            enabled = readEnabled(context)
        }
        val discovered = discoverTwoTier(context, budgetMs)
        val port = discovered.port ?: return "未发现端口（" + (discovered.note ?: "无应答") + "）"
        remember(port)
        return "端口 " + port + "（来源 " + (discovered.source ?: "未知") + "）"
    }

    /**
     * Heartbeat observation: **read-only**, never writes a setting.
     *
     * This exists so `env` keeps carrying a usable `adbwifi=` port without the
     * operator running a command. It must never bounce the toggle - the heartbeat
     * runs every 15 s, and a bounce there would cut every adb session on the
     * phone once a minute.
     *
     * @return one short line for [AgentLog].
     */
    fun observe(context: Context, budgetMs: Long): String {
        val discovered = discoverTwoTier(context, budgetMs)
        val port = discovered.port ?: return "未发现端口（" + (discovered.note ?: "无应答") + "）"
        remember(port)
        return "端口 " + port + "（来源 " + (discovered.source ?: "未知") + "）"
    }

    // ---------------------------------------------------------------- report

    /**
     * @param heal `adbheal` semantics: bounce `adb_wifi_enabled` 0 -> 1 through
     *   [Admin.setSetting] (so its permission error text is reused verbatim),
     *   then discover. Bouncing is unconditional - see [BOUNCE_SEQUENCE].
     * @return the report text, or a string starting with [Admin.ERROR_PREFIX]
     *   when the write itself was refused.
     */
    fun report(context: Context, heal: Boolean, budgetMs: Long): String {
        var enabled = readEnabled(context)

        if (heal) {
            for (value in BOUNCE_SEQUENCE) {
                val write = Admin.setSetting(context, "global", ENABLED_KEY, value)
                if (write.startsWith(Admin.ERROR_PREFIX)) return write
                if (value == "0") sleepQuietly(BOUNCE_OFF_MS)
            }
            enabled = readEnabled(context)
        }

        val ip = localIpv4(context)
        val now = System.currentTimeMillis()
        val age = AdbReport.cacheAgeSeconds(cachedAtMillis, now)

        // `adbheal` always re-learns: it just restarted the daemon, so a cached
        // port is about to be wrong. `adbinfo` prefers a fresh cache, because a
        // port only moves when the daemon restarts.
        if (!heal && AdbReport.isFresh(cachedAtMillis, now)) {
            return render(enabled, ip, cachedPort, AdbReport.cacheSource(age ?: 0L), null)
        }

        // Querying with the daemon off can only ever miss; say why instead of
        // spending the whole budget to prove it.
        if (!heal && enabled != "1") {
            return render(
                enabled,
                ip,
                null,
                null,
                "无线调试未开启（adb_wifi_enabled=" + (enabled ?: "?") + "）；跑 adbheal 打开并发现端口"
            )
        }

        val discovered = discoverTwoTier(context, budgetMs)
        if (discovered.port != null) {
            remember(discovered.port)
            return render(enabled, ip, discovered.port, discovered.source, null)
        }

        val note = if (!heal && age != null) {
            discovered.note + "；" + AdbReport.staleHint(age)
        } else {
            discovered.note
        }
        return render(enabled, ip, null, null, note)
    }

    /** One report line-set; a discovered-but-unreachable port is called out. */
    private fun render(
        enabled: String?,
        ip: String?,
        port: Int?,
        source: String?,
        note: String?
    ): String {
        val probe = if (port == null) null else probeTcp(ip, port, AdbReport.TCP_PROBE_TIMEOUT_MS)
        val finalNote = when {
            note != null -> note
            port != null && probe == false ->
                "端口已发现但 TCP 不可连（可能被防火墙拦，或守护进程刚重启）"
            else -> null
        }
        return AdbReport.format(
            AdbWifiInfo(
                enabledValue = enabled,
                localIp = ip,
                port = port,
                source = source,
                note = finalNote,
                tcpOk = probe
            )
        )
    }

    /** Daemon restart pause; interrupting it only shortens the wait, never breaks it. */
    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (t: Throwable) {
            Thread.currentThread().interrupt()
        }
    }

    /** Raw `adb_wifi_enabled` string; reading needs no permission at all. */
    fun readEnabled(context: Context): String? = try {
        Settings.Global.getString(context.applicationContext.contentResolver, ENABLED_KEY)
    } catch (t: Throwable) {
        null
    }

    // ------------------------------------------------------------------- IP

    /**
     * The current wlan IPv4 address, or null.
     *
     * LinkProperties is asked first because it is the address the system is
     * actually routing with; the interface walk is the fallback for ROMs where
     * the active network is not reported to a background app.
     */
    fun localIpv4(context: Context): String? = try {
        fromLinkProperties(context) ?: fromInterfaces()
    } catch (t: Throwable) {
        null
    }

    private fun fromLinkProperties(context: Context): String? = try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = manager?.activeNetwork
        val props = if (manager == null || network == null) null else manager.getLinkProperties(network)
        var hit: String? = null
        val addresses = props?.linkAddresses ?: emptyList()
        for (address in addresses) {
            val raw = address.address
            if (raw is Inet4Address && usable(raw)) {
                hit = raw.hostAddress
                break
            }
        }
        hit
    } catch (t: Throwable) {
        null
    }

    private fun fromInterfaces(): String? = try {
        var hit: String? = null
        val all: List<NetworkInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (nic in all) {
            if (nic.isLoopback || !nic.isUp) continue
            val name = nic.name ?: ""
            // wlan0 on most devices; a few ROMs use wlan1 / swlan0.
            if (!name.startsWith("wlan") && !name.startsWith("swlan")) continue
            for (address in Collections.list(nic.inetAddresses)) {
                if (address is Inet4Address && usable(address)) {
                    hit = address.hostAddress
                    break
                }
            }
            if (hit != null) break
        }
        hit
    } catch (t: Throwable) {
        null
    }

    /** Reject loopback and self-assigned 169.254.x.x, which no peer can reach. */
    private fun usable(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress) return false
        return !address.isLinkLocalAddress
    }

    // ------------------------------------------------------------- discovery

    /** One discovery outcome; [port] null means "not found", never 0-as-unknown. */
    internal class Found(val port: Int?, val source: String?, val note: String?)

    /**
     * Two tiers, in order:
     *
     * 1. NsdManager - the system resolver, kept because when it works it is the
     *    cheapest and it already validates the advertisement;
     * 2. a raw mDNS query we send ourselves ([RawMdns]) - because on the test
     *    tablet tier 1 goes silent for minutes at a time while the advertisement
     *    is demonstrably still on the wire (the PC resolved it on every try).
     *
     * Tier 2 runs unless tier 1 already answered. Both failing is reported as
     * both failing, with each tier's own reason.
     */
    fun discoverTwoTier(context: Context, budgetMs: Long): Found {
        val start = System.currentTimeMillis()
        val nsdBudget = if (budgetMs > RAW_RESERVE_MS) budgetMs - RAW_RESERVE_MS else budgetMs
        val nsd = discoverWithNsd(context, nsdBudget)
        if (nsd.port != null) return Found(nsd.port, AdbReport.SOURCE_NSD, null)

        val remaining = budgetMs - (System.currentTimeMillis() - start)
        // Even a small remaining budget is worth one raw query: it costs a
        // single datagram, and a missing answer is still a usable datum.
        val rawBudget = if (remaining < MIN_ROUND_MS) MIN_RAW_MS else remaining
        val raw = RawMdns.discover(context.applicationContext, AdbReport.SERVICE_TYPE, rawBudget)
        if (raw.port != null) return Found(raw.port, AdbReport.SOURCE_RAW, null)

        return Found(null, null, AdbReport.twoTierNote(nsd.note, raw.note))
    }

    /**
     * Poll NsdManager until [budgetMs] runs out. Rounds are needed because
     * NsdManager refuses to resolve the same service twice without
     * re-discovering it, and because \`adbheal\` may be racing the daemon up.
     */
    fun discoverWithNsd(context: Context, budgetMs: Long): Found {
        val deadline = System.currentTimeMillis() + budgetMs
        var lastNote: String? = null
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining < MIN_ROUND_MS) break
            val round = if (remaining < DISCOVER_ROUND_MS) remaining else DISCOVER_ROUND_MS
            val outcome = AdbDiscovery(context.applicationContext, round).run()
            if (outcome.port != null) return Found(outcome.port, AdbReport.SOURCE_NSD, null)
            lastNote = outcome.note ?: lastNote
        }
        return Found(null, null, lastNote ?: NO_BROADCAST)
    }

    private const val NO_BROADCAST =
        "未发现 _adb-tls-connect 广播（本机自解析可能被 ROM 过滤）"

    /** 200 ms TCP handshake probe; false on any failure, never throws. */
    fun probeTcp(host: String?, port: Int, timeoutMs: Int): Boolean {
        if (host == null || port <= 0) return false
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        } catch (t: Throwable) {
            false
        } finally {
            try {
                socket.close()
            } catch (ignored: Throwable) {
                // nothing to do; the probe result is already known
            }
        }
    }

    /**
     * One NsdManager round on its own looper thread.
     *
     * NsdManager insists on a Looper for its callbacks and the executor runs on
     * the poll thread, which has none - and blocking the main thread for a
     * multi-second discovery is out of the question. So each round gets a
     * private HandlerThread; the caller only waits on a latch.
     */
    private class AdbDiscovery(private val ctx: Context, private val budgetMs: Long) {

        private val done = CountDownLatch(1)
        private val hit = AtomicReference<Pair<String, Int>?>(null)
        private val note = AtomicReference<String?>(null)
        private val thread = HandlerThread("adbdisc")
        private var lock: WifiManager.MulticastLock? = null
        private var manager: NsdManager? = null
        private var listener: NsdManager.DiscoveryListener? = null

        fun run(): Found {
            thread.start()
            val handler = Handler(thread.looper)
            handler.post { start(ctx) }
            val finished = done.await(budgetMs, TimeUnit.MILLISECONDS)
            handler.post { stop() }
            thread.quitSafely()

            val found = hit.get()
            if (found != null && found.second > 0) return Found(found.second, AdbReport.SOURCE_NSD, null)
            val why = note.get()
            if (why != null) return Found(null, null, why)
            return Found(
                null,
                null,
                if (finished) {
                    NO_BROADCAST
                } else {
                    "在 " + budgetMs + "ms 内没有解析到 " + AdbReport.SERVICE_TYPE + " 广播（本机自解析可能被 ROM 过滤）"
                }
            )
        }

        private fun start(ctx: Context) {
            try {
                // Without a multicast lock the mDNS packet never reaches us on
                // many ROMs; it is a normal permission, no grant needed.
                val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val multicast = wifi?.createMulticastLock("phone-agent-adb")
                multicast?.setReferenceCounted(false)
                multicast?.acquire()
                lock = multicast

                val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager
                if (nsd == null) {
                    note.set("系统没有 NsdManager，无法解析 mDNS")
                    done.countDown()
                    return
                }
                manager = nsd
                val discovery = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) {
                        // nothing to do; resolution happens per service
                    }

                    override fun onServiceFound(info: NsdServiceInfo) {
                        if (info.serviceType?.contains("_adb-tls-connect") != true) return
                        resolve(nsd, info)
                    }

                    override fun onServiceLost(info: NsdServiceInfo) {
                        // keep whatever we already resolved
                    }

                    override fun onDiscoveryStopped(serviceType: String) {
                        // the latch is released by stop() or by a resolution
                    }

                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        note.set("mDNS 发现启动失败（错误码 " + errorCode + "）")
                        done.countDown()
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                        // stopping is best-effort
                    }
                }
                listener = discovery
                nsd.discoverServices(AdbReport.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            } catch (t: Throwable) {
                note.set("mDNS 发现异常: " + describe(t))
                done.countDown()
            }
        }

        private fun resolve(nsd: NsdManager, info: NsdServiceInfo) {
            try {
                val resolver = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        note.set("解析 _adb-tls-connect 失败（错误码 " + errorCode + "）")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val port = serviceInfo.port
                        val host = serviceInfo.host?.hostAddress
                        if (port in 1..65535) {
                            hit.compareAndSet(null, Pair(host ?: "", port))
                            done.countDown()
                        } else {
                            note.set("广播里没有可用端口（port=" + port + "）")
                        }
                    }
                }
                nsd.resolveService(info, resolver)
            } catch (t: Throwable) {
                note.set("解析异常: " + describe(t))
            }
        }

        private fun stop() {
            try {
                val nsd = manager
                val discovery = listener
                if (nsd != null && discovery != null) nsd.stopServiceDiscovery(discovery)
            } catch (t: Throwable) {
                // already stopped / never started
            }
            try {
                lock?.release()
            } catch (t: Throwable) {
                // lock may never have been acquired
            }
            lock = null
            manager = null
            listener = null
        }
    }
}
