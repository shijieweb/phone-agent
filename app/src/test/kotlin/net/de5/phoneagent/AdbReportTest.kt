package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of wireless-debugging discovery that can be checked without a
 * device: the connect string, the cache-freshness rule, and the report layout.
 *
 * The mDNS round itself is not unit-testable (it needs NsdManager), which is
 * exactly why all of its output is funnelled through [AdbReport].
 */
class AdbReportTest {

    @Test
    fun connectStringNeedsAPort() {
        assertEquals("192.168.0.108:38181", AdbReport.connectString("192.168.0.108", 38181))
        // No port means no address: a bare IP would be a lie about where to connect.
        assertNull(AdbReport.connectString("192.168.0.108", null))
        assertNull(AdbReport.connectString("192.168.0.108", 0))
        assertNull(AdbReport.connectString("192.168.0.108", 70000))
    }

    @Test
    fun anUnknownIpIsSpelledOutRatherThanInvented() {
        assertEquals("未取到本机 IP:38181", AdbReport.connectString(null, 38181))
    }

    @Test
    fun theCacheIsFreshForTenMinutesOnly() {
        val at = 1_000_000L
        assertTrue(AdbReport.isFresh(at, at))
        assertTrue(AdbReport.isFresh(at, at + AdbReport.PORT_CACHE_TTL_MS - 1))
        assertFalse(AdbReport.isFresh(at, at + AdbReport.PORT_CACHE_TTL_MS))
        assertFalse(AdbReport.isFresh(at, at + 60L * 60L * 1000L))
        // Never discovered, or a clock that went backwards: both are "unknown".
        assertFalse(AdbReport.isFresh(0L, at))
        assertFalse(AdbReport.isFresh(at + 10_000L, at))
    }

    @Test
    fun theHeartbeatFieldDisappearsWhenThePortGoesStale() {
        val at = 2_000_000L
        assertEquals("adbwifi=38181", AdbReport.envField(38181, at, at + 1000L))
        // The relay must never see a port that has already moved.
        assertNull(AdbReport.envField(38181, at, at + AdbReport.PORT_CACHE_TTL_MS))
        assertNull(AdbReport.envField(0, at, at))
        assertNull(AdbReport.envField(70000, at, at))
    }

    @Test
    fun theReportNeverPrintsAPortThatWasNotDiscovered() {
        val text = AdbReport.format(
            AdbWifiInfo(
                enabledValue = "1",
                localIp = "192.168.0.108",
                port = null,
                source = null,
                note = "未发现 _adb-tls-connect 广播",
                tcpOk = null
            )
        )
        assertTrue(text.contains("adb_wifi_enabled = 1"))
        assertTrue(text.contains("本机 IP = 192.168.0.108"))
        assertTrue(text.contains("无线调试端口 = 未发现"))
        assertTrue(text.contains("说明 = 未发现 _adb-tls-connect 广播"))
        assertTrue(text.contains("connect = 无（未发现端口）"))
        assertTrue(text.contains("TCP 探测 = 未做"))
        // The point of the whole command: no stray ":0" or made-up number.
        assertFalse(text.contains(":0"))
    }

    @Test
    fun aDiscoveredPortIsReportedWithItsSourceAndProbe() {
        val text = AdbReport.format(
            AdbWifiInfo(
                enabledValue = "1",
                localIp = "192.168.0.108",
                port = 38181,
                source = AdbReport.SERVICE_TYPE,
                note = null,
                tcpOk = true
            )
        )
        assertTrue(text.contains("无线调试端口 = 38181（来源 _adb-tls-connect._tcp）"))
        assertTrue(text.contains("connect = 192.168.0.108:38181"))
        assertTrue(text.contains("TCP 探测 = 可连"))
    }

    @Test
    fun anUnreadableEnabledValueIsReportedAsNull() {
        val text = AdbReport.format(
            AdbWifiInfo(null, null, null, null, null, null)
        )
        assertTrue(text.contains("adb_wifi_enabled = (null)"))
        assertTrue(text.contains("本机 IP = 未取到本机 IP"))
        assertTrue(text.contains("connect = 无（未发现端口）"))
    }

    @Test
    fun aCachedPortSaysHowOldItIs() {
        val at = 3_000_000L
        assertEquals(0L, AdbReport.cacheAgeSeconds(at, at))
        assertEquals(42L, AdbReport.cacheAgeSeconds(at, at + 42_000L))
        // Never discovered, or a clock that went backwards: not an age at all.
        assertNull(AdbReport.cacheAgeSeconds(0L, at))
        assertNull(AdbReport.cacheAgeSeconds(at + 5_000L, at))
        assertEquals("缓存（42s 前发现）", AdbReport.cacheSource(42L))
    }

    @Test
    fun bothTiersFailingNamesBothTiers() {
        val note = AdbReport.twoTierNote("在 1995ms 内没有解析到广播", "裸 mDNS 查询 2500ms 内没有收到应答")
        // Losing which tier failed would make the operator debug the wrong layer.
        assertTrue(note.startsWith("NsdManager 未发现"))
        assertTrue(note.contains("1995ms"))
        assertTrue(note.contains("裸 mDNS 查询也未收到应答"))
        assertTrue(note.contains("2500ms"))
    }

    @Test
    fun anEmptyTierNoteStillProducesAReadableSentence() {
        val note = AdbReport.twoTierNote(null, null)
        assertEquals("NsdManager 未发现，裸 mDNS 查询也未收到应答", note)
    }

    @Test
    fun aStaleCacheTellsTheOperatorToHeal() {
        val hint = AdbReport.staleHint(700L)
        assertTrue(hint.contains("700s"))
        assertTrue(hint.contains("adbheal"))
        assertTrue(AdbReport.staleHint(null).contains("adbheal"))
    }

    @Test
    fun theTwoSourceLabelsAreDistinguishable() {
        assertTrue(AdbReport.SOURCE_NSD.contains("NsdManager"))
        assertTrue(AdbReport.SOURCE_RAW.contains("裸 mDNS"))
        // A cached answer must never wear a freshly-measured label.
        assertFalse(AdbReport.cacheSource(1L).contains("_tcp"))
    }

    @Test
    fun aFailedProbeIsDistinguishableFromNoProbe() {
        val text = AdbReport.format(
            AdbWifiInfo("1", "10.0.0.5", 43133, AdbReport.SERVICE_TYPE, null, false)
        )
        // "broadcast found but nothing listening" is a different problem from
        // "no broadcast at all", and the operator has to be able to tell.
        assertTrue(text.contains("TCP 探测 = 不可连"))
        assertTrue(text.contains("43133"))
    }
}
