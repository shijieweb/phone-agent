package net.de5.phoneagent

import java.math.BigInteger

/**
 * 从 PKCS#8 DER 里抠出 RSA 的 modulus / publicExponent。
 *
 * 为什么需要它：Android 12（API 31，Lenovo TB-J607F 实测）上，Conscrypt 对
 * PKCS#8 私钥返回的实例不保证实现 java.security.interfaces.RSAPrivateCrtKey，
 * 一句 as? RSAPrivateCrtKey 就把「拿不到模数」判成了「密钥不能用」——
 * shkey import 在平板上稳定 exit=6，同一把钥匙在 Android 16 上却导入成功。
 * 私钥本身没问题，问题在我们只肯从 CRT 接口里取 n/e。
 *
 * 所以这里做最小 DER 走查，自己把 n/e 读出来：
 *
 * PrivateKeyInfo ::= SEQUENCE {
 *   version             INTEGER,
 *   privateKeyAlgorithm SEQUENCE { OID rsaEncryption, NULL },
 *   privateKey          OCTET STRING  -- 里面是 RSAPrivateKey
 * }
 * RSAPrivateKey ::= SEQUENCE {
 *   version INTEGER, modulus INTEGER, publicExponent INTEGER, ...
 * }
 *
 * 只认 rsaEncryption（1.2.840.113549.1.1.1），别的算法一律 null——宁可让上层报
 * 「这把私钥不是 RSA」，也不要瞎猜出一个公钥去握手。
 * 不引第三方库：DER 是定长前缀编码，六十行够用。
 */
internal object RsaDer {

    private const val TAG_INTEGER = 0x02
    private const val TAG_OCTET_STRING = 0x04
    private const val TAG_OID = 0x06
    private const val TAG_SEQUENCE = 0x30

    /** 1.2.840.113549.1.1.1 的 DER 内容（不含 tag/length）。 */
    private val RSA_OID = byteArrayOf(
        0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(),
        0x0D, 0x01, 0x01, 0x01
    )

    /** 2048 位是 generate() 的固定长度；比这短的模数不可能来自我们的密钥。 */
    private const val MIN_MODULUS_BYTES = 128

    /**
     * @return modulus 与 publicExponent；结构不认识、不是 RSA、或被截断都返回 null。
     */
    fun modulusAndExponent(pkcs8: ByteArray): Pair<BigInteger, BigInteger>? {
        val outer = read(pkcs8, 0, pkcs8.size) ?: return null
        if (outer.tag != TAG_SEQUENCE) return null
        var p = outer.start

        val version = read(pkcs8, p, outer.end) ?: return null
        if (version.tag != TAG_INTEGER) return null
        p = version.end

        val algorithm = read(pkcs8, p, outer.end) ?: return null
        if (algorithm.tag != TAG_SEQUENCE) return null
        p = algorithm.end

        val oid = read(pkcs8, algorithm.start, algorithm.end) ?: return null
        if (oid.tag != TAG_OID) return null
        if (!matchesRsaOid(pkcs8, oid)) return null

        val octets = read(pkcs8, p, outer.end) ?: return null
        if (octets.tag != TAG_OCTET_STRING) return null

        val inner = read(pkcs8, octets.start, octets.end) ?: return null
        if (inner.tag != TAG_SEQUENCE) return null
        var q = inner.start

        val rsaVersion = read(pkcs8, q, inner.end) ?: return null
        if (rsaVersion.tag != TAG_INTEGER) return null
        q = rsaVersion.end

        val modulus = read(pkcs8, q, inner.end) ?: return null
        if (modulus.tag != TAG_INTEGER) return null
        q = modulus.end

        val exponent = read(pkcs8, q, inner.end) ?: return null
        if (exponent.tag != TAG_INTEGER) return null

        if (modulus.length < MIN_MODULUS_BYTES) return null
        val n = BigInteger(1, pkcs8.copyOfRange(modulus.start, modulus.end))
        val e = BigInteger(1, pkcs8.copyOfRange(exponent.start, exponent.end))
        // 指数必须是正的奇数：偶数指数或 1 不可能是真密钥，宁可让上层报错。
        if (n.signum() <= 0) return null
        if (e.signum() <= 0 || !e.testBit(0) || e == BigInteger.ONE) return null
        return Pair(n, e)
    }

    /** 一个 DER 元素在缓冲区里的位置：[start] 是内容首字节，[end] 是内容末字节之后。 */
    private class Element(val tag: Int, val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    private fun read(der: ByteArray, offset: Int, limit: Int): Element? {
        if (offset < 0 || limit > der.size || offset + 2 > limit) return null
        val tag = der[offset].toInt() and 0xFF
        var p = offset + 1
        var length = der[p].toInt() and 0xFF
        p++
        if (length and 0x80 != 0) {
            val count = length and 0x7F
            // 只支持 1-4 字节长度；0 是非法 indefinite 长度，5+ 不可能是密钥。
            if (count == 0 || count > 4) return null
            if (p + count > limit) return null
            var value = 0
            for (i in 0 until count) {
                value = (value shl 8) or (der[p].toInt() and 0xFF)
                p++
            }
            length = value
        }
        if (length < 0 || p + length > limit) return null
        return Element(tag, p, p + length)
    }

    private fun matchesRsaOid(der: ByteArray, oid: Element): Boolean {
        if (oid.length != RSA_OID.size) return false
        for (i in RSA_OID.indices) {
            if (der[oid.start + i] != RSA_OID[i]) return false
        }
        return true
    }
}
