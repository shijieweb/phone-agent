package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mp3 命令的解析（v0.7.0-pad12）。
 *
 * 为什么值得单独一组：这条命令会动用户的视频文件。解析层多认一个词、少认一个词，
 * 后果不是「报错」而是「悄悄转了不该转的东西」，所以每种写法都要钉死——
 * 尤其是 dry-run 与 apply 的分界，以及 scan 不接受 apply。
 */
class Mp3CommandsTest {

    @Test
    fun bareMp3IsUsage() {
        val parsed = Commands.parse("mp3")
        assertTrue(parsed is Command.BadUsage)
        assertEquals(Mp3Plan.USAGE, (parsed as Command.BadUsage).detail)
    }

    @Test
    fun scanDefaultsToTheOneFolder() {
        val parsed = Commands.parse("mp3 scan")
        assertTrue(parsed is Command.Mp3Scan)
        assertEquals(Mp3Plan.DEFAULT_DIR, (parsed as Command.Mp3Scan).dir)
    }

    @Test
    fun scanTakesAnExplicitFolder() {
        val parsed = Commands.parse("mp3 scan /sdcard/Movies")
        assertEquals("/sdcard/Movies", (parsed as Command.Mp3Scan).dir)
    }

    @Test
    fun scanRefusesApplyBecauseItNeverWrites() {
        // 记混命令时宁可报用法：默默忽略 apply 会让人以为文件已经转了。
        assertTrue(Commands.parse("mp3 scan apply") is Command.BadUsage)
        assertTrue(Commands.parse("mp3 scan force") is Command.BadUsage)
    }

    @Test
    fun runIsDryByDefault() {
        val parsed = Commands.parse("mp3 run") as Command.Mp3Run
        assertEquals(false, parsed.apply)
        assertEquals(false, parsed.force)
        assertEquals(Mp3Plan.DEFAULT_DIR, parsed.dir)
    }

    @Test
    fun runApplyTurnsTheSwitchOn() {
        val parsed = Commands.parse("mp3 run apply") as Command.Mp3Run
        assertEquals(true, parsed.apply)
        assertEquals(false, parsed.force)
    }

    @Test
    fun forceIsAcceptedInEitherOrder() {
        val first = Commands.parse("mp3 run apply force") as Command.Mp3Run
        assertEquals(true, first.apply)
        assertEquals(true, first.force)
        val second = Commands.parse("mp3 run force apply") as Command.Mp3Run
        assertEquals(true, second.apply)
        assertEquals(true, second.force)
    }

    @Test
    fun forceWithoutApplyStillOnlyReports() {
        val parsed = Commands.parse("mp3 run force") as Command.Mp3Run
        assertEquals(false, parsed.apply)
        assertEquals(true, parsed.force)
    }

    @Test
    fun folderCanFollowTheSwitches() {
        val parsed = Commands.parse("mp3 run apply /sdcard/x")
        assertEquals(true, (parsed as Command.Mp3Run).apply)
        assertEquals("/sdcard/x", parsed.dir)
    }

    @Test
    fun twoFoldersIsUsage() {
        assertTrue(Commands.parse("mp3 run apply /a /b") is Command.BadUsage)
    }

    @Test
    fun unknownVerbIsUsage() {
        assertTrue(Commands.parse("mp3 convert") is Command.BadUsage)
        // 第一个词不是子命令时也不猜成目录：打错一个词就该看到用法。
        assertTrue(Commands.parse("mp3 /sdcard/x") is Command.BadUsage)
    }

    @Test
    fun verbIsCaseInsensitive() {
        assertTrue(Commands.parse("mp3 SCAN") is Command.Mp3Scan)
        assertEquals(true, (Commands.parse("mp3 RUN APPLY") as Command.Mp3Run).apply)
    }

    @Test
    fun usageMentionsBothVerbs() {
        assertTrue(Mp3Plan.USAGE.contains("mp3 scan"))
        assertTrue(Mp3Plan.USAGE.contains("mp3 run"))
        assertTrue(Mp3Plan.USAGE.contains("apply"))
    }
}
