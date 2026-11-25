package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `sh` 家族的纯逻辑：哨兵解析、超时钳制、文案、base64。
 *
 * 真机上最难查的就是这些字符串处理——TLS 失败会给一句明确的异常，
 * 而哨兵剥错只会让操作员看到一条「exit=0」的假成功。
 */
class SelfAdbPlanTest {

    // ------------------------------------------------------------ 哨兵

    @Test
    fun shellCommandAppendsSentinel() {
        assertEquals("ls /sdcard\necho __RC=$?", SelfAdbPlan.shellCommand("ls /sdcard"))
    }

    /** 正常退出：剥出 0，正文保留换行。 */
    @Test
    fun parseExitCleanZero() {
        val exit = SelfAdbPlan.parseExit("a\nb\n__RC=0\n")
        assertEquals(0, exit.code)
        assertEquals("a\nb\n", exit.text)
    }

    /** 非 0 退出：adb shell 协议不带退出码，全靠这一行。 */
    @Test
    fun parseExitNonZero() {
        val exit = SelfAdbPlan.parseExit("ls: /nope: No such file or directory\n__RC=1\n")
        assertEquals(1, exit.code)
        assertEquals("ls: /nope: No such file or directory\n", exit.text)
    }

    /**
     * 命令文本里自带 `__RC=` 字样：只认最后一次。
     *
     * 这条防的是「echo 一行日志里正好含 __RC=」被当成退出码——
     * 那会让一条失败的命令显示成功。
     */
    @Test
    fun parseExitTakesLastSentinel() {
        val exit = SelfAdbPlan.parseExit("log __RC=9 here\n__RC=3\n")
        assertEquals(3, exit.code)
        assertEquals("log __RC=9 here\n", exit.text)
    }

    /** 哨兵后面还有别的输出 = 流被截断，宁可回 null 也不猜退出码。 */
    @Test
    fun parseExitRejectsTrailingOutput() {
        val exit = SelfAdbPlan.parseExit("__RC=0\ntrailing noise\n")
        assertNull(exit.code)
        assertEquals("__RC=0\ntrailing noise\n", exit.text)
    }

    @Test
    fun parseExitWithoutSentinel() {
        val exit = SelfAdbPlan.parseExit("just text")
        assertNull(exit.code)
        assertEquals("just text", exit.text)
    }

    /** CRLF 归一：部分 shell 会带 \r，不归一化会让 sentinel 匹配不上。 */
    @Test
    fun parseExitNormalizesCrlf() {
        val exit = SelfAdbPlan.parseExit("a\r\nb\r\n__RC=0\r\n")
        assertEquals(0, exit.code)
        assertEquals("a\nb\n", exit.text)
    }

    @Test
    fun parseExitIgnoresNonDigitSentinel() {
        val exit = SelfAdbPlan.parseExit("__RC=\n")
        assertNull(exit.code)
    }

    // ------------------------------------------------------------ 超时

    @Test
    fun parseTimeoutAcceptsSeconds() {
        assertEquals(60_000L, SelfAdbPlan.parseTimeout("t=60"))
    }

    /** 非法值返回 null（调用方回 usage），绝不悄悄用默认值。 */
    @Test
    fun parseTimeoutRejectsGarbage() {
        assertNull(SelfAdbPlan.parseTimeout("t=abc"))
        assertNull(SelfAdbPlan.parseTimeout("t="))
        assertNull(SelfAdbPlan.parseTimeout("t=0"))
        assertNull(SelfAdbPlan.parseTimeout("t=-5"))
        assertNull(SelfAdbPlan.parseTimeout("60"))
    }

    @Test
    fun clampTimeoutBounds() {
        assertEquals(SelfAdbPlan.SHELL_TIMEOUT_DEFAULT_MS, SelfAdbPlan.clampTimeout(null))
        assertEquals(SelfAdbPlan.SHELL_TIMEOUT_MIN_MS, SelfAdbPlan.clampTimeout(1L))
        assertEquals(SelfAdbPlan.SHELL_TIMEOUT_MAX_MS, SelfAdbPlan.clampTimeout(10_000_000L))
        assertEquals(45_000L, SelfAdbPlan.clampTimeout(45_000L))
    }

    // ------------------------------------------------------------ 配对码

    @Test
    fun validPairCode() {
        assertTrue(SelfAdbPlan.validPairCode("123456"))
        assertFalse(SelfAdbPlan.validPairCode("12345"))
        assertFalse(SelfAdbPlan.validPairCode("1234567"))
        assertFalse(SelfAdbPlan.validPairCode("12345a"))
        assertFalse(SelfAdbPlan.validPairCode(""))
    }

    // ------------------------------------------------------------ 输出

    @Test
    fun outputKeepsShortText() {
        assertEquals("hi", SelfAdbPlan.output("hi"))
    }

    @Test
    fun outputTruncatesAndSaysSo() {
        val long = "x".repeat(SelfAdbPlan.SHELL_OUT_MAX + 100)
        val clipped = SelfAdbPlan.output(long)
        assertTrue(clipped.length > SelfAdbPlan.SHELL_OUT_MAX)
        assertTrue(clipped.startsWith("x".repeat(SelfAdbPlan.SHELL_OUT_MAX)))
        assertTrue(clipped.contains("输出被截断"))
        assertTrue(clipped.contains((SelfAdbPlan.SHELL_OUT_MAX + 100).toString()))
    }

    @Test
    fun shellOkShapes() {
        assertEquals("exit=0", SelfAdbPlan.shellOk(0, ""))
        assertEquals("abc\nexit=0", SelfAdbPlan.shellOk(0, "abc\n"))
        assertEquals("abc\nexit=2", SelfAdbPlan.shellOk(2, "abc\n"))
    }

    @Test
    fun dryRunQuotesCommandVerbatim() {
        assertEquals("dry-run: would run sh: ls  -l /sdcard", SelfAdbPlan.dryRun("ls  -l /sdcard"))
    }

    // ------------------------------------------------------------ base64

    @Test
    fun base64RoundTrip() {
        val raw = byteArrayOf(0, 1, 2, 3, 0x7f, -1, -2)
        val encoded = SelfAdbPlan.base64(raw)
        val decoded = SelfAdbPlan.unbase64(encoded)
        assertTrue(decoded != null)
        assertEquals(raw.size, decoded!!.size)
        for (i in raw.indices) assertEquals(raw[i], decoded[i])
    }

    @Test
    fun base64KnownVector() {
        assertEquals("aGVsbG8=", SelfAdbPlan.base64("hello".toByteArray(Charsets.UTF_8)))
        assertEquals("aGVsbG8", SelfAdbPlan.base64("hello".toByteArray(Charsets.UTF_8), pad = false))
    }

    @Test
    fun unbase64RejectsGarbage() {
        assertNull(SelfAdbPlan.unbase64("!!!!"))
        assertNull(SelfAdbPlan.unbase64("a"))
    }

    /** 指纹格式与 AOSP adb keygen 一致：SHA256: + 无填充 base64。 */
    @Test
    fun fingerprintShape() {
        val text = SelfAdbPlan.fingerprint(ByteArray(32) { 0 })
        assertTrue(text.startsWith("SHA256:"))
        assertFalse(text.contains("="))
        assertEquals("SHA256:" + "A".repeat(43), text)
    }

    // ------------------------------------------------------------ 文案

    @Test
    fun keyReportShowsPlaceholdersWhenMissing() {
        val text = SelfAdbPlan.keyReport(null, null)
        assertTrue(text.contains("公钥: (未生成)"))
        assertTrue(text.contains("指纹: (未生成)"))
    }

    @Test
    fun keyReportShowsBothWhenPresent() {
        val text = SelfAdbPlan.keyReport("AAAABBBB phone-agent@dsh", "SHA256:xyz")
        assertTrue(text.contains("公钥: AAAABBBB phone-agent@dsh"))
        assertTrue(text.contains("指纹: SHA256:xyz"))
    }

    @Test
    fun regenDryRunWarnsAboutPairing() {
        assertTrue(SelfAdbPlan.regenDryRun().startsWith("dry-run: "))
        assertTrue(SelfAdbPlan.regenDryRun().contains("apply"))
    }

    @Test
    fun statusMentionsLoopbackOnly() {
        val text = SelfAdbPlan.status(false, null, false, null)
        assertTrue(text.contains("未连接"))
        assertTrue(text.contains("127.0.0.1"))
        assertTrue(text.contains("(本次进程内无记录)"))
    }

    @Test
    fun statusReportsConnectedAndFingerprint() {
        val text = SelfAdbPlan.status(true, "SHA256:abc", true, "配对成功")
        assertTrue(text.contains("已连接"))
        assertTrue(text.contains("SHA256:abc"))
        assertTrue(text.contains("配对成功"))
    }

    // ------------------------------------------------------------ 异常翻译

    @Test
    fun errorTextMapsPairingRequired() {
        val text = SelfAdbPlan.errorText(
            io.github.muntashirakon.adb.AdbPairingRequiredException("ADB pairing is required.")
        )
        assertTrue(text.contains("shpair"))
    }

    @Test
    fun errorTextMapsAuthFailed() {
        val text = SelfAdbPlan.errorText(
            io.github.muntashirakon.adb.AdbAuthenticationFailedException()
        )
        assertTrue(text.contains("shkey regen apply"))
    }

    @Test
    fun errorTextMapsNotConnected() {
        val text = SelfAdbPlan.errorText(java.io.IOException("Not connected to ADB."))
        assertEquals(SelfAdbPlan.notConnected(), text)
    }

    @Test
    fun errorTextMapsProtocolError() {
        val text = SelfAdbPlan.errorText(javax.net.ssl.SSLProtocolException("Received fatal alert: protocol error"))
        assertTrue(text.contains("shpair"))
    }

    @Test
    fun errorTextFallsBackToClassName() {
        val text = SelfAdbPlan.errorText(IllegalStateException())
        assertEquals("IllegalStateException", text)
    }
}
