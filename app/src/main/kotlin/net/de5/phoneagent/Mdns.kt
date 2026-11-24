package net.de5.phoneagent

/**
 * Just enough of the DNS-SD wire format to ask one PTR question and read SRV +
 * A answers back. No Android imports, so the byte layout is unit-testable.
 *
 * Why this exists: on the test tablet NsdManager silently drops this device's
 * own advertisement (measured: 4 consecutive misses over minutes while the PC
 * resolved the same broadcast on every try, and logcat stayed empty). A query
 * we build and send ourselves does not depend on the system resolver at all.
 *
 * Only IPv4 (224.0.0.251) is queried. Wireless-debugging is advertised over
 * both address families, and IPv4 is the one that is always present.
 */
internal object Mdns {

    const val PORT = 5353
    const val GROUP_V4 = "224.0.0.251"

    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_SRV = 33
    const val CLASS_IN = 1

    /** Question-class bit: "answer to my source port, not to the group". */
    const val QU_BIT = 0x8000

    private const val HEADER_BYTES = 12
    private const val MAX_POINTER_JUMPS = 32
    private const val MAX_LABEL_BYTES = 63

    /**
     * What one packet says about the service that was asked for.
     *
     * Every field is nullable because a packet may legitimately be partial: an
     * SRV without an A record still carries a usable port, and the caller falls
     * back to the local address rather than inventing one.
     */
    data class Answer(
        val instance: String?,
        val target: String?,
        val address: String?,
        val port: Int?
    ) {
        /** An out-of-range port is the same as no port - never report a bad one. */
        val found: Boolean get() = port != null && port in 1..65535
    }

    private data class SrvRecord(val owner: String, val target: String, val port: Int)

    /** "_adb-tls-connect._tcp" becomes "_adb-tls-connect._tcp.local". */
    fun queryName(serviceType: String): String {
        val trimmed = serviceType.trim().trimEnd('.')
        if (trimmed.length == 0) return "local"
        return if (trimmed.endsWith(".local", ignoreCase = true)) trimmed else trimmed + ".local"
    }

    /**
     * A standard one-question DNS query for the service's PTR record.
     *
     * @param unicastResponse set the QU bit so the responder replies straight
     *   to our ephemeral port. Without it a responder multicasts to 5353, which
     *   we cannot reliably bind next to the system's own mDNS daemon.
     */
    fun ptrQuery(serviceType: String, unicastResponse: Boolean): ByteArray {
        val name = encodeName(queryName(serviceType))
        val out = ByteArray(HEADER_BYTES + name.size + 4)
        // ID 0, flags 0, QDCOUNT 1, AN/NS/AR 0 - all big-endian by protocol.
        out[5] = 1
        name.copyInto(out, HEADER_BYTES)
        var at = HEADER_BYTES + name.size
        out[at++] = (TYPE_PTR shr 8).toByte()
        out[at++] = TYPE_PTR.toByte()
        val qclass = if (unicastResponse) CLASS_IN or QU_BIT else CLASS_IN
        out[at++] = (qclass shr 8).toByte()
        out[at] = qclass.toByte()
        return out
    }

    /** Length-prefixed labels, terminated by a zero byte. */
    fun encodeName(name: String): ByteArray {
        val labels = ArrayList<ByteArray>(4)
        for (raw in name.split('.')) {
            if (raw.length == 0) continue
            var bytes = raw.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_LABEL_BYTES) bytes = bytes.copyOf(MAX_LABEL_BYTES)
            labels.add(bytes)
        }
        var size = 1
        for (label in labels) size += 1 + label.size
        val out = ByteArray(size)
        var at = 0
        for (label in labels) {
            out[at++] = label.size.toByte()
            label.copyInto(out, at)
            at += label.size
        }
        out[at] = 0
        return out
    }

    /**
     * Parse one response packet.
     *
     * Only an SRV record whose owner matches a PTR record for [serviceType]
     * counts: a packet full of other services' records must not be mistaken for
     * ours. A truncated or hostile packet returns an empty [Answer], never an
     * exception - this runs on the poll thread.
     */
    fun parse(packet: ByteArray, length: Int, serviceType: String): Answer {
        val empty = Answer(null, null, null, null)
        val limit = if (length in 0..packet.size) length else packet.size
        if (limit < HEADER_BYTES) return empty
        val wanted = queryName(serviceType)
        val instances = ArrayList<String>(4)
        val srvs = ArrayList<SrvRecord>(4)
        val addresses = HashMap<String, String>(4)
        try {
            val questions = u16(packet, 4)
            val records = u16(packet, 6) + u16(packet, 8) + u16(packet, 10)
            var at = HEADER_BYTES
            repeat(questions) {
                val question = readName(packet, at, limit) ?: return empty
                at = question.second + 4
                if (at > limit) return empty
            }
            repeat(records) {
                val owner = readName(packet, at, limit) ?: return empty
                val fields = owner.second
                if (fields + 10 > limit) return empty
                val type = u16(packet, fields)
                val rdLength = u16(packet, fields + 8)
                val rdata = fields + 10
                if (rdLength < 0 || rdata + rdLength > limit) return empty
                when (type) {
                    TYPE_PTR -> {
                        val target = readName(packet, rdata, limit)
                        if (target != null && sameName(owner.first, wanted)) {
                            instances.add(target.first)
                        }
                    }
                    TYPE_SRV -> {
                        if (rdLength >= 6) {
                            val port = u16(packet, rdata + 4)
                            val target = readName(packet, rdata + 6, limit)
                            if (target != null && port in 1..65535) {
                                srvs.add(SrvRecord(owner.first, target.first, port))
                            }
                        }
                    }
                    TYPE_A -> {
                        if (rdLength == 4) addresses[normalize(owner.first)] = ipv4(packet, rdata)
                    }
                }
                at = rdata + rdLength
            }
        } catch (t: Throwable) {
            return empty
        }

        // A PTR for our service is mandatory: that is what makes the rest ours.
        var chosen: SrvRecord? = null
        for (srv in srvs) {
            for (instance in instances) {
                if (sameName(instance, srv.owner)) {
                    chosen = srv
                    break
                }
            }
            if (chosen != null) break
        }
        val picked = chosen ?: return empty
        var address: String? = null
        // The A record is looked up by the SRV target; some responders put the
        // address under the instance name instead, so both are accepted.
        val byTarget = addresses[normalize(picked.target)]
        if (byTarget != null) {
            address = byTarget
        } else {
            for (instance in instances) {
                val byInstance = addresses[normalize(instance)]
                if (byInstance != null) {
                    address = byInstance
                    break
                }
            }
        }
        return Answer(instances[0], picked.target, address, picked.port)
    }

    /**
     * Read a (possibly compressed) name.
     *
     * @return the dotted name and the offset just past it, or null when the
     *   packet is truncated or the pointer chain loops.
     */
    private fun readName(packet: ByteArray, start: Int, limit: Int): Pair<String, Int>? {
        val builder = StringBuilder(48)
        var at = start
        var after = -1
        var jumps = 0
        while (true) {
            if (at < 0 || at >= limit) return null
            val length = packet[at].toInt() and 0xFF
            if (length == 0) {
                at += 1
                break
            }
            if (length and 0xC0 == 0xC0) {
                if (at + 1 >= limit) return null
                val pointer = ((length and 0x3F) shl 8) or (packet[at + 1].toInt() and 0xFF)
                if (after < 0) after = at + 2
                jumps += 1
                if (jumps > MAX_POINTER_JUMPS) return null
                at = pointer
                continue
            }
            if (length and 0xC0 != 0) return null
            at += 1
            if (at + length > limit) return null
            if (builder.isNotEmpty()) builder.append('.')
            builder.append(String(packet, at, length, Charsets.UTF_8))
            at += length
        }
        return builder.toString() to (if (after >= 0) after else at)
    }

    private fun u16(packet: ByteArray, at: Int): Int =
        ((packet[at].toInt() and 0xFF) shl 8) or (packet[at + 1].toInt() and 0xFF)

    private fun ipv4(packet: ByteArray, at: Int): String =
        (packet[at].toInt() and 0xFF).toString() + "." +
            (packet[at + 1].toInt() and 0xFF) + "." +
            (packet[at + 2].toInt() and 0xFF) + "." +
            (packet[at + 3].toInt() and 0xFF)

    /** DNS names are case-insensitive and may carry a trailing root dot. */
    private fun normalize(name: String): String = name.trimEnd('.').lowercase()

    private fun sameName(a: String, b: String): Boolean = normalize(a) == normalize(b)
}
