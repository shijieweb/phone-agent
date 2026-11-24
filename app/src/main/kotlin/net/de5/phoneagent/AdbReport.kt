package net.de5.phoneagent

/**
 * What wireless-debugging discovery managed to learn, plus the pure report
 * rendering that goes with it.
 *
 * Every nullable field means **honestly unknown** - never a placeholder. This
 * file has no Android imports on purpose, so the formatting, the connect string
 * and the cache-freshness rule are unit-testable on a plain JVM (SPEC.md §10).
 */
data class AdbWifiInfo(
    /** Raw value of `adb_wifi_enabled`: "0", "1", or null when unreadable. */
    val enabledValue: String?,
    /** Current wlan IPv4 address, or null when it could not be read. */
    val localIp: String?,
    /** Discovered port, or null when no broadcast was resolved. */
    val port: Int?,
    /** Where [port] came from, e.g. "mDNS _adb-tls-connect._tcp". */
    val source: String?,
    /** Why [port] is unknown; always set when [port] is null. */
    val note: String?,
    /** TCP probe of [port]: null when there was nothing to probe. */
    val tcpOk: Boolean?
)

object AdbReport {

    /** The heartbeat only advertises a port discovered this recently. */
    const val PORT_CACHE_TTL_MS = 10 * 60 * 1000L

    /** How long the optional port probe waits for a TCP handshake. */
    const val TCP_PROBE_TIMEOUT_MS = 200

    const val UNKNOWN_IP = "未取到本机 IP"
    const val NO_PORT = "未发现"
    const val SERVICE_TYPE = "_adb-tls-connect._tcp"

    /** Source labels, so a report always says how the port was learned. */
    const val SOURCE_NSD = "_adb-tls-connect._tcp（NsdManager）"
    const val SOURCE_RAW = "_adb-tls-connect._tcp（裸 mDNS 查询）"

    /**
     * "host:port" as the operator would paste it into `adb connect`, or null
     * when there is no port. An unknown IP is spelled out rather than invented:
     * a wrong address is worse than an obviously incomplete one.
     */
    fun connectString(localIp: String?, port: Int?): String? {
        if (port == null || port <= 0 || port > 65535) return null
        return (localIp ?: UNKNOWN_IP) + ":" + port
    }

    /** True when a cached discovery may still be advertised. */
    fun isFresh(atMillis: Long, nowMillis: Long): Boolean {
        if (atMillis <= 0L) return false
        val age = nowMillis - atMillis
        return age >= 0L && age < PORT_CACHE_TTL_MS
    }

    /**
     * The heartbeat `env` fragment, e.g. "adbwifi=38181".
     *
     * @return null unless a port was discovered recently - the relay must never
     *   see a made-up value, so a stale cache simply omits the field.
     */
    fun envField(port: Int, atMillis: Long, nowMillis: Long): String? {
        if (port <= 0 || port > 65535) return null
        if (!isFresh(atMillis, nowMillis)) return null
        return "adbwifi=" + port
    }

    /** Seconds the cache has been alive, or null when it was never set. */
    fun cacheAgeSeconds(atMillis: Long, nowMillis: Long): Long? {
        if (atMillis <= 0L) return null
        val age = nowMillis - atMillis
        return if (age < 0L) null else age / 1000L
    }

    /** "缓存（Ns 前发现）" - a cached answer must never look freshly measured. */
    fun cacheSource(ageSeconds: Long): String = "缓存（" + ageSeconds + "s 前发现）"

    /**
     * What both discovery tiers failing means, said plainly.
     *
     * The two tiers are named apart on purpose: "NsdManager found nothing" and
     * "a query I sent myself got no reply" are different faults, and only the
     * second one points at the network rather than at the ROM.
     */
    fun twoTierNote(nsdNote: String?, rawNote: String?): String {
        val builder = StringBuilder("NsdManager 未发现")
        if (!nsdNote.isNullOrBlank()) builder.append("（").append(nsdNote).append("）")
        builder.append("，裸 mDNS 查询也未收到应答")
        if (!rawNote.isNullOrBlank() && rawNote != nsdNote) builder.append("（").append(rawNote).append("）")
        return builder.toString()
    }

    /**
     * Added to a *stale* cache miss: the port moved, and the operator's next
     * move is not another query.
     */
    fun staleHint(ageSeconds: Long?): String =
        "缓存已过期（" + (ageSeconds ?: 0L) + "s 前发现，端口会随重启或开关变化）；建议跑 adbheal"

    /**
     * How long the heartbeat tolerates an unobserved cache before it spends a
     * little of its own time looking again.
     *
     * Ten minutes (the TTL) is when the field *disappears*; thirty is when a
     * background look is worth it, so a port that merely aged out is not
     * re-discovered on every single heartbeat.
     */
    const val OBSERVE_STALE_MS = 30 * 60 * 1000L

    /**
     * Boot heal and heartbeat observation only ever *enable*; neither bounces a
     * working daemon, because a bounce drops a live adb session and neither was
     * asked to.
     */
    fun bootShouldEnable(enabledValue: String?): Boolean = enabledValue != "1"

    /**
     * True when the cache is missing or older than [staleMs] - i.e. worth
     * spending a discovery round on. A clock that moved backwards is not an
     * invitation to hammer the network, so it is treated as "not due".
     */
    fun observeDue(atMillis: Long, nowMillis: Long, staleMs: Long = OBSERVE_STALE_MS): Boolean {
        if (atMillis <= 0L) return true
        val age = nowMillis - atMillis
        return age >= staleMs
    }

    /** The multi-line report: this is the command's stdout. */
    fun format(info: AdbWifiInfo): String {
        val lines = ArrayList<String>(6)
        lines.add("adb_wifi_enabled = " + (info.enabledValue ?: "(null)"))
        lines.add("本机 IP = " + (info.localIp ?: UNKNOWN_IP))
        if (info.port == null) {
            lines.add("无线调试端口 = " + NO_PORT)
        } else {
            lines.add("无线调试端口 = " + info.port + "（来源 " + (info.source ?: "未知") + "）")
        }
        if (!info.note.isNullOrBlank()) lines.add("说明 = " + info.note)
        lines.add("connect = " + (connectString(info.localIp, info.port) ?: "无（未发现端口）"))
        lines.add(
            "TCP 探测 = " + when (info.tcpOk) {
                null -> "未做（没有端口）"
                true -> "可连"
                false -> "不可连（" + TCP_PROBE_TIMEOUT_MS + "ms 内没有完成握手）"
            }
        )
        return lines.joinToString("\n")
    }
}
