package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.5.1：配对在 Android 16 上被 hidden API 拦死之后，唯一的出路是
 * `shkey import`——借一把手机已经授权过的密钥。
 *
 * 这些测试盯的是「导入时的校验能不能挡住半对密钥」和「报错能不能指路」，
 * 因为这条命令一旦静默失败，操作员就没有第二条路了。
 */
class ShKeyImportTest {

    // ---------------------------------------------------------- 私钥 PEM 形状

    @Test
    fun pemProblemRejectsBlank() {
        val message = SelfAdbPlan.pemProblem("  \n ")
        assertNotNull(message)
        assertTrue(message!!.contains("私钥内容为空"))
    }

    @Test
    fun pemProblemRejectsMissingBegin() {
        val message = SelfAdbPlan.pemProblem("MIIBOgIBAAJBAK\n")
        assertNotNull(message)
        assertTrue(message!!.contains("PKCS#8"))
    }

    @Test
    fun pemProblemRejectsTruncated() {
        val message = SelfAdbPlan.pemProblem(SelfAdbPlan.PEM_BEGIN + "\nMIIBOgIBAAJBAK\n")
        assertNotNull(message)
        assertTrue(message!!.contains("截断"))
    }

    @Test
    fun pemProblemRejectsIllegalBase64() {
        val pem = SelfAdbPlan.PEM_BEGIN + "\n!!!!\n" + SelfAdbPlan.PEM_END + "\n"
        val message = SelfAdbPlan.pemProblem(pem)
        assertNotNull(message)
        assertTrue(message!!.contains("非法 base64"))
    }

    @Test
    fun pemProblemAcceptsWellFormedPem() {
        val pem = SelfAdbPlan.PEM_BEGIN + "\nMIIBOgIBAAJBAK\n" + SelfAdbPlan.PEM_END + "\n"
        assertNull(SelfAdbPlan.pemProblem(pem))
    }

    // ---------------------------------------------------------- 公钥结构体

    @Test
    fun pubProblemRejectsShortBlob() {
        val text = SelfAdbPlan.base64(ByteArray(100), pad = true)
        val message = SelfAdbPlan.pubProblem(text)
        assertNotNull(message)
        assertTrue(message!!.contains("524"))
    }

    @Test
    fun pubProblemRejectsWrongModulusWords() {
        // 前 4 字节小端 = 32 字（1024 位），不是 2048 位。
        val blob = ByteArray(SelfAdbPlan.PUB_BLOB_SIZE)
        blob[0] = 32
        val message = SelfAdbPlan.pubProblem(SelfAdbPlan.base64(blob, pad = true) + " phone-agent@dsh\u0000")
        assertNotNull(message)
        assertTrue(message!!.contains("不是 2048 位"))
    }

    @Test
    fun pubProblemAcceptsWellFormedBlob() {
        val blob = ByteArray(SelfAdbPlan.PUB_BLOB_SIZE)
        blob[0] = SelfAdbPlan.PUB_MODULUS_WORDS.toByte()
        val text = SelfAdbPlan.base64(blob, pad = true) + " phone-agent@dsh\u0000"
        assertNull(SelfAdbPlan.pubProblem(text))
    }

    @Test
    fun pubBlobIgnoresTrailingName() {
        val blob = ByteArray(SelfAdbPlan.PUB_BLOB_SIZE) { 7 }
        blob[0] = 64
        val text = SelfAdbPlan.base64(blob, pad = true) + " someone@host\u0000"
        val parsed = SelfAdbPlan.pubBlob(text)
        assertNotNull(parsed)
        assertEquals(SelfAdbPlan.PUB_BLOB_SIZE, parsed!!.size)
        assertEquals(64, parsed[0].toInt())
    }

    @Test
    fun pubNameReadsTheTrailingName() {
        val blob = ByteArray(SelfAdbPlan.PUB_BLOB_SIZE)
        blob[0] = 64
        val text = SelfAdbPlan.base64(blob, pad = true) + " laptop@desk\u0000"
        assertEquals("laptop@desk", SelfAdbPlan.pubName(text))
    }

    @Test
    fun pubNameIsNullWithoutName() {
        assertNull(SelfAdbPlan.pubName(SelfAdbPlan.base64(ByteArray(SelfAdbPlan.PUB_BLOB_SIZE), pad = true)))
    }

    // ---------------------------------------------------------- 文案

    @Test
    fun originLabelsAreReadable() {
        assertEquals("导入", SelfAdbPlan.originLabel("imported"))
        assertEquals("自生成", SelfAdbPlan.originLabel("generated"))
        assertTrue(SelfAdbPlan.originLabel(null).contains("未知"))
    }

    @Test
    fun importOkPointsAtShconnect() {
        val text = SelfAdbPlan.importOk("laptop@desk", "SHA256:abc")
        assertTrue(text.contains("laptop@desk"))
        assertTrue(text.contains("SHA256:abc"))
        assertTrue(text.contains("shconnect"))
    }

    @Test
    fun showReportHasThreeLines() {
        val text = SelfAdbPlan.showReport("imported", "QAAA", "SHA256:abc")
        val lines = text.split('\n')
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("导入"))
        assertTrue(lines[1].startsWith("公钥: "))
        assertTrue(lines[2].startsWith("指纹: "))
    }

    @Test
    fun showReportMarksMissingKey() {
        val text = SelfAdbPlan.showReport(null, null, null)
        assertTrue(text.contains("(未生成)"))
    }

    @Test
    fun adoptExplainsWhyItCannotWork() {
        val text = SelfAdbPlan.adoptUnsupported()
        assertTrue(text.contains("adb_keys"))
        assertTrue(text.contains("shkey import"))
    }

    @Test
    fun hiddenApiBlockedSaysWhatToDo() {
        val text = SelfAdbPlan.hiddenApiBlocked()
        assertTrue(text.contains("exportKeyingMaterial"))
        assertTrue(text.contains("shkey import"))
    }

    // ---------------------------------------------------------- hidden API 识别

    @Test
    fun detectsWrappedNoSuchMethod() {
        val inner = NoSuchMethodException(
            "com.android.org.conscrypt.Conscrypt.exportKeyingMaterial [class javax.net.ssl.SSLSocket]"
        )
        val wrapped = javax.net.ssl.SSLException(inner)
        assertTrue(SelfAdbPlan.isHiddenApiBlock(wrapped))
    }

    @Test
    fun detectsDirectNoSuchMethod() {
        assertTrue(SelfAdbPlan.isHiddenApiBlock(NoSuchMethodException("exportKeyingMaterial")))
    }

    @Test
    fun ignoresUnrelatedHandshakeFailure() {
        assertFalse(SelfAdbPlan.isHiddenApiBlock(javax.net.ssl.SSLException("Handshake failed")))
        assertFalse(SelfAdbPlan.isHiddenApiBlock(java.io.IOException("Connection refused")))
    }
}