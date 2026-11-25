package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `sh` / `shinfo` / `shpair` / `shconnect` / `shkey` 的语法。
 *
 * 这些断言守的是「命令语义不能漂」：载荷逐字保留、flag 只吃前导、
 * 非法参数回 usage 而不是猜一个默认值。
 */
class ShCommandsTest {

    @Test
    fun shInfoParses() {
        assertTrue(Commands.parse("shinfo") is Command.ShInfo)
        assertTrue(Commands.parse("SHINFO") is Command.ShInfo)
    }

    @Test
    fun shInfoRejectsArguments() {
        assertTrue(Commands.parse("shinfo now") is Command.BadUsage)
    }

    @Test
    fun shDefaultsToDryRun() {
        val command = Commands.parse("sh ls /sdcard") as Command.Sh
        assertEquals("ls /sdcard", command.command)
        assertFalse(command.apply)
        assertEquals(SelfAdbPlan.SHELL_TIMEOUT_DEFAULT_MS, command.timeoutMs)
    }

    @Test
    fun shApplyFlag() {
        val command = Commands.parse("sh apply ls /sdcard") as Command.Sh
        assertEquals("ls /sdcard", command.command)
        assertTrue(command.apply)
    }

    @Test
    fun shTimeoutFlag() {
        val command = Commands.parse("sh t=90 apply id") as Command.Sh
        assertEquals(90_000L, command.timeoutMs)
        assertTrue(command.apply)
    }

    @Test
    fun shTimeoutClamped() {
        assertEquals(
            SelfAdbPlan.SHELL_TIMEOUT_MAX_MS,
            (Commands.parse("sh t=99999 apply id") as Command.Sh).timeoutMs
        )
        assertEquals(
            SelfAdbPlan.SHELL_TIMEOUT_MIN_MS,
            (Commands.parse("sh t=1 apply id") as Command.Sh).timeoutMs
        )
    }

    /** 载荷逐字保留：多空格、引号、管道都不能被拆词吃掉。 */
    @Test
    fun shKeepsPayloadVerbatim() {
        val command = Commands.parse("sh apply echo a  b") as Command.Sh
        assertEquals("echo a  b", command.command)

        val quoted = Commands.parse("sh apply sh -c 'echo hi | cat'") as Command.Sh
        assertEquals("sh -c 'echo hi | cat'", quoted.command)
    }

    /** flag 只吃前导：出现在载荷里的 apply 属于命令本身。 */
    @Test
    fun shApplyInsidePayloadIsNotAFlag() {
        val command = Commands.parse("sh echo apply") as Command.Sh
        assertEquals("echo apply", command.command)
        assertFalse(command.apply)
    }

    @Test
    fun shRejectsBadTimeout() {
        assertTrue(Commands.parse("sh t=abc apply id") is Command.BadUsage)
        assertTrue(Commands.parse("sh t=") is Command.BadUsage)
    }

    @Test
    fun shRejectsEmpty() {
        assertTrue(Commands.parse("sh") is Command.BadUsage)
        assertTrue(Commands.parse("sh   ") is Command.BadUsage)
        assertTrue(Commands.parse("sh apply") is Command.BadUsage)
    }

    @Test
    fun shPairParsesSixDigits() {
        val command = Commands.parse("shpair 123456") as Command.ShPair
        assertEquals("123456", command.code)
    }

    @Test
    fun shPairRejectsBadCode() {
        assertTrue(Commands.parse("shpair 12345") is Command.BadUsage)
        assertTrue(Commands.parse("shpair abcdef") is Command.BadUsage)
        assertTrue(Commands.parse("shpair 123456 654321") is Command.BadUsage)
        assertTrue(Commands.parse("shpair") is Command.BadUsage)
    }

    @Test
    fun shConnectParses() {
        assertTrue(Commands.parse("shconnect") is Command.ShConnect)
        assertTrue(Commands.parse("shconnect now") is Command.BadUsage)
    }

    @Test
    fun shKeyBareReports() {
        val command = Commands.parse("shkey") as Command.ShKey
        assertFalse(command.regen)
        assertFalse(command.apply)
    }

    /** regen 单独出现只是 dry run——换密钥会让配对失效。 */
    @Test
    fun shKeyRegenIsDryRunByDefault() {
        val command = Commands.parse("shkey regen") as Command.ShKey
        assertTrue(command.regen)
        assertFalse(command.apply)
    }

    @Test
    fun shKeyRegenApply() {
        val command = Commands.parse("shkey regen apply") as Command.ShKey
        assertTrue(command.regen)
        assertTrue(command.apply)
    }

    // ------------------------------------------------------------ watchdog

    @Test
    fun watchdogDefaultsToStatus() {
        assertEquals(Command.Watchdog("status"), Commands.parse("watchdog"))
        assertEquals(Command.Watchdog("status"), Commands.parse("watchdog status"))
    }

    @Test
    fun watchdogParsesArmAndDisarm() {
        assertEquals(Command.Watchdog("arm"), Commands.parse("watchdog arm"))
        assertEquals(Command.Watchdog("disarm"), Commands.parse("watchdog disarm"))
        // 大小写不敏感，和其它命令一致。
        assertEquals(Command.Watchdog("arm"), Commands.parse("WATCHDOG Arm"))
    }

    @Test
    fun watchdogRejectsGarbage() {
        assertTrue(Commands.parse("watchdog restart") is Command.BadUsage)
        assertTrue(Commands.parse("watchdog arm now") is Command.BadUsage)
        assertTrue(Commands.parse("watchdog status disarm") is Command.BadUsage)
    }

    @Test
    fun shKeyRejectsGarbage() {
        assertTrue(Commands.parse("shkey rotate") is Command.BadUsage)
        assertTrue(Commands.parse("shkey regen maybe") is Command.BadUsage)
        assertTrue(Commands.parse("shkey regen apply now") is Command.BadUsage)
    }
}
