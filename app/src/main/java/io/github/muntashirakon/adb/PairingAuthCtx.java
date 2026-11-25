// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import android.os.Build;

import io.github.muntashirakon.adb.annotation.NonNull;
import io.github.muntashirakon.adb.annotation.Nullable;
import io.github.muntashirakon.adb.annotation.RequiresApi;
import io.github.muntashirakon.adb.annotation.VisibleForTesting;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.Destroyable;

import io.github.muntashirakon.crypto.HkdfSha256;
import io.github.muntashirakon.crypto.spake2.Spake2Context;
import io.github.muntashirakon.crypto.spake2.Spake2Role;

@RequiresApi(Build.VERSION_CODES.GINGERBREAD)
class PairingAuthCtx implements Destroyable {
    // The following values are taken from the following source and are subjected to change
    // https://github.com/aosp-mirror/platform_system_core/blob/android-11.0.0_r1/adb/pairing_auth/pairing_auth.cpp
    private static final byte[] CLIENT_NAME = StringCompat.getBytes("adb pair client\u0000", "UTF-8");
    private static final byte[] SERVER_NAME = StringCompat.getBytes("adb pair server\u0000", "UTF-8");

    // The following values are taken from the following source and are subjected to change
    // https://github.com/aosp-mirror/platform_system_core/blob/android-11.0.0_r1/adb/pairing_auth/aes_128_gcm.cpp
    private static final byte[] INFO = StringCompat.getBytes("adb pairing_auth aes-128-gcm key", "UTF-8");
    private static final int HKDF_KEY_LENGTH = 128 / 8;
    public static final int GCM_IV_LENGTH = 12; // in bytes

    private final byte[] mMsg;
    private final Spake2Context mSpake2Ctx;
    private final byte[] mSecretKey = new byte[HKDF_KEY_LENGTH];
    private long mDecIv = 0;
    private long mEncIv = 0;
    private boolean mIsDestroyed = false;

    @Nullable
    public static PairingAuthCtx createAlice(byte[] password) {
        Spake2Context spake25519 = new Spake2Context(Spake2Role.Alice, CLIENT_NAME, SERVER_NAME);
        try {
            return new PairingAuthCtx(spake25519, password);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return null;
        }
    }

    @VisibleForTesting
    @Nullable
    public static PairingAuthCtx createBob(byte[] password) {
        Spake2Context spake25519 = new Spake2Context(Spake2Role.Bob, SERVER_NAME, CLIENT_NAME);
        try {
            return new PairingAuthCtx(spake25519, password);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return null;
        }
    }

    private PairingAuthCtx(Spake2Context spake25519, byte[] password)
            throws IllegalArgumentException, IllegalStateException {
        mSpake2Ctx = spake25519;
        mMsg = mSpake2Ctx.generateMessage(password);
    }

    public byte[] getMsg() {
        return mMsg;
    }

    public boolean initCipher(byte[] theirMsg) throws IllegalArgumentException, IllegalStateException {
        if (mIsDestroyed) return false;
        byte[] keyMaterial = mSpake2Ctx.processMessage(theirMsg);
        if (keyMaterial == null) return false;
        // 与 BouncyCastle 的 HKDFParameters(keyMaterial, null, INFO) 等价：salt 传
        // null 时 RFC 5869 规定用 HashLen 个零字节，info 就是 adb 写死的那个串。
        byte[] derived = HkdfSha256.derive(null, keyMaterial, INFO, mSecretKey.length);
        System.arraycopy(derived, 0, mSecretKey, 0, mSecretKey.length);
        Arrays.fill(derived, (byte) 0);
        Arrays.fill(keyMaterial, (byte) 0);
        return true;
    }

    @Nullable
    public byte[] encrypt(@NonNull byte[] in) {
        return encryptDecrypt(true, in, ByteBuffer.allocate(GCM_IV_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN).putLong(mEncIv++).array());
    }

    @Nullable
    public byte[] decrypt(@NonNull byte[] in) {
        return encryptDecrypt(false, in, ByteBuffer.allocate(GCM_IV_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN).putLong(mDecIv++).array());
    }

    @Override
    public boolean isDestroyed() {
        return mIsDestroyed;
    }

    @Override
    public void destroy() {
        mIsDestroyed = true;
        Arrays.fill(mSecretKey, (byte) 0);
        mSpake2Ctx.destroy();
    }

    /**
     * AES-128-GCM 加解密，语义与原来的 BouncyCastle 版本一一对应。
     *
     * <p>为什么换 JCE：本项目的硬约束是零第三方运行时依赖，而
     * {@code AES/GCM/NoPadding} 是 Android 自 API 19 起就有的标准实现。
     * 密文布局也一致——JCE 的 {@code doFinal} 直接把 tag 附在密文尾部，
     * 正是 adb 协议要求的 {@code ciphertext || tag}。
     *
     * <p>失败一律返回 null（原实现靠 {@code InvalidCipherTextException}
     * 表达同一件事）：配对时对方消息被篡改或密钥不一致，都属于「这次配对
     * 不能继续」，而不是让异常穿出去打断整个 TLS 握手。
     */
    @Nullable
    private byte[] encryptDecrypt(boolean forEncryption, @NonNull byte[] in, @NonNull byte[] iv) {
        if (mIsDestroyed) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(forEncryption ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                    new SecretKeySpec(mSecretKey, "AES"),
                    new GCMParameterSpec(mSecretKey.length * 8, iv));
            return cipher.doFinal(in);
        } catch (GeneralSecurityException e) {
            // AEADBadTagException（tag 不匹配）也走这里，等价于原来的
            // InvalidCipherTextException 分支
            return null;
        }
    }
}
