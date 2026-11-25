package net.de5.phoneagent

import android.content.Context
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * mDNS 广播的过滤闸：只认「本机地址」或「回环上真开着的端口」。
 *
 * 为什么必须有这道闸：mDNS 是局域网广播，同一网段里每台开了无线调试的设备都会
 * 播 _adb-tls-connect._tcp。平板 Lenovo TB-J607F 实测拿到的是**手机**的端口
 * （192.168.0.106:40157），于是 shconnect 稳定超时 exit=4；桌面 adb 同一刻看到
 * 两条广播，平板自己真正的端口是 192.168.0.108:41081。
 * 上游 AdbMdns 只保证「这个端口在本机某块网卡上能 bind」，它不过滤地址
 * （AdbMdns.java:112-128），所以闸只能我们自己加。
 *
 * 两道闸，顺序固定：
 *
 * 1. **地址**：广播里的地址等于本机 wlan IPv4 才算数。地址取不到（ROM 不给，
 *    或 SRV 里没有 A 记录）时判「不可判」，交给第二道。
 * 2. **回环**：真去连 127.0.0.1:port。自己的 adbd 端口一定在回环上开着（SelfAdb
 *    本来就是连 127.0.0.1），别人的端口在回环上一定连不上——比字符串比对硬。
 *
 * 地址能判定的情况下**不再多花一次 TCP 往返**：地址就是本机，没有第二种可能。
 *
 * 明确不做的事：**绝不拿候选端口挨个试连**。找不到就老实说找不到，并把忽略掉的
 * 广播写进报错，让操作员知道是「被过滤了」而不是「网线没插」。
 */
internal object MdnsGate {

    /** 本机 IP 读不到时的说明，调用方可以把它记进日志。 */
    const val UNKNOWN_LOCAL_IP = "本机 IP 未知，未做地址过滤"

    /** 地址这一道闸的三种结论。 */
    internal enum class Verdict {
        /** 确定是本机（地址相同，或广播给的是回环地址）。 */
        SELF,

        /** 地址不可判：本机 IP 未知，或广播里没有地址。 */
        UNVERIFIED,

        /** 确定不是本机。 */
        OTHER
    }

    /** 一次过滤的结论；[rejected] 只在 [pass] 为 false 时有值，直接拼进报错。 */
    internal class Decision(val pass: Boolean, val rejected: String?)

    /**
     * 只看地址的判定。纯函数，不碰网络，单测直接打这个。
     */
    fun addressVerdict(localIp: String?, host: String?): Verdict {
        val local = localIp?.trim().orEmpty()
        if (local.isEmpty()) return Verdict.UNVERIFIED
        val peer = host?.trim().orEmpty()
        if (peer.isEmpty()) return Verdict.UNVERIFIED
        if (isLoopback(peer)) return Verdict.SELF
        return if (peer == local) Verdict.SELF else Verdict.OTHER
    }

    /** 两道闸都过才算 [Decision.pass]；本机 IP 由调用方先取好（一次发现只取一次）。 */
    fun check(localIp: String?, host: String?, port: Int): Decision {
        if (port !in 1..65535) return Decision(false, null)
        val label = label(host, port)
        return when (addressVerdict(localIp, host)) {
            Verdict.SELF -> Decision(true, null)
            Verdict.OTHER -> Decision(false, label + "（不是本机地址）")
            Verdict.UNVERIFIED ->
                if (loopbackOpen(port)) {
                    Decision(true, null)
                } else {
                    Decision(false, label + "（回环上连不上）")
                }
        }
    }

    /** 便捷版：每次现读本机 IP。循环里请自己取 [AdbWifi.localIpv4] 再调纯函数版。 */
    fun check(context: Context, host: String?, port: Int): Decision =
        check(AdbWifi.localIpv4(context), host, port)

    fun isSelf(context: Context, host: InetAddress?): Boolean = isSelf(context, host?.hostAddress)

    fun isSelf(context: Context, host: String?): Boolean =
        addressVerdict(AdbWifi.localIpv4(context), host) != Verdict.OTHER

    /**
     * 127.0.0.1:port 上有没有人在听。
     *
     * 300ms 足够回环握手（回环上失败是立刻 ECONNREFUSED，不会等到超时）。
     */
    fun loopbackOpen(port: Int, timeoutMs: Int = 300): Boolean {
        if (port !in 1..65535) return false
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), timeoutMs)
            true
        } catch (t: Throwable) {
            false
        } finally {
            try {
                socket.close()
            } catch (ignored: Throwable) {
                // 探针的结论已经拿到了
            }
        }
    }

    fun isLoopback(host: String?): Boolean {
        val text = host?.trim().orEmpty()
        if (text.isEmpty()) return false
        return text == "127.0.0.1" || text == "::1" || text.startsWith("127.")
    }

    /** "192.168.0.106:40157"，地址缺失时只说端口。 */
    fun label(host: String?, port: Int): String {
        val peer = host?.trim().orEmpty()
        return if (peer.isEmpty()) "端口 " + port else peer + ":" + port
    }

    /**
     * 一句给操作员看的话：「已忽略 2 个其它设备的广播（如 192.168.0.106:40157…）」。
     *
     * 样例最多两个：报错行要能在一屏里读完，全量写进日志反而没人看。
     */
    fun ignoredLine(entries: List<String>): String? {
        if (entries.isEmpty()) return null
        val sample = entries.take(2).joinToString("、")
        val tail = if (entries.size > 2) " 等" else ""
        return "已忽略 " + entries.size + " 个其它设备的广播（如 " + sample + tail + "）"
    }
}
