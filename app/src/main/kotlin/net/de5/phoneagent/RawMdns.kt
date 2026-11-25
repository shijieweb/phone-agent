package net.de5.phoneagent

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections

/**
 * The second discovery tier: a mDNS query we send ourselves.
 *
 * Both phases send from an **ephemeral** port instead of binding 5353, so we
 * never fight the system's own mDNS daemon for the socket:
 *
 * 1. QU query - the question class carries the unicast-response bit, so a
 *    responder answers straight to our port;
 * 2. plain query - RFC 6762 6.7 says a query from a port other than 5353 is
 *    answered by unicast (legacy unicast response), which is what we want, and
 *    covers responders that ignore the QU bit.
 *
 * If neither phase hears anything, the caller reports a miss. A port is only
 * ever reported when a real SRV record carried it.
 */
internal object RawMdns {

    private const val RECV_SLICE_MS = 200
    private const val UNICAST_PHASE_MS = 1_300L
    private const val MIN_PHASE_MS = 500L
    private const val BUFFER_BYTES = 4096

    /**
     * One raw-query outcome; a null [port] always comes with a [note].
     *
     * [ignored] lists the advertisements [MdnsGate] dropped because they came
     * from another device on the same Wi-Fi (see the tablet bug in MdnsGate's
     * doc). The caller turns it into the operator-visible "已忽略 N 个其它设备
     * 的广播" line.
     */
    internal class Hit(
        val port: Int?,
        val host: String?,
        val note: String?,
        val ignored: List<String> = emptyList()
    )

    fun discover(context: Context, serviceType: String, budgetMs: Long): Hit {
        if (budgetMs < MIN_PHASE_MS) return Hit(null, null, "裸 mDNS 查询没有时间预算")
        var lock: WifiManager.MulticastLock? = null
        try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val multicast = wifi?.createMulticastLock("phone-agent-rawmdns")
            multicast?.setReferenceCounted(false)
            multicast?.acquire()
            lock = multicast
        } catch (t: Throwable) {
            lock = null
        }
        try {
            val deadline = System.currentTimeMillis() + budgetMs
            // 一次发现只读一次本机地址：地址闸要用它，收包循环里不必反复问系统。
            val localIp = AdbWifi.localIpv4(context)
            val rejected = ArrayList<String>()
            val unicastBudget = if (budgetMs < UNICAST_PHASE_MS) budgetMs else UNICAST_PHASE_MS
            val first = ask(
                serviceType = serviceType,
                unicastResponse = true,
                budgetMs = unicastBudget,
                localIp = localIp,
                rejected = rejected
            )
            if (first.port != null) return first

            val remaining = deadline - System.currentTimeMillis()
            if (remaining < MIN_PHASE_MS) return Hit(null, null, first.note, rejected.toList())
            val second = ask(
                serviceType = serviceType,
                unicastResponse = false,
                budgetMs = remaining,
                localIp = localIp,
                rejected = rejected
            )
            if (second.port != null) return second
            return Hit(null, null, joinNotes(first.note, second.note), rejected.toList())
        } finally {
            try {
                lock?.release()
            } catch (t: Throwable) {
                // the lock may never have been acquired
            }
        }
    }

    /** One query + receive window. Never throws; failures become a note. */
    private fun ask(
        serviceType: String,
        unicastResponse: Boolean,
        budgetMs: Long,
        localIp: String?,
        rejected: MutableList<String>
    ): Hit {
        val service = Mdns.queryName(serviceType)
        val query = Mdns.ptrQuery(service, unicastResponse)
        var socket: MulticastSocket? = null
        try {
            val opened = MulticastSocket(null as InetSocketAddress?)
            opened.reuseAddress = true
            opened.bind(InetSocketAddress(0))
            socket = opened

            val group = InetAddress.getByName(Mdns.GROUP_V4)
            opened.send(DatagramPacket(query, query.size, group, Mdns.PORT))

            val deadline = System.currentTimeMillis() + budgetMs
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                opened.soTimeout = if (remaining < RECV_SLICE_MS) remaining.toInt() else RECV_SLICE_MS
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    opened.receive(packet)
                } catch (timeout: SocketTimeoutException) {
                    continue
                } catch (t: Throwable) {
                    break
                }
                val answer = Mdns.parse(buffer, packet.length, service)
                val foundPort = answer.port
                // 收到第一个应答就走人，是平板拿到手机端口的直接原因：
                // mDNS 是局域网广播，先到的未必是自己那台。过闸才算数。
                if (answer.found && foundPort != null) {
                    val decision = MdnsGate.check(localIp, answer.address, foundPort)
                    if (decision.pass) return Hit(foundPort, answer.address, null, rejected.toList())
                    val why = decision.rejected
                    if (why != null && !rejected.contains(why)) rejected.add(why)
                }
            }
            return Hit(null, null, "裸 mDNS 查询 " + budgetMs + "ms 内没有收到应答")
        } catch (t: Throwable) {
            return Hit(null, null, "裸 mDNS 查询异常: " + describe(t))
        } finally {
            try {
                socket?.close()
            } catch (t: Throwable) {
                // already closed
            }
        }
    }

    private fun joinNotes(first: String?, second: String?): String {
        val parts = ArrayList<String>(2)
        if (!first.isNullOrBlank()) parts.add(first)
        if (!second.isNullOrBlank() && second != first) parts.add(second)
        return if (parts.isEmpty()) "裸 mDNS 查询没有收到应答" else parts.joinToString("；")
    }

    /** The wlan NIC, so the query leaves through the interface the tablet is using. */
    @Suppress("unused")
    private fun wlanInterface(): NetworkInterface? = try {
        var hit: NetworkInterface? = null
        for (nic in Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (nic.isLoopback || !nic.isUp) continue
            val name = nic.name ?: ""
            if (!name.startsWith("wlan") && !name.startsWith("swlan")) continue
            for (address in Collections.list(nic.inetAddresses)) {
                if (address is Inet4Address && !address.isLinkLocalAddress && !address.isLoopbackAddress) {
                    hit = nic
                    break
                }
            }
            if (hit != null) break
        }
        hit
    } catch (t: Throwable) {
        null
    }
}
