package net.de5.phoneagent

import io.github.muntashirakon.crypto.HkdfSha256
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RFC 5869 附录 A 的两组官方向量。
 *
 * 为什么必须用官方向量而不是自己算一遍对一遍：HKDF 是配对握手的密钥派生，
 * 算错了配对会以「密码错误」的形式失败（服务端只说 tag 不对），
 * 自己写的实现和自己写的期望值一起错是最难查的一类 bug。
 */
class HkdfSha256Test {

    private fun hex(text: String): ByteArray {
        val clean = text.replace(" ", "").replace("\n", "")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /** Case 1：salt 与 info 都非空，输出跨两个 HMAC 块（42 字节 > 32）。 */
    @Test
    fun rfc5869Case1() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val prk = HkdfSha256.extract(salt, ikm)
        assertEquals(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            toHex(prk)
        )
        val okm = HkdfSha256.expand(prk, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            toHex(okm)
        )
    }

    /**
     * Case 3：salt 与 info 都是零长度。
     *
     * 这条是 adb 配对真正走的分支——PairingAuthCtx 传的 salt 就是 null，
     * 而 null 必须等价于「32 字节零」，不是「跳过 extract」。
     */
    @Test
    fun rfc5869Case3ZeroSalt() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val prk = HkdfSha256.extract(null, ikm)
        assertEquals(
            "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04",
            toHex(prk)
        )
        val okm = HkdfSha256.expand(prk, ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8",
            toHex(okm)
        )
    }

    /** derive 是 extract+expand 的一步版，结果必须与两步走一致。 */
    @Test
    fun deriveMatchesTwoStep() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val info = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8)
        val twoStep = HkdfSha256.expand(HkdfSha256.extract(null, ikm), info, 16)
        val oneStep = HkdfSha256.derive(null, ikm, info, 16)
        assertEquals(toHex(twoStep), toHex(oneStep))
        assertEquals(16, oneStep.size)
    }

    /** 长度越界当场抛，不返回半截数组——半截密钥比抛异常危险得多。 */
    @Test
    fun expandRejectsBadLength() {
        val prk = ByteArray(32) { 1 }
        try {
            HkdfSha256.expand(prk, ByteArray(0), -1)
            throw AssertionError("negative length should be rejected")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
        // 0 是合法请求（要 0 字节就给 0 字节），不能抛
        assertEquals(0, HkdfSha256.expand(prk, ByteArray(0), 0).size)
        try {
            HkdfSha256.expand(prk, ByteArray(0), 255 * 32 + 1)
            throw AssertionError("length > 255*32 should be rejected")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}
