package net.de5.phoneagent

import io.github.muntashirakon.adb.AdbKeyCodec
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 自连 ADB 要用的那对密钥与自签证书。
 *
 * 三件事在这里做完，别处不再关心格式：
 *
 * 1. **私钥**存成 PKCS#8 PEM，文件名与 AOSP 的 `adb keygen` 一致（`adbkey`），
 *    这样用 adb 命令行生成过的密钥能直接拷过来用；
 * 2. **公钥**存成 adb 自定义二进制格式的 base64 + " 名字\0"（`adbkey.pub`），
 *    手机「已配对的设备」读的就是这个格式；
 * 3. **自签证书**每次启动按私钥现算——adb 只校验签名与公钥是否匹配，
 *    不校验证书内容、有效期与签发者，所以没必要把证书也落盘。
 */
internal object SelfAdbKeys {

    /** 一把可用的密钥：私钥、公钥（adb 编码要用）、自签证书（TLS 要用）。 */
    internal class Material(
        val privateKey: PrivateKey,
        val publicKey: RSAPublicKey,
        val certificate: X509Certificate
    )

    fun dir(context: android.content.Context): File = File(context.filesDir, SelfAdbPlan.KEY_DIR)

    fun privateFile(context: android.content.Context): File = File(dir(context), SelfAdbPlan.KEY_NAME)

    fun publicFile(context: android.content.Context): File = File(dir(context), SelfAdbPlan.PUB_NAME)

    fun hasKey(context: android.content.Context): Boolean =
        privateFile(context).isFile && publicFile(context).isFile

    /**
     * 读已有密钥；缺失或损坏返回 null（由调用方决定是否重新生成，绝不静默
     * 覆盖——覆盖等于把手机上已授权的指纹弄丢）。
     */
    fun load(context: android.content.Context): Material? {
        return try {
            val pem = privateFile(context).readText()
            val der = SelfAdbPlan.unbase64(stripPem(pem)) ?: return null
            val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
            build(privateKey)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 生成一把新密钥并落盘。
     *
     * 指数固定 3：AOSP 的 `adb keygen` 用的就是 3，手机上 `/data/misc/adb/adb_keys`
     * 里已有的记录也多是 3；adb 协议对这个值没有硬假设（编码层原样写进结构体），
     * 但保持一致能少一类「为什么我和 adb 生成的指纹对不上」的困惑。
     */
    fun generate(context: android.content.Context): Material? {
        return try {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(java.security.spec.RSAPublicKeySpec(
                BigInteger.ONE, BigInteger.ONE
            ).let { java.security.spec.RSAKeyGenParameterSpec(2048, BigInteger.valueOf(3)) })
            val pair = generator.generateKeyPair()
            val privateKey = pair.private as PrivateKey
            val publicKey = pair.public as RSAPublicKey

            val target = dir(context)
            if (!target.isDirectory && !target.mkdirs()) return null
            privateFile(context).writeText(pem(privateKey.encoded))
            publicFile(context).writeBytes(
                AdbKeyCodec.encodePublicKey(publicKey, SelfAdbPlan.DEVICE_NAME)
            )
            build(privateKey)
        } catch (t: Throwable) {
            null
        }
    }

    /** 删掉现有密钥；调用方负责先确认（`shkey regen apply`）。 */
    fun delete(context: android.content.Context): Boolean {
        val a = privateFile(context).delete()
        val b = publicFile(context).delete()
        AdbKeyCodec.clearTlsCache()
        return a || b
    }

    /**
     * 指纹：对 adb 编码后的公钥（524 字节）做 SHA-256，再 base64 去掉尾部 =。
     * 与 AOSP `adb keygen` 打印的 `SHA256:...` 同一算法，方便跟手机对账。
     */
    fun fingerprint(publicKey: RSAPublicKey): String? {
        return try {
            val encoded = AdbKeyCodec.encodePublicKey(publicKey, SelfAdbPlan.DEVICE_NAME)
            val cut = encoded.indexOf(' '.code.toByte())
            val blob = SelfAdbPlan.unbase64(
                String(if (cut > 0) encoded.copyOfRange(0, cut) else encoded, Charsets.US_ASCII)
            ) ?: return null
            SelfAdbPlan.fingerprint(MessageDigest.getInstance("SHA-256").digest(blob))
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------ 内部

    private fun build(privateKey: PrivateKey): Material? {
        val crt = privateKey as? RSAPrivateCrtKey ?: return null
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            java.security.spec.RSAPublicKeySpec(crt.modulus, crt.publicExponent)
        ) as RSAPublicKey
        val certificate = selfSigned(privateKey, publicKey) ?: return null
        return Material(privateKey, publicKey, certificate)
    }

    private fun stripPem(pem: String): String = pem
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")

    private fun pem(der: ByteArray): String {
        val body = SelfAdbPlan.base64(der)
        val sb = StringBuilder("-----BEGIN PRIVATE KEY-----\n")
        var i = 0
        while (i < body.length) {
            val end = minOf(i + 64, body.length)
            sb.append(body, i, end).append('\n')
            i = end
        }
        sb.append("-----END PRIVATE KEY-----\n")
        return sb.toString()
    }

    /**
     * 现写一份 X.509 自签证书。
     *
     * 为什么要手写 DER：Android 没有「生成证书」的公开 API，
     * `CertificateFactory` 只能解析不能签发。而 adb 对这张证书的要求极低——
     * 公钥与私钥匹配、能被 TLS 栈解析即可（SslUtils 里是全接受的 TrustManager，
     * adb 服务端也只做一次 TLS 握手），所以一段最小可用的 DER 就够了。
     *
     * 有效期钳到 2049-12-31：UTCTime 只能表示 1950..2049，超过就得换
     * GeneralizedTime，为一个自签证书引入两条编码路径不值得。
     */
    private fun selfSigned(privateKey: PrivateKey, publicKey: RSAPublicKey): X509Certificate? {
        return try {
            val name = derSequence(
                derSet(derSequence(derOid(OID_COMMON_NAME), derUtf8("phone-agent")))
            )
            val algorithm = derSequence(derOid(OID_SHA256_RSA), derNull())
            val serial = derInteger(BigInteger.valueOf(System.currentTimeMillis()))
            val validity = derSequence(
                derUtcTime(now(-24L * 3600 * 1000)),
                derUtcTime(notAfter())
            )
            val spki = derSequence(
                derSequence(derOid(OID_RSA), derNull()),
                derBitString(derSequence(derInteger(publicKey.modulus), derInteger(publicKey.publicExponent)))
            )
            val tbs = derSequence(
                derExplicit(0, derInteger(BigInteger.valueOf(2))),
                serial,
                algorithm,
                name,
                validity,
                name,
                spki
            )
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initSign(privateKey)
            signature.update(tbs)
            val signed = signature.sign()
            val certificate = derSequence(tbs, algorithm, derBitString(signed))
            CertificateFactory.getInstance("X.509")
                .generateCertificate(certificate.inputStream()) as X509Certificate
        } catch (t: Throwable) {
            null
        }
    }

    private const val OID_COMMON_NAME = "2.5.4.3"
    private const val OID_SHA256_RSA = "1.2.840.113549.1.1.11"
    private const val OID_RSA = "1.2.840.113549.1.1.1"

    private fun now(offsetMillis: Long): Long = System.currentTimeMillis() + offsetMillis

    private fun notAfter(): Long {
        val twentyYears = 20L * 365 * 24 * 3600 * 1000
        val limit = utc("491231235959")
        return minOf(now(twentyYears), limit)
    }

    private fun utc(text: String): Long {
        val format = SimpleDateFormat("yyMMddHHmmss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.parse(text).time
    }

    private fun derUtcTime(millis: Long): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return der(0x17, format.format(Date(millis)).toByteArray(Charsets.US_ASCII))
    }

    private fun derInteger(value: BigInteger): ByteArray = der(0x02, value.toByteArray())

    private fun derNull(): ByteArray = der(0x05, ByteArray(0))

    private fun derUtf8(text: String): ByteArray = der(0x0c, text.toByteArray(Charsets.UTF_8))

    private fun derBitString(payload: ByteArray): ByteArray {
        val body = ByteArray(payload.size + 1)
        body[0] = 0
        System.arraycopy(payload, 0, body, 1, payload.size)
        return der(0x03, body)
    }

    private fun derExplicit(tag: Int, payload: ByteArray): ByteArray = der(0xa0 or tag, payload)

    private fun derSequence(vararg parts: ByteArray): ByteArray = der(0x30, concat(parts))

    private fun derSet(vararg parts: ByteArray): ByteArray = der(0x31, concat(parts))

    private fun derOid(dotted: String): ByteArray {
        val numbers = dotted.split('.').map { it.toInt() }
        val out = java.io.ByteArrayOutputStream()
        out.write(numbers[0] * 40 + numbers[1])
        for (i in 2 until numbers.size) {
            var value = numbers[i]
            val chunk = ArrayList<Int>(4)
            chunk.add(value and 0x7f)
            value = value shr 7
            while (value > 0) {
                chunk.add((value and 0x7f) or 0x80)
                value = value shr 7
            }
            for (j in chunk.indices.reversed()) out.write(chunk[j])
        }
        return der(0x06, out.toByteArray())
    }

    private fun der(tag: Int, body: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(body.size + 6)
        out.write(tag)
        val length = body.size
        when {
            length < 0x80 -> out.write(length)
            length < 0x100 -> {
                out.write(0x81)
                out.write(length)
            }
            length < 0x10000 -> {
                out.write(0x82)
                out.write((length shr 8) and 0xff)
                out.write(length and 0xff)
            }
            else -> {
                out.write(0x83)
                out.write((length shr 16) and 0xff)
                out.write((length shr 8) and 0xff)
                out.write(length and 0xff)
            }
        }
        out.write(body)
        return out.toByteArray()
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }
}
