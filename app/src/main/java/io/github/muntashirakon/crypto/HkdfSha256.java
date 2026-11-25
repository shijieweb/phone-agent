// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF-SHA256（RFC 5869），只实现 adb 配对要用的那一段。
 *
 * <p>为什么自己写：vendoring 进来的 libadb 原本用 BouncyCastle 的
 * {@code HKDFBytesGenerator}，而本项目的硬约束是零第三方运行时依赖。
 * 这段算法只有二十来行，比引一个 8 MB 的加密库划算得多，也能被单测按
 * RFC 5869 附录 A 的官方测试向量钉死。
 *
 * <p>三个方法都是无状态的，salt 传 {@code null} 等价于传
 * {@code HashLen} 个零字节（RFC 5869 §2.2 的规定），adb 配对正是这么用的。
 */
public final class HkdfSha256 {

    /** RFC 5869 用 SHA-256 时 HashLen = 32。 */
    public static final int HASH_LEN = 32;

    private static final String MAC_ALGORITHM = "HmacSHA256";

    private HkdfSha256() {
    }

    /**
     * HKDF-Extract：{@code PRK = HMAC-Hash(salt, IKM)}。
     *
     * @param salt 盐，null 或空数组按 HashLen 个零字节处理
     * @param ikm  输入密钥材料
     * @return 32 字节的伪随机密钥
     */
    public static byte[] extract(byte[] salt, byte[] ikm) {
        byte[] realSalt = (salt == null || salt.length == 0) ? new byte[HASH_LEN] : salt;
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(realSalt, MAC_ALGORITHM));
            return mac.doFinal(ikm);
        } catch (GeneralSecurityException e) {
            // 每个 Android 运行时都带 HmacSHA256，走不到这里
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * HKDF-Expand：{@code OKM = T(1) | T(2) | ...}，
     * {@code T(i) = HMAC-Hash(PRK, T(i-1) | info | i)}。
     *
     * @param prk    {@link #extract} 的输出
     * @param info   上下文/应用相关字符串，可为 null
     * @param length 需要的字节数，不能超过 255 * HashLen
     */
    public static byte[] expand(byte[] prk, byte[] info, int length) {
        if (length < 0 || length > 255 * HASH_LEN) {
            throw new IllegalArgumentException("invalid output length " + length);
        }
        byte[] realInfo = info == null ? new byte[0] : info;
        byte[] out = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        int counter = 1;
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(prk, MAC_ALGORITHM));
            while (offset < length) {
                mac.reset();
                mac.update(previous);
                mac.update(realInfo);
                mac.update((byte) counter);
                previous = mac.doFinal();
                int take = Math.min(previous.length, length - offset);
                System.arraycopy(previous, 0, out, offset, take);
                offset += take;
                counter++;
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        } finally {
            Arrays.fill(previous, (byte) 0);
        }
    }

    /** extract + expand 一步到位，调用方不用关心中间的 PRK。 */
    public static byte[] derive(byte[] salt, byte[] ikm, byte[] info, int length) {
        byte[] prk = extract(salt, ikm);
        try {
            return expand(prk, info, length);
        } finally {
            Arrays.fill(prk, (byte) 0);
        }
    }

    /** 只是为了让 MessageDigest 的可用性在编译期就被引用一次，运行时无用。 */
    static MessageDigest sha256() throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256");
    }
}
