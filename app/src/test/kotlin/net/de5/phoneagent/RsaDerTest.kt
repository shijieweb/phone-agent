package net.de5.phoneagent

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 钉住 Android 12（API 31）平板上的真机缺陷：Conscrypt 对 PKCS#8 私钥返回的
 * 实例不保证实现 RSAPrivateCrtKey，旧代码一句 `as? RSAPrivateCrtKey` 就把
 * 「拿不到模数」判成了「密钥不能用」—— shkey import 稳定 exit=6，而同一把
 * 钥匙在 Android 16 上导入成功。
 *
 * 这里的样本密钥是现场生成的（不落盘、不进仓库）：要验的是「能从 PKCS#8 里
 * 抠出 n/e」，不是某一把具体的钥匙。
 */
class RsaDerTest {

    @Test
    fun theModulusAndExponentComeOutOfAPkcs8Key() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSAKeyGenParameterSpec(2048, BigInteger.valueOf(3)))
        val pair = generator.generateKeyPair()
        val publicKey = pair.public as RSAPublicKey

        val parsed = RsaDer.modulusAndExponent(pair.private.encoded)
        assertNotNull(parsed)
        assertEquals(publicKey.modulus, parsed!!.first)
        assertEquals(publicKey.publicExponent, parsed.second)
    }

    @Test
    fun theExponentIsCarriedThroughVerbatim() {
        // 65537 与 3 都要能读出来：adb keygen 用 3，别的工具常用 65537。
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        val parsed = RsaDer.modulusAndExponent(pair.private.encoded)!!
        assertEquals(BigInteger.valueOf(65537), parsed.second)
    }

    @Test
    fun aTruncatedKeyIsRefusedInsteadOfGuessed() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSAKeyGenParameterSpec(2048, BigInteger.valueOf(3)))
        val der = generator.generateKeyPair().private.encoded
        assertNull(RsaDer.modulusAndExponent(der.copyOfRange(0, der.size / 2)))
        assertNull(RsaDer.modulusAndExponent(ByteArray(0)))
    }

    @Test
    fun garbageIsRefusedInsteadOfGuessed() {
        assertNull(RsaDer.modulusAndExponent(byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x00)))
        assertNull(RsaDer.modulusAndExponent("not a key at all".toByteArray()))
        // 长度字段声称 0x7F 个后续字节（indefinite 长度的写法）：一律不认。
        assertNull(RsaDer.modulusAndExponent(byteArrayOf(0x30, 0x80.toByte(), 0x02, 0x01, 0x00)))
    }

    @Test
    fun aShortModulusIsRefused() {
        // 512 位模数不可能是我们 generate() 出来的密钥；认了只会在握手里炸。
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(512)
        val der = generator.generateKeyPair().private.encoded
        assertNull(RsaDer.modulusAndExponent(der))
    }

    @Test
    fun theParsedNumbersArePositive() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSAKeyGenParameterSpec(2048, BigInteger.valueOf(3)))
        val parsed = RsaDer.modulusAndExponent(generator.generateKeyPair().private.encoded)!!
        assertEquals(1, parsed.first.signum())
        assertEquals(1, parsed.second.signum())
    }

    @Test
    fun theFailureTextStopsBlamingCrt() {
        // 操作员看到的必须是「不是 RSA」而不是「不是 CRT」——后者会把人引向
        // 「换一把钥匙」，而真机上换钥匙根本没用。
        val text = SelfAdbPlan.importNotRsa()
        assertEquals(false, text.contains("CRT"))
        assertEquals(true, text.contains("PKCS#8"))
        assertEquals(true, text.startsWith("导入失败"))
    }
}
