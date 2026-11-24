package net.de5.phoneagent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two unattended paths (boot heal, heartbeat observation) share one rule set;
 * these tests pin the rules, not the Android plumbing.
 */
class AdbUnattendedTest {

    @Test
    fun bootTurnsTheDaemonOnOnlyWhenItIsOff() {
        // "1" is a running daemon: writing again would bounce it for nothing.
        assertFalse(AdbReport.bootShouldEnable("1"))
        // Anything else - off, or unreadable - is worth one write.
        assertTrue(AdbReport.bootShouldEnable("0"))
        assertTrue(AdbReport.bootShouldEnable(null))
        assertTrue(AdbReport.bootShouldEnable(""))
    }

    @Test
    fun anEmptyCacheIsAlwaysDue() {
        assertTrue(AdbReport.observeDue(0L, 1_000_000L))
    }

    @Test
    fun theHeartbeatWaitsThirtyMinutesNotTen() {
        val at = 1_000_000L
        // 10 minutes is when the field disappears; it is not yet worth a query.
        assertFalse(AdbReport.observeDue(at, at + 10 * 60 * 1000L))
        assertFalse(AdbReport.observeDue(at, at + 29 * 60 * 1000L))
        assertTrue(AdbReport.observeDue(at, at + 30 * 60 * 1000L))
        assertTrue(AdbReport.observeDue(at, at + 31 * 60 * 1000L))
    }

    @Test
    fun aClockThatWentBackwardsDoesNotTriggerAQuery() {
        // NTP correction or a timezone-adjacent jump must not look like staleness.
        val now = 1_000_000L
        assertFalse(AdbReport.observeDue(now + 60_000L, now))
    }

    @Test
    fun theObserveWindowIsLongerThanTheCacheTtl() {
        // If these ever crossed, the heartbeat would re-discover the moment the
        // field vanished, on every beat.
        assertTrue(AdbReport.OBSERVE_STALE_MS > AdbReport.PORT_CACHE_TTL_MS)
    }
}
