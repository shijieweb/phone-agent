package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `shkey` 家族在 v0.5.1 多出 show / import / adopt 三个动词。 */
class ShKeyCommandsTest {

    private fun parse(line: String): Command = Commands.parse(line)

    @Test
    fun showParses() {
        assertTrue(parse("shkey show") is Command.ShKeyShow)
    }

    @Test
    fun showRejectsArguments() {
        assertTrue(parse("shkey show now") is Command.BadUsage)
    }

    @Test
    fun adoptParses() {
        assertTrue(parse("shkey adopt") is Command.ShKeyAdopt)
    }

    @Test
    fun adoptRejectsArguments() {
        assertTrue(parse("shkey adopt yes") is Command.BadUsage)
    }

    @Test
    fun importTakesOneUrl() {
        val command = parse("shkey import http://43.166.6.110/dl/t/adbkey") as Command.ShKeyImport
        assertEquals("http://43.166.6.110/dl/t/adbkey", command.pemUrl)
        assertNull(command.pubUrl)
    }

    @Test
    fun importTakesOptionalPubUrl() {
        val command = parse(
            "shkey import https://example.com/adbkey https://example.com/adbkey.pub"
        ) as Command.ShKeyImport
        assertEquals("https://example.com/adbkey", command.pemUrl)
        assertEquals("https://example.com/adbkey.pub", command.pubUrl)
    }

    @Test
    fun importRejectsNonHttpUrl() {
        assertTrue(parse("shkey import file:///sdcard/adbkey") is Command.BadUsage)
        assertTrue(parse("shkey import 43.166.6.110/adbkey") is Command.BadUsage)
    }

    @Test
    fun importRejectsTooManyArguments() {
        assertTrue(
            parse("shkey import https://a/x https://a/x.pub extra") is Command.BadUsage
        )
    }

    @Test
    fun importRejectsMissingUrl() {
        assertTrue(parse("shkey import") is Command.BadUsage)
    }

    @Test
    fun bareShkeyStillReports() {
        val command = parse("shkey") as Command.ShKey
        assertEquals(false, command.regen)
        assertEquals(false, command.apply)
    }

    @Test
    fun regenStillWorks() {
        val command = parse("shkey regen apply") as Command.ShKey
        assertEquals(true, command.regen)
        assertEquals(true, command.apply)
    }

    @Test
    fun usageMentionsEveryVerb() {
        val usage = (parse("shkey nonsense") as Command.BadUsage).detail
        assertTrue(usage.contains("show"))
        assertTrue(usage.contains("regen"))
        assertTrue(usage.contains("import"))
        assertTrue(usage.contains("adopt"))
    }
}