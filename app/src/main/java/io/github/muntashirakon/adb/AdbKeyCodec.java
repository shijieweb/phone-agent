// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;

import io.github.muntashirakon.adb.annotation.NonNull;

/**
 * 给包外（net.de5.phoneagent）用的一层薄桥。
 *
 * <p>为什么要它：{@link AndroidPubkey} 与 {@link SslUtils} 都是包内可见的，
 * 而 SelfAdb 需要两件事——把公钥编码成 adb 的二进制格式写进 adbkey.pub，
 * 以及换密钥后清掉进程内缓存的 SSLContext。与其把它们改成 public（那等于
 * 把 vendoring 的代码改出补丁），不如在这里开一个明确的小口子，一眼能看出
 * 外部只用了这两个能力。
 */
public final class AdbKeyCodec {

    private AdbKeyCodec() {
    }

    /** adb 自定义公钥格式的 base64 + " 名字\0"，即 adbkey.pub 的内容。 */
    @NonNull
    public static byte[] encodePublicKey(@NonNull RSAPublicKey publicKey, @NonNull String name)
            throws InvalidKeyException {
        return AndroidPubkey.encodeWithName(publicKey, name);
    }

    /** 用私钥给 adb 的 AUTH token 签名（PKCS#1 v1.5 + SHA-1 填充）。 */
    @NonNull
    public static byte[] signToken(@NonNull PrivateKey privateKey, @NonNull byte[] token)
            throws java.security.GeneralSecurityException {
        return AndroidPubkey.adbAuthSign(privateKey, token);
    }

    /** 换密钥后必须调用：否则旧 KeyPair 被 SSLContext 缓存钉死。 */
    public static void clearTlsCache() {
        SslUtils.clearSslContext();
    }
}
