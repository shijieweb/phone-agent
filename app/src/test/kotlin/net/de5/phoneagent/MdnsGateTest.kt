package net.de5.phoneagent

import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住平板 Lenovo TB-J607F 上那个真机缺陷：mDNS 广播是**局域网**共享的，
 * 平板拿到的是手机的 192.168.0.106:40157，于是 shconnect 稳定超时 exit=4，
 * 而它自己真正的端口 192.168.0.108:41081 就在同一批广播里。
 *
 * 这里只测决策本身（纯 JVM）；两道闸接进发现流程的效果靠 manualcheck 的真机项。
 */
class MdnsGateTest {

    @Test
    fun ourOwnAddressIsRecognisedAsOurs() {
        assertEquals(
            MdnsGate.Verdict.SELF,
            MdnsGate.addressVerdict("192.168.0.108", "192.168.0.108")
        )
    }

    @Test
    fun thePhoneNextDoorIsRejected() {
        // 这就是真机上发生的那一对：平板 .108 收到了手机 .106 的端口。
        assertEquals(
            MdnsGate.Verdict.OTHER,
            MdnsGate.addressVerdict("192.168.0.108", "192.168.0.106")
        )
    }

    @Test
    fun anUnknownLocalAddressFallsBackToTheLoopbackProbe() {
        assertEquals(
            MdnsGate.Verdict.UNVERIFIED,
            MdnsGate.addressVerdict(null, "192.168.0.106")
        )
        assertEquals(
            MdnsGate.Verdict.UNVERIFIED,
            MdnsGate.addressVerdict("", "192.168.0.106")
        )
    }

    @Test
    fun aBroadcastWithoutAnAddressIsNotJudgedByAddress() {
        assertEquals(
            MdnsGate.Verdict.UNVERIFIED,
            MdnsGate.addressVerdict("192.168.0.108", null)
        )
    }

    @Test
    fun loopbackCountsAsUsEvenWhenItIsNotTheWlanAddress() {
        assertEquals(MdnsGate.Verdict.SELF, MdnsGate.addressVerdict("192.168.0.108", "127.0.0.1"))
        assertEquals(MdnsGate.Verdict.SELF, MdnsGate.addressVerdict("192.168.0.108", "::1"))
    }

    @Test
    fun aForeignAddressIsRejectedWithoutTouchingTheNetwork() {
        // 端口故意选一个本机绝不可能在听的号：判定必须是「地址不对」，
        // 而不是「回环连不上」——地址能判的时候不该再花一次 TCP 往返。
        val decision = MdnsGate.check("192.168.0.108", "192.168.0.106", 40157)
        assertFalse(decision.pass)
        val why = decision.rejected
        assertNotNull(why)
        assertTrue(why!!.contains("192.168.0.106:40157"))
        assertTrue(why.contains("不是本机地址"))
    }

    @Test
    fun ourOwnAddressPassesEvenIfNothingListensThere() {
        val decision = MdnsGate.check("192.168.0.108", "192.168.0.108", 41081)
        assertTrue(decision.pass)
        assertNull(decision.rejected)
    }

    @Test
    fun anUnknownAddressIsSettledByTheLoopbackProbe() {
        // backlog 要给够：这个 server 从不 accept，闸会来连两次（第一次是
        // loopbackOpen 的直调，第二次是 check 里那道闸），backlog=1 时第二次
        // 会被拒——那是测试的错，不是闸的错。
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        try {
            val open = server.localPort
            assertTrue(MdnsGate.loopbackOpen(open))
            assertTrue(MdnsGate.check(null, null, open).pass)
            // 地址未知但回环上没人听：只能丢。
            val dead = freePort()
            assertFalse(MdnsGate.loopbackOpen(dead))
            val decision = MdnsGate.check(null, "192.168.0.106", dead)
            assertFalse(decision.pass)
            assertTrue(decision.rejected!!.contains("回环上连不上"))
        } finally {
            server.close()
        }
    }

    @Test
    fun aNonsensePortNeverPasses() {
        assertFalse(MdnsGate.check("192.168.0.108", "192.168.0.108", 0).pass)
        assertFalse(MdnsGate.check("192.168.0.108", "192.168.0.108", 70000).pass)
    }

    @Test
    fun theIgnoredLineSaysHowManyAndNamesThem() {
        val line = MdnsGate.ignoredLine(listOf("192.168.0.106:40157", "192.168.0.109:1234"))
        assertNotNull(line)
        assertTrue(line!!.startsWith("已忽略 2 个其它设备的广播"))
        assertTrue(line.contains("192.168.0.106:40157"))
        assertTrue(line.contains("192.168.0.109:1234"))
        // 样例最多两个：报错行要能一屏读完。
        val many = MdnsGate.ignoredLine(listOf("a:1", "b:2", "c:3"))!!
        assertTrue(many.startsWith("已忽略 3 个"))
        assertTrue(many.contains("a:1"))
        assertFalse(many.contains("c:3"))
        assertTrue(many.contains("等"))
    }

    @Test
    fun nothingIgnoredMeansNoLineAtAll() {
        assertNull(MdnsGate.ignoredLine(emptyList()))
    }

    @Test
    fun theNoPortTextCarriesTheIgnoredCount() {
        val plain = SelfAdbPlan.noPort(SelfAdbPlan.SERVICE_CONNECT)
        assertFalse(plain.contains("已忽略"))
        val withIgnored = SelfAdbPlan.noPort(
            SelfAdbPlan.SERVICE_CONNECT,
            listOf("192.168.0.106:40157")
        )
        assertTrue(withIgnored.startsWith(plain))
        assertTrue(withIgnored.contains("已忽略 1 个其它设备的广播"))
        assertTrue(withIgnored.contains("192.168.0.106:40157"))
    }

    /** 拿一个「刚刚还空着」的端口号：绑定后立刻释放，连上去必然被拒。 */
    private fun freePort(): Int {
        val probe = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = probe.localPort
        probe.close()
        return port
    }
}
