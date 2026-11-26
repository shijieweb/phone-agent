package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `battery` (v0.8.0-pad12, SPEC.md §3.24).
 *
 * The read half needs no shell at all; the write half must stay dry-run until
 * `apply` is typed, and must say "shconnect" rather than just failing when the
 * self-ADB link is down.
 */
class BatteryPlanTest {

    @Test
    fun noArgumentsIsTheReadRequest() {
        val request = BatteryPlan.parse("")!!
        assertTrue(request.read)
        assertTrue(!request.apply)
        assertTrue(BatteryPlan.parse("   ")!!.read)
    }

    @Test
    fun allowAndDenyWithoutApplyAreDryRuns() {
        val allow = BatteryPlan.parse("allow")!!
        assertEquals(false, allow.read)
        assertEquals(true, allow.allow)
        assertEquals(false, allow.apply)

        val deny = BatteryPlan.parse("deny")!!
        assertEquals(false, deny.allow)
        assertEquals(false, deny.apply)
    }

    @Test
    fun applyIsAcceptedInAnyCase() {
        assertTrue(BatteryPlan.parse("allow apply")!!.apply)
        assertTrue(BatteryPlan.parse("deny APPLY")!!.apply)
    }

    @Test
    fun anythingElseIsRefused() {
        assertNull(BatteryPlan.parse("maybe"))
        assertNull(BatteryPlan.parse("allow later"))
        assertNull(BatteryPlan.parse("allow apply now"))
        assertNull(BatteryPlan.parse("allow apply apply"))
    }

    @Test
    fun theCommandTargetsOurOwnPackage() {
        val allow = BatteryPlan.parse("allow")!!
        val deny = BatteryPlan.parse("deny")!!
        assertEquals("cmd deviceidle whitelist +net.de5.phoneagent", BatteryPlan.command(allow))
        assertEquals("cmd deviceidle whitelist -net.de5.phoneagent", BatteryPlan.command(deny))
        assertEquals("net.de5.phoneagent", BatteryPlan.PACKAGE)
    }

    @Test
    fun normalizeRoundTripsThroughParse() {
        assertEquals("", BatteryPlan.normalize(BatteryPlan.parse("")!!))
        assertEquals("allow", BatteryPlan.normalize(BatteryPlan.parse("allow")!!))
        assertEquals("deny apply", BatteryPlan.normalize(BatteryPlan.parse("deny apply")!!))
        // Parsing the normalised form again yields the same request.
        val once = BatteryPlan.parse("allow apply")!!
        val twice = BatteryPlan.parse(BatteryPlan.normalize(once))!!
        assertEquals(BatteryPlan.command(once), BatteryPlan.command(twice))
        assertEquals(once.apply, twice.apply)
    }

    @Test
    fun theReadLineIsTheDocumentedWord() {
        assertEquals("battery whitelisted", BatteryPlan.readLine(true))
        assertEquals("battery not-whitelisted", BatteryPlan.readLine(false))
    }

    @Test
    fun theDryRunLineNamesTheExactShellCommand() {
        val line = BatteryPlan.dryRunLine("cmd deviceidle whitelist +net.de5.phoneagent")
        assertTrue(line.startsWith("dry-run: "))
        assertTrue(line.contains("cmd deviceidle whitelist +net.de5.phoneagent"))
    }

    @Test
    fun aMissingShellIsReportedAsShconnectNotAsAMystery() {
        val text = BatteryPlan.needShell()
        assertTrue(text.startsWith(Admin.ERROR_PREFIX))
        assertTrue(text.contains("shconnect"))
        assertTrue(text.contains("uid 2000"))
    }

    @Test
    fun aFailedWriteKeepsTheExitCodeAndTheDeviceText() {
        val text = BatteryPlan.failedLine("cmd deviceidle whitelist +net.de5.phoneagent", 255, "  Operation not allowed ")
        assertTrue(text.startsWith(Admin.ERROR_PREFIX))
        assertTrue(text.contains("255"))
        assertTrue(text.contains("Operation not allowed"))
    }

    @Test
    fun theAppliedLinesSayWhichWayItWent() {
        assertTrue(BatteryPlan.appliedLine(true, false).contains("加入"))
        assertTrue(BatteryPlan.appliedLine(false, true).contains("移出"))
    }

    /**
     * v0.8.1: 本来就在白名单里时不能说"已加入"。
     *
     * 真机上 `battery` 读是 whitelisted（手机本来就在），`battery allow apply`
     * 却回"已加入"，操作员看不出这条命令到底干没干活。
     */
    @Test
    fun anAlreadySatisfiedStateIsReportedAsUnchanged() {
        val already = BatteryPlan.appliedLine(true, true)
        assertTrue(already.contains("本来就在"))
        assertTrue(already.contains("未改动"))
        assertTrue(!already.contains("已加入"))

        val never = BatteryPlan.appliedLine(false, false)
        assertTrue(never.contains("本来就不在"))
        assertTrue(never.contains("未改动"))
        assertTrue(!never.contains("已移出"))
    }

    @Test
    fun anUnknownBeforeStateKeepsTheOldWording() {
        // 读不到现状（Context 异常）时不许猜，退回 v0.8.0 的说法。
        assertEquals("battery 已加入电池优化白名单", BatteryPlan.appliedLine(true, null))
        assertEquals("battery 已移出电池优化白名单", BatteryPlan.appliedLine(false, null))
        assertEquals(BatteryPlan.appliedLine(true), BatteryPlan.appliedLine(true, null))
    }
}
