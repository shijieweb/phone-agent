package net.de5.phoneagent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Self-diagnosis for the "SocketException: Connection reset" class of failures.
 *
 * The phone is unreachable by adb and its logcat is not readable from the outside,
 * so the only channel out of the device is text the operator copies to the
 * clipboard. A plain log dump would not be enough: the relay path can break at
 * four different layers and each one needs a different fix, so this probe walks
 * the layers in order and reports which one fails.
 *
 *  1. environment — proxy properties, VPN, active transports
 *  2. DNS         — which address the phone actually resolves (a hijacked or
 *                   VPN-supplied answer explains a lot)
 *  3. TCP         — plain connect to the resolved address on the relay port
 *  4. TLS         — full handshake with an explicit SNI, then dump the peer
 *                   certificate. Reaching the server without SNI lands on the
 *                   nginx `default_server` block, which returns 444 and resets
 *                   the connection — indistinguishable from a network failure
 *                   unless the certificate is inspected.
 *  5. HTTPS       — the same POST the agent makes, through HttpURLConnection
 *  6. raw HTTP    — the same POST written by hand over the TLS socket, to tell
 *                   an HTTP-layer rejection apart from a framework/TLS one
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
            sb.append("\n--- 探针 2-6 跳过 ---\n")
            sb.append("  relay 地址无法解析为 host:port: ").append(prefs.relayBase).append('\n')
        } else {
            sb.append("  探测目标: ").append(target.host).append(':').append(target.port)
                .append("  路径前缀=").append(target.path.ifEmpty { "/" }).append('\n')
            probe("2 DNS", sb) { dns(sb, target) }
            probe("3 TCP", sb) { tcp(sb, target) }
            probe("4 TLS", sb) { tls(sb, target) }
            probe("5 HTTPS", sb) { https(sb, target) }
            probe("6 原始HTTP", sb) { rawHttp(sb, target) }
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

        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            sb.append("  ConnectivityManager 不可用\n")
            return
        }
        val network = cm.activeNetwork
        if (network == null) {
            sb.append("  没有活动网络\n")
            return
        }
        val caps = cm.getNetworkCapabilities(network)
        if (caps == null) {
            sb.append("  无法读取网络能力\n")
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
            sb.append("  ").append(address.hostAddress).append('\n')
        }
        val expected = expectedAddressFor(target.host)
        if (expected != null && addresses.none { it.hostAddress == expected }) {
            sb.append("  <<< 期望 ").append(expected)
                .append("，实际没有这个地址 → DNS 被改写或走了代理")
        }
        return addresses
    }

    private fun tcp(sb: StringBuilder, target: Target) {
        val resolved = try {
            InetAddress.getAllByName(target.host).mapNotNull { it.hostAddress }
        } catch (t: Throwable) {
            emptyList()
        }
        // Test the hard-coded address as well: if DNS hands back something else
        // and that something refuses connections, the two results together say
        // "DNS is the problem" rather than "the server is down".
        val expected = expectedAddressFor(target.host)
        val targets = (resolved + listOfNotNull(expected)).distinct()

        for (address in targets) {
            val socket = Socket()
            val started = System.currentTimeMillis()
            try {
                socket.connect(InetSocketAddress(address, target.port), CONNECT_TIMEOUT_MS)
                sb.append("  ").append(address).append(":").append(target.port)
                    .append(" 连接成功 ").append(System.currentTimeMillis() - started).append("ms\n")
            } catch (t: Throwable) {
                sb.append("  ").append(address).append(":").append(target.port)
                    .append(" 连接失败 ").append(System.currentTimeMillis() - started).append("ms ")
                    .append(t.javaClass.simpleName).append(": ").append(t.message ?: "").append('\n')
            } finally {
                closeQuietly(socket)
            }
        }
    }

    /**
     * The decisive probe, run as a controlled A/B: the same handshake once with
     * an explicit SNI and once without one.
     *
     * The default trust manager cannot be used here — nginx serves a self-signed
     * `dummy.crt` on its default server, so a stripped SNI makes the handshake
     * fail before the certificate can be inspected, and "handshake failed" is
     * exactly the ambiguity we are trying to remove. A permissive manager is
     * installed for this probe **only** so the peer certificate can be read and
     * compared; no data is ever sent over these sockets.
     *
     * Interpretation:
     *   with-SNI cert == dsh.owen1.de5.net  → SNI works; look elsewhere
     *   with-SNI cert == dummy              → our ClientHello loses its SNI
     *   no-SNI   cert == dummy              → confirms the 444 default server
     */
    private fun tls(sb: StringBuilder, target: Target) {
        val inspected = inspectTls(sb, "带 SNI（正常情况）", withSni = true, target = target)
        inspectTls(sb, "不带 SNI（对照）", withSni = false, target = target)

        when (inspected) {
            CertKind.REAL -> sb.append("  OK: 带 SNI 能拿到正式证书 → TLS/SNI 这一层没问题，故障在更上层\n")
            CertKind.DUMMY -> sb.append("  <<< 命中: 带 SNI 也拿到 dummy 证书 → nginx 没认出我们的 SNI")
            CertKind.UNKNOWN -> sb.append("  <<< 带 SNI 的握手没成功，看上面的异常信息\n")
        }
    }

    private enum class CertKind { REAL, DUMMY, UNKNOWN }

    private fun inspectTls(sb: StringBuilder, label: String, withSni: Boolean, target: Target): CertKind {
        sb.append("  [").append(label).append("]\n")
        val raw = Socket()
        var kind = CertKind.UNKNOWN
        try {
            raw.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = READ_TIMEOUT_MS
            val context = javax.net.ssl.SSLContext.getInstance("TLS")
            context.init(null, arrayOf(TRUST_ANY), java.security.SecureRandom())
            val ssl = context.socketFactory.createSocket(raw, target.host, target.port, true) as SSLSocket
            try {
                ssl.soTimeout = READ_TIMEOUT_MS
                val params = ssl.sslParameters
                // No hostname verification on purpose: we read the certificate
                // ourselves below, and a mismatch is the finding, not an error.
                if (withSni) params.serverNames = listOf(SNIHostName(target.host))
                ssl.sslParameters = params
                ssl.startHandshake()

                val session = ssl.session
                sb.append("    握手成功 protocol=").append(session.protocol)
                    .append(" cipher=").append(session.cipherSuite).append('\n')
                val cert = session.peerCertificates.firstOrNull() as? X509Certificate
                if (cert == null) {
                    sb.append("    没有对端证书\n")
                } else {
                    val subject = cert.subjectDN.name
                    sb.append("    subject=").append(subject).append('\n')
                    sb.append("    issuer=").append(cert.issuerDN.name).append('\n')
                    sb.append("    有效期=").append(cert.notBefore).append(" → ").append(cert.notAfter)
                        .append('\n')
                    // A real endpoint presents a certificate that names the host we
                    // asked for (or one of Cloudflare's edge names when the host is
                    // proxied). Anything else means we are talking to a fallback
                    // server, which is itself the finding.
                    kind = if (certNamesHost(cert, target.host)) {
                        sb.append("    → 证书覆盖 ").append(target.host).append("（正常）\n")
                        CertKind.REAL
                    } else {
                        sb.append("    → 证书不覆盖 ").append(target.host)
                            .append("（默认站/中间人）\n")
                        CertKind.DUMMY
                    }
                }
            } finally {
                closeQuietly(ssl)
            }
        } catch (t: Throwable) {
            sb.append("    握手失败 ").append(t.javaClass.name).append(": ")
                .append(t.message ?: "").append('\n')
        } finally {
            closeQuietly(raw)
        }
        return kind
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

    /** Inspection-only trust manager (see [inspectTls]); never used for real traffic. */
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

    private fun closeQuietly(closeable: java.io.Closeable?) {
        try {
            closeable?.close()
        } catch (t: Throwable) {
            // ignore
        }
    }

    /**
     * The address the public DNS returns for our own relay host; anything else
     * means interception. Unknown hosts (for example a Cloudflare-proxied name)
     * return null and simply skip the comparison.
     */
    private fun expectedAddressFor(host: String): String? =
        if (host.equals(RELAY_DIRECT_HOST, ignoreCase = true)) RELAY_DIRECT_IP else null

    private const val RELAY_DIRECT_HOST = "dsh.owen1.de5.net"
    private const val RELAY_DIRECT_IP = "212.50.232.54"
}
