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
 *  3. TCP         — plain connect to the resolved address on 19092
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

    const val HOST = "dsh.owen1.de5.net"
    const val PORT = 19092
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
        probe("2 DNS", sb) { dns(sb) }
        probe("3 TCP", sb) { tcp(sb) }
        probe("4 TLS", sb) { tls(sb) }
        probe("5 HTTPS", sb) { https(sb) }
        probe("6 原始HTTP", sb) { rawHttp(sb) }

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
    private fun dns(sb: StringBuilder): List<InetAddress> {
        val addresses = try {
            InetAddress.getAllByName(HOST).toList()
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
        if (addresses.none { it.hostAddress == EXPECTED_IP }) {
            sb.append("  <<< 期望 ").append(EXPECTED_IP)
                .append("，实际没有这个地址 → DNS 被改写或走了代理")
        }
        return addresses
    }

    private fun tcp(sb: StringBuilder) {
        val resolved = try {
            InetAddress.getAllByName(HOST).mapNotNull { it.hostAddress }
        } catch (t: Throwable) {
            emptyList()
        }
        // Test the hard-coded address as well: if DNS hands back something else
        // and that something refuses connections, the two results together say
        // "DNS is the problem" rather than "the server is down".
        val targets = (resolved + EXPECTED_IP).distinct()

        for (target in targets) {
            val socket = Socket()
            val started = System.currentTimeMillis()
            try {
                socket.connect(InetSocketAddress(target, PORT), CONNECT_TIMEOUT_MS)
                sb.append("  ").append(target).append(":").append(PORT)
                    .append(" 连接成功 ").append(System.currentTimeMillis() - started).append("ms\n")
            } catch (t: Throwable) {
                sb.append("  ").append(target).append(":").append(PORT)
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
    private fun tls(sb: StringBuilder) {
        val inspected = inspectTls(sb, "带 SNI（正常情况）", withSni = true)
        inspectTls(sb, "不带 SNI（对照）", withSni = false)

        when (inspected) {
            CertKind.REAL -> sb.append("  OK: 带 SNI 能拿到正式证书 → TLS/SNI 这一层没问题，故障在更上层\n")
            CertKind.DUMMY -> sb.append("  <<< 命中: 带 SNI 也拿到 dummy 证书 → nginx 没认出我们的 SNI")
            CertKind.UNKNOWN -> sb.append("  <<< 带 SNI 的握手没成功，看上面的异常信息\n")
        }
    }

    private enum class CertKind { REAL, DUMMY, UNKNOWN }

    private fun inspectTls(sb: StringBuilder, label: String, withSni: Boolean): CertKind {
        sb.append("  [").append(label).append("]\n")
        val raw = Socket()
        var kind = CertKind.UNKNOWN
        try {
            raw.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
            raw.soTimeout = READ_TIMEOUT_MS
            val context = javax.net.ssl.SSLContext.getInstance("TLS")
            context.init(null, arrayOf(TRUST_ANY), java.security.SecureRandom())
            val ssl = context.socketFactory.createSocket(raw, HOST, PORT, true) as SSLSocket
            try {
                ssl.soTimeout = READ_TIMEOUT_MS
                val params = ssl.sslParameters
                // No hostname verification on purpose: we read the certificate
                // ourselves below, and a mismatch is the finding, not an error.
                if (withSni) params.serverNames = listOf(SNIHostName(HOST))
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
                    kind = if (subject.contains(EXPECTED_CN, ignoreCase = true)) {
                        sb.append("    → 正式站点证书\n")
                        CertKind.REAL
                    } else {
                        sb.append("    → 不是正式证书（nginx 默认站）\n")
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

    /** Inspection-only trust manager (see [inspectTls]); never used for real traffic. */
    private val TRUST_ANY = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Exactly what the agent does, through the framework HTTP stack. */
    private fun https(sb: StringBuilder) {
        val connection = java.net.URL("https://$HOST:$PORT/agent/hello").openConnection() as HttpsURLConnection
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
    private fun rawHttp(sb: StringBuilder) {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
            raw.soTimeout = READ_TIMEOUT_MS
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(raw, HOST, PORT, true) as SSLSocket
            try {
                ssl.soTimeout = READ_TIMEOUT_MS
                val params = ssl.sslParameters
                params.serverNames = listOf(SNIHostName(HOST))
                ssl.sslParameters = params
                ssl.startHandshake()

                val body = """{"id":"PROBE-RAW"}"""
                val request = buildString {
                    append("POST /agent/hello HTTP/1.1\r\n")
                    append("Host: ").append(HOST).append(':').append(PORT).append("\r\n")
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

    /** The address the public DNS returns; anything else means interception. */
    private const val EXPECTED_IP = "212.50.232.54"
    private const val EXPECTED_CN = "dsh.owen1.de5.net"
}
