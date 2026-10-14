package net.de5.phoneagent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Self-diagnosis for the "SocketException: Connection reset" class of failures.
 *
 * The phone is unreachable by adb and its logcat is not readable from the outside,
 * so the only channel out of the device is text the operator copies to the
 * clipboard. A plain log dump would not be enough: the relay path can break at
 * several different layers and each one needs a different fix, so this probe
 * walks the layers in order and reports which one fails.
 *
 *  1. environment — proxy properties, VPN, active transports, local addresses
 *  2. DNS         — which address the phone actually resolves, per family
 *  3. TCP         — plain connect to every resolved address on the relay port
 *  4. TLS         — full handshake to every resolved address *pinned by address*,
 *                   plus a with-SNI / without-SNI A/B by hostname. Pinning tells
 *                   an IPv6-only breakage apart from a TLS-layer one; the A/B
 *                   tells a stripped SNI apart from a reset.
 *  5. HTTPS       — the same POST the agent makes, through HttpURLConnection
 *  6. raw HTTP    — the same POST written by hand over the TLS socket, to tell
 *                   an HTTP-layer rejection apart from a framework/TLS one
 *  7. control     — the same handshake against unrelated hosts. If the controls
 *                   succeed while the relay fails, the fault is on the relay's
 *                   path and not in this device's TLS stack.
 *  8. TLS variants— the relay handshake with TLS1.3-only, TLS1.2-only and an
 *                   explicit ALPN, because some middleboxes reset a handshake
 *                   they dislike the shape of rather than the destination.
 *  9. cleartext   — plain HTTP on port 80 and against the old direct endpoint:
 *                   an HTTP answer proves TCP survives and only TLS is killed.
 * 10. IP x SNI    — our address handshaked with our name, with a different name
 *                   and with no name at all: the control that tells a blocked
 *                   *destination* apart from a blocked *name*. The pinned-address
 *                   probes in 4 always carry our own SNI, so on their own they
 *                   cannot make that distinction.
 * 11. ECH         — whether the relay name publishes an Encrypted Client Hello
 *                   policy over DNS. The one observable difference found so far
 *                   between the name that resets and the names that work: Chrome
 *                   speaks ECH and hides the name, Java/Conscrypt cannot.
 *
 * Every probe catches its own failures; this never throws.
 */
internal object Diagnostics {

    /**
     * Probes target whatever relay the operator typed in, never a hard-coded
     * host: a build that still pointed at a retired endpoint would report the
     * old route's failure and send us chasing a fault that no longer exists.
     */
    private class Target(val host: String, val port: Int, val path: String)

    private fun targetOf(prefs: Prefs): Target? {
        val raw = prefs.relayBase.trim()
        if (raw.isEmpty()) return null
        return try {
            val url = java.net.URL(raw)
            val host = url.host ?: return null
            val port = if (url.port > 0) url.port else if (url.protocol == "https") 443 else 80
            val path = url.path.orEmpty().trimEnd('/')
            Target(host, port, path)
        } catch (t: Throwable) {
            null
        }
    }

    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 8_000

    fun run(context: Context, prefs: Prefs): String {
        val sb = StringBuilder()
        sb.append("=== Phone Agent 诊断 ").append(nowText()).append(" ===\n")
        sb.append("app=").append(BuildConfig.VERSION_NAME)
            .append("  android=").append(Build.VERSION.RELEASE)
            .append("(").append(Build.VERSION.SDK_INT).append(")")
            .append("  device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append('\n')
        sb.append("relay=").append(prefs.relayBase)
            .append("  id=").append(prefs.agentId)
            .append("  secret=").append(mask(prefs.secret))
            .append('\n')
        sb.append("acc=").append(PhoneAccessibilityService.instance != null)
            .append("  running=").append(RcAgentService.running)
            .append('\n')

        probe("1 环境", sb) { environment(context, sb) }

        val target = targetOf(prefs)
        if (target == null) {
            sb.append("\n--- 探针 2-11 跳过 ---\n")
            sb.append("  relay 地址无法解析为 host:port: ").append(prefs.relayBase).append('\n')
        } else {
            sb.append("  探测目标: ").append(target.host).append(':').append(target.port)
                .append("  路径前缀=").append(target.path.ifEmpty { "/" }).append('\n')
            probe("2 DNS", sb) { dns(sb, target) }
            probe("3 TCP", sb) { tcp(sb, target) }
            probe("4 TLS", sb) { tls(sb, target) }
            probe("5 HTTPS", sb) { https(sb, target) }
            probe("6 原始HTTP", sb) { rawHttp(sb, target) }
            probe("7 对照主机", sb) { controls(sb) }
            probe("8 TLS 变体", sb) { tlsVariants(sb, target) }
            probe("9 明文 HTTP", sb) { cleartext(sb, target) }
            probe("10 IP×SNI 对照", sb) { ipSniMatrix(sb, target) }
            probe("11 ECH 配置", sb) { echLookup(sb, target) }
        }

        sb.append("\n--- 服务状态 ---\n").append(RcAgentService.snapshot()).append('\n')
        sb.append("\n--- 运行日志 (最近 ").append(AgentLog.size()).append(" 行) ---\n")
        sb.append(AgentLog.dump())
        return sb.toString()
    }

    /** Cheap report without any network traffic: used for the automatic copy. */
    fun stateOnly(prefs: Prefs): String {
        val sb = StringBuilder()
        sb.append("=== Phone Agent 状态 ").append(nowText()).append(" ===\n")
        sb.append("app=").append(BuildConfig.VERSION_NAME)
            .append("  android=").append(Build.VERSION.RELEASE)
            .append("(").append(Build.VERSION.SDK_INT).append(")")
            .append("  device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append('\n')
        sb.append("relay=").append(prefs.relayBase)
            .append("  id=").append(prefs.agentId)
            .append("  secret=").append(mask(prefs.secret))
            .append("  loop=").append(prefs.locationInterval).append("s")
            .append('\n')
        sb.append("acc=").append(PhoneAccessibilityService.instance != null)
            .append("  running=").append(RcAgentService.running)
            .append('\n')
        sb.append("\n--- 服务状态 ---\n").append(RcAgentService.snapshot()).append('\n')
        sb.append("\n--- 运行日志 ---\n").append(AgentLog.dump())
        return sb.toString()
    }

    private inline fun probe(name: String, sb: StringBuilder, body: () -> Unit) {
        sb.append('\n').append("--- 探针 ").append(name).append(" ---\n")
        try {
            body()
        } catch (t: Throwable) {
            sb.append("  探针异常: ").append(t.javaClass.name)
                .append(": ").append(t.message ?: "").append('\n')
        }
    }

    private fun environment(context: Context, sb: StringBuilder) {
        sb.append("  proxyHost=").append(System.getProperty("http.proxyHost") ?: "-")
            .append("  proxyPort=").append(System.getProperty("http.proxyPort") ?: "-")
            .append('\n')
        sb.append("  https.proxyHost=").append(System.getProperty("https.proxyHost") ?: "-")
            .append("  https.proxyPort=").append(System.getProperty("https.proxyPort") ?: "-")
            .append('\n')
        sb.append("  socksProxyHost=").append(System.getProperty("socksProxyHost") ?: "-")
            .append('\n')

        val provider = java.security.Security.getProviders().firstOrNull { it.name.contains("Conscrypt") }
        sb.append("  TLS 提供方=").append(provider?.name ?: "(系统默认)")
            .append("  ").append(provider?.version ?: "").append('\n')

        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            sb.append("  ConnectivityManager 不可用\n")
            localAddresses(sb)
            return
        }
        val network = cm.activeNetwork
        if (network == null) {
            sb.append("  没有活动网络\n")
            localAddresses(sb)
            return
        }
        val caps = cm.getNetworkCapabilities(network)
        if (caps == null) {
            sb.append("  无法读取网络能力\n")
            localAddresses(sb)
            return
        }
        sb.append("  传输方式: wifi=").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            .append(" cellular=").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
            .append(" ethernet=").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            .append('\n')
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        sb.append("  VPN=").append(vpn)
        if (vpn) {
            sb.append("  <<< 命中 VPN：TLS/SNI 很可能被中间人改写或剥离")
        }
        sb.append('\n')
        sb.append("  默认代理=").append(cm.defaultProxy?.toString() ?: "无").append('\n')
        sb.append("  VALIDATED=").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            .append("  INTERNET=").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            .append('\n')
        localAddresses(sb)
    }

    /**
     * The addresses this device actually holds. An IPv6 address here plus a
     * failing IPv6 handshake is a very different fault from a TLS-layer reset,
     * and only the address list makes that visible from a clipboard report.
     */
    private fun localAddresses(sb: StringBuilder) {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return
            var any = false
            for (nif in java.util.Collections.list(interfaces)) {
                if (!nif.isUp) continue
                for (address in java.util.Collections.list(nif.inetAddresses)) {
                    if (address.isLoopbackAddress) continue
                    sb.append("  本机地址 ").append(nif.name).append(' ')
                        .append(family(address)).append(' ').append(address.hostAddress).append('\n')
                    any = true
                }
            }
            if (!any) sb.append("  本机没有非回环地址\n")
        } catch (t: Throwable) {
            sb.append("  读取网卡失败: ").append(describe(t)).append('\n')
        }
    }

    /** Returns the addresses DNS hands the app, which may not be the public ones. */
    private fun dns(sb: StringBuilder, target: Target): List<InetAddress> {
        val addresses = try {
            InetAddress.getAllByName(target.host).toList()
        } catch (t: UnknownHostException) {
            sb.append("  解析失败: ").append(t.message ?: t.javaClass.simpleName).append('\n')
            return emptyList()
        }
        if (addresses.isEmpty()) {
            sb.append("  解析结果为空\n")
            return addresses
        }
        for (address in addresses) {
            sb.append("  ").append(family(address)).append(' ').append(address.hostAddress).append('\n')
        }
        val expected = expectedAddressFor(target.host)
        if (expected != null && addresses.none { it.hostAddress == expected }) {
            sb.append("  <<< 期望 ").append(expected)
                .append("，实际没有这个地址 → DNS 被改写或走了代理")
        }
        return addresses
    }

    private fun tcp(sb: StringBuilder, target: Target) {
        // Test the hard-coded address as well: if DNS hands back something else
        // and that something refuses connections, the two results together say
        // "DNS is the problem" rather than "the server is down".
        val expected = expectedAddressFor(target.host)
        val targets = resolveQuietly(target.host) + listOfNotNull(expected?.let { InetAddress.getByName(it) })

        for (address in targets.distinctBy { it.hostAddress }) {
            val socket = Socket()
            val started = System.currentTimeMillis()
            try {
                socket.connect(InetSocketAddress(address, target.port), CONNECT_TIMEOUT_MS)
                sb.append("  ").append(family(address)).append(' ').append(address.hostAddress)
                    .append(':').append(target.port)
                    .append(" 连接成功 ").append(System.currentTimeMillis() - started).append("ms\n")
            } catch (t: Throwable) {
                sb.append("  ").append(family(address)).append(' ').append(address.hostAddress)
                    .append(':').append(target.port)
                    .append(" 连接失败 ").append(System.currentTimeMillis() - started).append("ms ")
                    .append(describe(t)).append('\n')
            } finally {
                closeQuietly(socket)
            }
        }
    }

    /**
     * The decisive probe. Each resolved address is tried *pinned*, so a broken
     * IPv6 path cannot hide behind a working IPv4 one (or the other way round),
     * and the hostname path is tried twice as a controlled A/B on SNI.
     *
     * The default trust manager cannot be used here — a middlebox or a fallback
     * server presents a certificate that does not validate, and "handshake
     * failed" is exactly the ambiguity we are trying to remove. A permissive
     * manager is installed for this probe **only** so the peer certificate can
     * be read and judged; no data is ever sent over these sockets.
     */
    private fun tls(sb: StringBuilder, target: Target) {
        val addresses = resolveQuietly(target.host)
        if (addresses.isEmpty()) {
            sb.append("  域名解析不到地址，跳过固定地址测试\n")
        }
        for (address in addresses) {
            tlsProbe(
                sb, "固定 ${family(address)} ${address.hostAddress} 带 SNI",
                target.host, target.port, address, withSni = true, protocols = null, alpn = null
            )
        }
        tlsProbe(
            sb, "按域名 带 SNI（正常情况）",
            target.host, target.port, null, withSni = true, protocols = null, alpn = null
        )
        tlsProbe(
            sb, "按域名 不带 SNI（对照）",
            target.host, target.port, null, withSni = false, protocols = null, alpn = null
        )
    }

    /**
     * Same handshake against unrelated hosts. Controls are the whole point: if
     * they succeed while the relay host fails, this device's TLS stack is fine
     * and something on the relay's path is resetting us. If the controls fail
     * too, the fault is local (device, ROM, security app, network).
     */
    private fun controls(sb: StringBuilder) {
        sb.append("  （与目标无关的对照；对照能过而 relay 不过 ⇒ 问题在 relay 这条链路上，不在本机 TLS 栈）\n")
        for (control in CONTROLS) {
            tlsProbe(
                sb, "${control.first}:${control.second}",
                control.first, control.second, null, withSni = true, protocols = null, alpn = null
            )
        }
    }

    /**
     * The same destination with a different handshake shape. Some middleboxes
     * reset a flow they do not like the look of rather than one they cannot
     * reach, and the only way to see that from a clipboard report is to vary
     * the shape and watch which variants survive.
     */
    private fun tlsVariants(sb: StringBuilder, target: Target) {
        tlsProbe(
            sb, "仅 TLSv1.3", target.host, target.port, null,
            withSni = true, protocols = arrayOf("TLSv1.3"), alpn = null
        )
        tlsProbe(
            sb, "仅 TLSv1.2", target.host, target.port, null,
            withSni = true, protocols = arrayOf("TLSv1.2"), alpn = null
        )
        tlsProbe(
            sb, "仅 TLSv1.2 + ALPN http/1.1", target.host, target.port, null,
            withSni = true, protocols = arrayOf("TLSv1.2"), alpn = arrayOf("http/1.1")
        )
        tlsProbe(
            sb, "默认版本 + ALPN http/1.1", target.host, target.port, null,
            withSni = true, protocols = null, alpn = arrayOf("http/1.1")
        )
        // The one that matters most: a browser always offers h2 first, and some
        // filters reset a ClientHello that does not. Handshake only — nothing is
        // sent over the socket afterwards, so h2 needs no HTTP/2 implementation.
        tlsProbe(
            sb, "浏览器式 ALPN h2,http/1.1", target.host, target.port, null,
            withSni = true, protocols = null, alpn = arrayOf("h2", "http/1.1")
        )
    }

    /**
     * The control that separates "this IP is blocked" from "the name in this
     * handshake is blocked": the *same* address and port, handshaked three
     * times with three different SNI values.
     *
     *  - our name succeeds  → nothing is wrong at all any more
     *  - our name fails, another name succeeds → the address is reachable and
     *    the reset is aimed at the name we ask for, not at the destination
     *  - every name fails → the address (or the path to it) is blocked, and a
     *    new domain would not have helped
     *
     * Existing probes already pin the addresses, but always with our own SNI,
     * so on their own they cannot tell those three cases apart.
     */
    private fun ipSniMatrix(sb: StringBuilder, target: Target) {
        sb.append("  （同一地址换 SNI：域名被针对 vs 地址被拦）\n")
        val pairs = mutableListOf<Pair<InetAddress, Int>>()
        for (address in resolveQuietly(target.host)) pairs.add(address to target.port)
        // Always include our own fixed address as well: whichever route the app
        // is configured for, both are worth answering for.
        try {
            pairs.add(InetAddress.getByName(RELAY_DIRECT_IP) to RELAY_DIRECT_PORT)
        } catch (t: Throwable) {
            // ignore
        }
        if (pairs.isEmpty()) {
            sb.append("  没有任何可测地址，跳过\n")
            return
        }
        for ((address, port) in pairs.distinctBy { (it.first.hostAddress ?: "") + ':' + it.second }) {
            tlsProbe(
                sb, "${address.hostAddress}:$port + SNI " + target.host,
                target.host, port, address, withSni = true, protocols = null, alpn = null
            )
            tlsProbe(
                sb, "${address.hostAddress}:$port + SNI " + CONTROL_SNI_HOST,
                CONTROL_SNI_HOST, port, address, withSni = true, protocols = null, alpn = null
            )
            tlsProbe(
                sb, "${address.hostAddress}:$port + 不带 SNI",
                target.host, port, address, withSni = false, protocols = null, alpn = null
            )
        }
    }

    /**
     * Plain HTTP, no TLS at all. An HTTP answer — even a redirect or a 400 —
     * proves the path carries bytes and only the TLS handshake is being killed,
     * which rules out DNS, routing and port blocking in one shot.
     */
    private fun cleartext(sb: StringBuilder, target: Target) {
        plainHttp(sb, target.host, 80)
        plainHttp(sb, RELAY_DIRECT_HOST, RELAY_DIRECT_PORT)
    }

    private fun plainHttp(sb: StringBuilder, host: String, port: Int) {
        val started = System.currentTimeMillis()
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val connectedAt = System.currentTimeMillis() - started
            raw.soTimeout = READ_TIMEOUT_MS
            val body = """{"id":"PROBE-PLAIN"}"""
            val payload = body.toByteArray(Charsets.UTF_8)
            val request = buildString {
                append("POST /agent/hello HTTP/1.1\r\n")
                append("Host: ").append(host).append(':').append(port).append("\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ").append(payload.size).append("\r\n")
                append("Connection: close\r\n\r\n")
                append(body)
            }
            // Deliberately not closing (and not using) the output stream: closing
            // a socket's output stream closes the socket itself, and the read
            // below would then report "Socket is closed" — the defect that made
            // this probe useless in 0.4.0 — instead of the HTTP answer.
            val out = raw.getOutputStream()
            out.write(request.toByteArray(Charsets.UTF_8))
            out.flush()
            val text = readAll(raw.inputStream)
            val firstLine = text.lineSequence().firstOrNull() ?: "(无响应)"
            sb.append("  http://").append(host).append(':').append(port)
                .append("  连接=").append(connectedAt).append("ms  响应首行: ")
                .append(firstLine).append('\n')
        } catch (t: Throwable) {
            sb.append("  http://").append(host).append(':').append(port)
                .append("  失败 ").append(System.currentTimeMillis() - started).append("ms ")
                .append(describe(t)).append('\n')
        } finally {
            closeQuietly(raw)
        }
    }

    /**
     * One TLS handshake, fully controlled: pinned address or hostname, SNI on or
     * off, protocol list and ALPN overridable. Returns true when the handshake
     * completed, whatever the certificate turned out to be.
     */
    private fun tlsProbe(
        sb: StringBuilder,
        label: String,
        host: String,
        port: Int,
        address: InetAddress?,
        withSni: Boolean,
        protocols: Array<String>?,
        alpn: Array<String>?
    ): Boolean {
        sb.append("  [").append(label).append("]\n")
        val raw = Socket()
        val started = System.currentTimeMillis()
        try {
            val endpoint = if (address != null) InetSocketAddress(address, port) else InetSocketAddress(host, port)
            raw.connect(endpoint, CONNECT_TIMEOUT_MS)
            val connectedAt = System.currentTimeMillis() - started
            raw.soTimeout = READ_TIMEOUT_MS

            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(TRUST_ANY), java.security.SecureRandom())
            val ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            try {
                ssl.soTimeout = READ_TIMEOUT_MS
                val params = ssl.sslParameters
                // No hostname verification on purpose: the certificate is read
                // and judged below, and a mismatch is the finding, not an error.
                if (withSni) params.serverNames = listOf(SNIHostName(host)) else params.serverNames = emptyList()
                if (alpn != null) params.applicationProtocols = alpn
                if (protocols != null) ssl.enabledProtocols = protocols
                ssl.sslParameters = params

                ssl.startHandshake()
                val handshakeMs = System.currentTimeMillis() - started - connectedAt
                val session = ssl.session
                sb.append("    握手成功 连接=").append(connectedAt).append("ms 握手=")
                    .append(handshakeMs).append("ms protocol=").append(session.protocol)
                    .append(" alpn=").append(ssl.applicationProtocol ?: "-").append('\n')
                val cert = session.peerCertificates.firstOrNull() as? X509Certificate
                if (cert == null) {
                    sb.append("    没有对端证书\n")
                } else {
                    sb.append("    subject=").append(cert.subjectDN.name).append('\n')
                    sb.append("    → ").append(
                        if (certNamesHost(cert, host)) {
                            "证书覆盖 $host（正常）"
                        } else {
                            "证书不覆盖 $host（默认站或中间人）"
                        }
                    ).append('\n')
                }
                return true
            } finally {
                closeQuietly(ssl)
            }
        } catch (t: Throwable) {
            // The elapsed time is evidence too: a reset that arrives far faster
            // than a real round trip to that host was not sent by that host.
            sb.append("    握手失败 连接+握手=").append(System.currentTimeMillis() - started)
                .append("ms ").append(describe(t)).append('\n')
            return false
        } finally {
            closeQuietly(raw)
        }
    }

    /** True when the certificate is valid for [host], by SAN or by wildcard. */
    private fun certNamesHost(cert: X509Certificate, host: String): Boolean {
        val names = mutableListOf<String>()
        try {
            cert.subjectAlternativeNames?.forEach { entry ->
                if (entry.size >= 2 && (entry[0] == 2 || entry[0] == 7)) {
                    names.add(entry[1].toString().lowercase())
                }
            }
        } catch (t: Throwable) {
            // fall through to the CN check
        }
        if (names.isEmpty()) {
            val cn = cert.subjectDN.name
            val marker = "CN="
            val at = cn.indexOf(marker)
            if (at >= 0) {
                names.add(cn.substring(at + marker.length).substringBefore(',').lowercase())
            }
        }
        val wanted = host.lowercase()
        return names.any { name ->
            name == wanted ||
                (name.startsWith("*.") && wanted.endsWith(name.substring(1)) &&
                    wanted.count { it == '.' } == name.count { it == '.' })
        }
    }

    /** Inspection-only trust manager (see [tlsProbe]); never used for real traffic. */
    private val TRUST_ANY = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Exactly what the agent does, through the framework HTTP stack. */
    private fun https(sb: StringBuilder, target: Target) {
        val url = "https://${target.host}:${target.port}${target.path}/agent/hello"
        val connection = java.net.URL(url).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("X-RC-Secret", "probe")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            val payload = """{"id":"PROBE-APP"}""".toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            sb.append("  HTTP ").append(code).append('\n')
            val text = readAll(connection.errorStream ?: connection.inputStream)
            if (text.isNotEmpty()) {
                sb.append("  body=").append(text.take(300)).append('\n')
            }
            if (code == 403) {
                sb.append("  <<< 403 = 已到达 relay 但口令不对\n")
            } else if (code == 200) {
                sb.append("  OK: 应用自己发出的 HTTPS 请求可以到达 relay\n")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The same request written by hand, which skips every HttpURLConnection
     * behaviour (redirect handling, header rewriting, connection pooling) and
     * therefore separates an HTTP-layer rejection from a socket-layer one.
     */
    private fun rawHttp(sb: StringBuilder, target: Target) {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = READ_TIMEOUT_MS
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(raw, target.host, target.port, true) as SSLSocket
            try {
                ssl.soTimeout = READ_TIMEOUT_MS
                val params = ssl.sslParameters
                params.serverNames = listOf(SNIHostName(target.host))
                ssl.sslParameters = params
                ssl.startHandshake()

                val body = """{"id":"PROBE-RAW"}"""
                val request = buildString {
                    append("POST ").append(target.path).append("/agent/hello HTTP/1.1\r\n")
                    append("Host: ").append(target.host).append(':').append(target.port).append("\r\n")
                    append("X-RC-Secret: probe\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ").append(body.toByteArray(Charsets.UTF_8).size).append("\r\n")
                    append("Connection: close\r\n\r\n")
                    append(body)
                }
                ssl.outputStream.use {
                    it.write(request.toByteArray(Charsets.UTF_8))
                    it.flush()
                }
                val text = readAll(ssl.inputStream)
                val firstLine = text.lineSequence().firstOrNull() ?: "(无响应)"
                sb.append("  响应首行: ").append(firstLine).append('\n')
                sb.append("  响应片段: ")
                    .append(text.take(300).replace('\r', ' ').replace('\n', ' '))
                    .append('\n')
                if (firstLine.contains(" 200")) {
                    sb.append("  OK: 裸 TLS+HTTP 可以到达 relay\n")
                }
            } finally {
                closeQuietly(ssl)
            }
        } finally {
            closeQuietly(raw)
        }
    }

    /**
     * Whether the relay name publishes an Encrypted Client Hello policy.
     *
     * This is the one observable difference found so far between the name that
     * fails and the names that work: Chrome speaks ECH and therefore hides the
     * real name behind an encrypted blob, while the Java/Conscrypt stack does
     * not and sends the name in clear. Asking DNS over HTTPS from the phone
     * settles whether the policy exists on this name — and it runs over a
     * different host, which the controls show is reachable.
     */
    private fun echLookup(sb: StringBuilder, target: Target) {
        for (host in listOf(target.host, CONTROL_SNI_HOST)) {
            val url = "https://cloudflare-dns.com/dns-query?name=$host&type=HTTPS"
            val connection = java.net.URL(url).openConnection() as HttpsURLConnection
            try {
                connection.setRequestProperty("accept", "application/dns-json")
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                val text = readAll(connection.inputStream)
                val answers = org.json.JSONObject(text).optJSONArray("Answer")
                var data: String? = null
                if (answers != null) {
                    for (i in 0 until answers.length()) {
                        val record = answers.optJSONObject(i) ?: continue
                        if (record.optInt("type") == 65) {
                            data = record.optString("data")
                            break
                        }
                    }
                }
                if (data == null) {
                    sb.append("  ").append(host).append(": 没有 HTTPS(65) 记录\n")
                } else {
                    val hasEch = data.contains("ech=")
                    sb.append("  ").append(host).append(": ").append(data).append('\n')
                    sb.append("    ECH 配置=").append(if (hasEch) "有" else "无").append('\n')
                    if (hasEch && host.equals(target.host, ignoreCase = true)) {
                        sb.append("    <<< 该域名发布了 ECH：支持 ECH 的客户端（Chrome）会把真实域名")
                            .append("加密，中间设备看不到；不支持 ECH 的 Java/Conscrypt 栈明文送出域名，")
                            .append("可能因此被针对\n")
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun readAll(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return try {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (t: Throwable) {
            "(读取失败: ${t.javaClass.simpleName}: ${t.message ?: ""})"
        }
    }

    private fun mask(secret: String): String {
        if (secret.isEmpty()) return "(空)"
        val head = secret.take(4)
        return "$head…(len=${secret.length})"
    }

    private fun nowText(): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        return format.format(java.util.Date())
    }

    private fun family(address: InetAddress): String = if (address is Inet4Address) "IPv4" else "IPv6"

    private fun resolveQuietly(host: String): List<InetAddress> = try {
        InetAddress.getAllByName(host).toList()
    } catch (t: Throwable) {
        emptyList()
    }

    private fun describe(t: Throwable): String =
        t.javaClass.simpleName + ": " + (t.message ?: "")

    private fun closeQuietly(closeable: java.io.Closeable?) {
        try {
            closeable?.close()
        } catch (t: Throwable) {
            // ignore
        }
    }

    private val CONTROLS = listOf(
        "www.baidu.com" to 443,
        "www.cloudflare.com" to 443,
        RELAY_DIRECT_HOST to RELAY_DIRECT_PORT
    )

    /** A second name to point at our own address, to tell name-blocking from IP-blocking. */
    private const val CONTROL_SNI_HOST = "www.cloudflare.com"

    /**
     * The address the public DNS returns for our own relay host; anything else
     * means interception. Unknown hosts (for example a Cloudflare-proxied name)
     * return null and simply skip the comparison.
     */
    private fun expectedAddressFor(host: String): String? =
        if (host.equals(RELAY_DIRECT_HOST, ignoreCase = true)) RELAY_DIRECT_IP else null

    private const val RELAY_DIRECT_HOST = "dsh.owen1.de5.net"
    private const val RELAY_DIRECT_IP = "212.50.232.54"
    private const val RELAY_DIRECT_PORT = 19092
}
