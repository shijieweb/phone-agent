package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DNS-SD byte layout, tested without a network.
 *
 * This tier exists because NsdManager was measured going silent for minutes
 * while the advertisement was still on the wire, so the hand-built query and
 * the parser are the part that must not be wrong: a parser that guesses would
 * report a port nobody is listening on.
 */
class MdnsTest {

    private val service = "_adb-tls-connect._tcp"
    private val instance = "adb-HA1GL59N-wie4ed." + service + ".local"
    private val target = "Android.local"

    @Test
    fun theQueryAsksOnePtrQuestionForTheService() {
        val packet = Mdns.ptrQuery(service, unicastResponse = true)

        // Header: id 0, flags 0, QDCOUNT 1, everything else 0.
        assertEquals(1, ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF))
        assertEquals(0, ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF))

        // First label is "_adb-tls-connect" (16 bytes), then _tcp, then local.
        assertEquals(16, packet[12].toInt() and 0xFF)
        assertEquals("_adb-tls-connect", String(packet, 13, 16, Charsets.UTF_8))

        val nameBytes = Mdns.encodeName(Mdns.queryName(service)).size
        val typeAt = 12 + nameBytes
        val type = ((packet[typeAt].toInt() and 0xFF) shl 8) or (packet[typeAt + 1].toInt() and 0xFF)
        val qclass = ((packet[typeAt + 2].toInt() and 0xFF) shl 8) or (packet[typeAt + 3].toInt() and 0xFF)
        assertEquals(Mdns.TYPE_PTR, type)
        // The unicast-response bit is what makes the answer come to our port.
        assertEquals(Mdns.CLASS_IN or Mdns.QU_BIT, qclass)
        assertEquals(Mdns.CLASS_IN, qclass and 0x7FFF)
    }

    @Test
    fun theQuestionNameGetsTheLocalSuffixOnce() {
        assertEquals("_adb-tls-connect._tcp.local", Mdns.queryName("_adb-tls-connect._tcp"))
        assertEquals("_adb-tls-connect._tcp.local", Mdns.queryName("_adb-tls-connect._tcp.local"))
        assertEquals("_adb-tls-connect._tcp.local", Mdns.queryName("_adb-tls-connect._tcp.local."))
    }

    @Test
    fun aFullAnswerYieldsInstanceTargetAddressAndPort() {
        val packet = response(
            ptr(instance),
            srv(instance, target, 41277),
            a(target, byteArrayOf(192.toByte(), 168.toByte(), 0, 108))
        )

        val answer = Mdns.parse(packet, packet.size, service)

        assertTrue(answer.found)
        assertEquals(41277, answer.port)
        assertEquals(target, answer.target)
        assertEquals("192.168.0.108", answer.address)
    }

    @Test
    fun anSrvWithoutAnAddressStillYieldsThePort() {
        // Real responders split SRV and A across packets; the caller falls back
        // to the local address rather than dropping a usable port.
        val packet = response(ptr(instance), srv(instance, target, 43133))

        val answer = Mdns.parse(packet, packet.size, service)

        assertTrue(answer.found)
        assertEquals(43133, answer.port)
        assertNull(answer.address)
    }

    @Test
    fun aPacketAboutAnotherServiceIsNotOurAnswer() {
        val other = "_googlecast._tcp.local"
        val packet = response(
            ptr(other, "_chromecast._googlecast._tcp.local"),
            srv("_chromecast._googlecast._tcp.local", "living-room.local", 8009)
        )

        val answer = Mdns.parse(packet, packet.size, service)

        // Reporting 8009 as our adb port would be the worst possible failure.
        assertFalse(answer.found)
        assertNull(answer.port)
    }

    @Test
    fun aCompressedOwnerNameIsFollowed() {
        // The SRV owner is written as a pointer back to the PTR target, the way
        // real responders compress repeated names. That target starts right
        // after the PTR record's own owner name and its 10 fixed fields.
        val ptrOwnerBytes = Mdns.encodeName(Mdns.queryName(service)).size
        val targetAt = 12 + ptrOwnerBytes + 10
        val packet = response(
            ptr(instance),
            srvAt(pointerTo(targetAt), target, 38181)
        )

        val answer = Mdns.parse(packet, packet.size, service)

        assertTrue(answer.found)
        assertEquals(38181, answer.port)
    }

    @Test
    fun aTruncatedPacketIsRejectedWithoutThrowing() {
        val full = response(ptr(instance), srv(instance, target, 41277))
        val cases = listOf(
            ByteArray(0),
            ByteArray(3),
            full.copyOf(full.size / 2),
            full.copyOf(full.size - 3)
        )

        for (packet in cases) {
            val answer = Mdns.parse(packet, packet.size, service)
            assertFalse(answer.found)
        }
    }

    @Test
    fun aPointerLoopTerminates() {
        // Owner name = pointer to itself: a hostile packet that must not hang
        // the poll thread.
        val broken = ByteArray(12 + 2)
        broken[4] = 1 // one answer
        broken[12] = 0xC0.toByte()
        broken[13] = 12

        val answer = Mdns.parse(broken, broken.size, service)
        assertFalse(answer.found)
    }

    @Test
    fun anOutOfRangePortIsNotAFinding() {
        val packet = response(ptr(instance), srv(instance, target, 0))
        val answer = Mdns.parse(packet, packet.size, service)
        assertFalse(answer.found)
    }

    // ------------------------------------------------------------- builders

    private fun response(vararg records: ByteArray): ByteArray {
        val out = ByteArray(12)
        out[2] = 0x84.toByte() // response, authoritative
        out[7] = records.size.toByte() // ANCOUNT
        return records.fold(out) { acc, record -> acc + record }
    }

    private fun ptr(name: String): ByteArray = ptr(service, name)

    private fun ptr(owner: String, target: String): ByteArray {
        val ownerBytes = Mdns.encodeName(Mdns.queryName(owner))
        val targetBytes = Mdns.encodeName(target)
        val out = ByteArray(ownerBytes.size + 10 + targetBytes.size)
        ownerBytes.copyInto(out, 0)
        var at = ownerBytes.size
        at = put16(out, at, Mdns.TYPE_PTR)
        at = put16(out, at, Mdns.CLASS_IN)
        at = put32(out, at, 120L)
        at = put16(out, at, targetBytes.size)
        targetBytes.copyInto(out, at)
        return out
    }

    private fun srv(owner: String, target: String, port: Int): ByteArray =
        srvAt(Mdns.encodeName(owner), target, port)

    private fun srvAt(ownerBytes: ByteArray, target: String, port: Int): ByteArray {
        val targetBytes = Mdns.encodeName(target)
        val out = ByteArray(ownerBytes.size + 10 + 6 + targetBytes.size)
        ownerBytes.copyInto(out, 0)
        var at = ownerBytes.size
        at = put16(out, at, Mdns.TYPE_SRV)
        at = put16(out, at, Mdns.CLASS_IN)
        at = put32(out, at, 120L)
        at = put16(out, at, 6 + targetBytes.size)
        at = put16(out, at, 0) // priority
        at = put16(out, at, 0) // weight
        at = put16(out, at, port)
        targetBytes.copyInto(out, at)
        return out
    }

    private fun a(owner: String, address: ByteArray): ByteArray {
        val ownerBytes = Mdns.encodeName(owner)
        val out = ByteArray(ownerBytes.size + 10 + 4)
        ownerBytes.copyInto(out, 0)
        var at = ownerBytes.size
        at = put16(out, at, Mdns.TYPE_A)
        at = put16(out, at, Mdns.CLASS_IN)
        at = put32(out, at, 120L)
        at = put16(out, at, 4)
        address.copyInto(out, at)
        return out
    }

    /** A two-byte DNS compression pointer to [offset]. */
    private fun pointerTo(offset: Int): ByteArray =
        byteArrayOf((0xC0 or (offset shr 8)).toByte(), (offset and 0xFF).toByte())

    private fun put16(out: ByteArray, at: Int, value: Int): Int {
        out[at] = (value shr 8).toByte()
        out[at + 1] = value.toByte()
        return at + 2
    }

    private fun put32(out: ByteArray, at: Int, value: Long): Int {
        out[at] = (value shr 24).toByte()
        out[at + 1] = (value shr 16).toByte()
        out[at + 2] = (value shr 8).toByte()
        out[at + 3] = value.toByte()
        return at + 4
    }
}
