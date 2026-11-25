// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import android.annotation.SuppressLint;
import android.os.Build;
import android.util.Log;

import io.github.muntashirakon.adb.annotation.NonNull;
import io.github.muntashirakon.adb.annotation.Nullable;
import io.github.muntashirakon.adb.annotation.RequiresApi;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.InvalidKeyException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Objects;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

// https://github.com/aosp-mirror/platform_system_core/blob/android-11.0.0_r1/adb/pairing_connection/pairing_connection.cpp
// Also based on Shizuku's implementation
@RequiresApi(Build.VERSION_CODES.GINGERBREAD)
public final class PairingConnectionCtx implements Closeable {
    public static final String TAG = PairingConnectionCtx.class.getSimpleName();

    public static final String EXPORTED_KEY_LABEL = "adb-label\u0000";
    public static final int EXPORT_KEY_SIZE = 64;

    private enum State {
        Ready,
        ExchangingMsgs,
        ExchangingPeerInfo,
        Stopped
    }

    enum Role {
        Client,
        Server,
    }

    private final String mHost;
    private final int mPort;
    private final byte[] mPswd;
    private final PeerInfo mPeerInfo;
    private final SSLContext mSslContext;
    private final Role mRole = Role.Client;

    private DataInputStream mInputStream;
    private DataOutputStream mOutputStream;
    private PairingAuthCtx mPairingAuthCtx;
    private State mState = State.Ready;

    public PairingConnectionCtx(@NonNull String host, int port, @NonNull byte[] pswd, @NonNull KeyPair keyPair,
                                @NonNull String deviceName)
            throws NoSuchAlgorithmException, KeyManagementException, InvalidKeyException {
        this.mHost = Objects.requireNonNull(host);
        this.mPort = port;
        this.mPswd = Objects.requireNonNull(pswd);
        this.mPeerInfo = new PeerInfo(PeerInfo.ADB_RSA_PUB_KEY, AndroidPubkey.encodeWithName((RSAPublicKey)
                keyPair.getPublicKey(), Objects.requireNonNull(deviceName)));
        this.mSslContext = SslUtils.getSslContext(keyPair);
    }

    public PairingConnectionCtx(@NonNull String host, int port, @NonNull byte[] pswd, @NonNull PrivateKey privateKey,
                                @NonNull Certificate certificate, @NonNull String deviceName)
            throws NoSuchAlgorithmException, KeyManagementException, InvalidKeyException {
        this(host, port, pswd, new KeyPair(Objects.requireNonNull(privateKey), Objects.requireNonNull(certificate)),
                deviceName);
    }

    public void start() throws IOException {
        if (mState != State.Ready) {
            throw new IOException("Connection is not ready yet.");
        }

        mState = State.ExchangingMsgs;

        // Start worker
        setupTlsConnection();

        for (; ; ) {
            switch (mState) {
                case ExchangingMsgs:
                    if (!doExchangeMsgs()) {
                        notifyResult();
                        throw new IOException("Exchanging message wasn't successful.");
                    }
                    mState = State.ExchangingPeerInfo;
                    break;
                case ExchangingPeerInfo:
                    if (!doExchangePeerInfo()) {
                        notifyResult();
                        throw new IOException("Could not exchange peer info.");
                    }
                    notifyResult();
                    return;
                case Ready:
                case Stopped:
                    throw new IOException("Connection closed with errors.");
            }
        }
    }

    private void notifyResult() {
        mState = State.Stopped;
    }

    private void setupTlsConnection() throws IOException {
        Socket socket;
        if (mRole == Role.Server) {
            SSLServerSocket sslServerSocket = (SSLServerSocket) mSslContext.getServerSocketFactory().createServerSocket(mPort);
            socket = sslServerSocket.accept();
            // TODO: Write automated test scripts after removing Conscrypt dependency.
        } else { // role == Role.Client
            socket = new Socket(mHost, mPort);
        }
        socket.setTcpNoDelay(true);

        // We use custom SSLContext to allow any SSL certificates
        SSLSocket sslSocket = (SSLSocket) mSslContext.getSocketFactory().createSocket(socket, mHost, mPort, true);
        sslSocket.startHandshake();
        Log.d(TAG, "Handshake succeeded.");

        mInputStream = new DataInputStream(sslSocket.getInputStream());
        mOutputStream = new DataOutputStream(sslSocket.getOutputStream());

        // To ensure the connection is not stolen while we do the PAKE, append the exported key material from the
        // tls connection to the password.
        byte[] keyMaterial = exportKeyingMaterial(sslSocket, EXPORT_KEY_SIZE);
        byte[] passwordBytes = new byte[mPswd.length + keyMaterial.length];
        System.arraycopy(mPswd, 0, passwordBytes, 0, mPswd.length);
        System.arraycopy(keyMaterial, 0, passwordBytes, mPswd.length, keyMaterial.length);

        PairingAuthCtx pairingAuthCtx = PairingAuthCtx.createAlice(passwordBytes);
        if (pairingAuthCtx == null) {
            throw new IOException("Unable to create PairingAuthCtx.");
        }
        this.mPairingAuthCtx = pairingAuthCtx;
    }

    @SuppressLint("PrivateApi") // Conscrypt is a stable private API
    private byte[] exportKeyingMaterial(SSLSocket sslSocket, int length) throws SSLException {
        // TLS exporter 的三种入口，按「越靠近公开 API 越先试」的顺序。
        //
        // 为什么需要三级：Android 16 的 hidden API 拦截会让第一级（Conscrypt 的
        // 4 参静态方法）在 getMethod 时直接抛 NoSuchMethodException——方法本身
        // 还在，只是 targetSdk < 系统版本时不允许反射。实测真机就是这一级挂掉，
        // 所以必须有后备路径，否则配对功能在最新系统上等于不可用。
        if (!SslUtils.isCustomConscrypt() && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Although support for conscrypt has been added in Android 5.0 (Lollipop),
            // TLS1.3 isn't supported until Android 9 (Pie).
            throw new SSLException("TLSv1.3 isn't supported on your platform. Use custom Conscrypt library instead.");
        }

        // 一级：Conscrypt.exportKeyingMaterial(SSLSocket, String, byte[], int)
        Throwable first = null;
        try {
            Class<?> conscrypt = Class.forName(conscryptClassName());
            Method method = conscrypt.getMethod("exportKeyingMaterial", SSLSocket.class,
                    String.class, byte[].class, int.class);
            return (byte[]) method.invoke(null, sslSocket, EXPORTED_KEY_LABEL, null, length);
        } catch (Throwable th) {
            first = th;
        }

        // 二级：AbstractSession.exportKeyingMaterial(String, byte[], int)，从 SSLSession 取。
        // 这是实例方法，签名与一级不同；某些实现把它放在 session 上而不是静态工具类上。
        try {
            Object session = sslSocket.getSession();
            if (session != null) {
                Method method = session.getClass().getMethod("exportKeyingMaterial",
                        String.class, byte[].class, int.class);
                return (byte[]) method.invoke(session, EXPORTED_KEY_LABEL, null, length);
            }
        } catch (Throwable ignored) {
            // 继续下一级；真正的原因由第一级的异常代表（它才是操作员看到的那个）
        }

        // 三级：在 session 的实现类里扫一遍同名方法（个别实现把它声明在中间父类上，
        // 直接 getMethod 拿不到但遍历能找到）。
        //
        // 注意：SSLSocket 没有公开的 getEngine()，所以「SSLEngine 版」这条入口
        // 在阻塞式 socket 上根本够不着，不要写进来。
        try {
            Object session = sslSocket.getSession();
            if (session != null) {
                for (Class<?> type = session.getClass(); type != null; type = type.getSuperclass()) {
                    for (Method candidate : type.getDeclaredMethods()) {
                        if (!"exportKeyingMaterial".equals(candidate.getName())) continue;
                        Class<?>[] params = candidate.getParameterTypes();
                        if (params.length != 3) continue;
                        candidate.setAccessible(true);
                        return (byte[]) candidate.invoke(session, EXPORTED_KEY_LABEL, null, length);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 三级全挂，把一级的异常抛出去：它是 hidden API 拦截的直接证据，
            // 上层 SelfAdbPlan.isHiddenApiBlock 靠它认路。
        }
        throw new SSLException(first);
    }

    private static String conscryptClassName() {
        return SslUtils.isCustomConscrypt() ? "org.conscrypt.Conscrypt" : "com.android.org.conscrypt.Conscrypt";
    }

    private void writeHeader(@NonNull PairingPacketHeader header, @NonNull byte[] payload) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(PairingPacketHeader.PAIRING_PACKET_HEADER_SIZE)
                .order(ByteOrder.BIG_ENDIAN);
        header.writeTo(buffer);

        mOutputStream.write(buffer.array());
        mOutputStream.write(payload);
    }

    @Nullable
    private PairingPacketHeader readHeader() throws IOException {
        byte[] bytes = new byte[PairingPacketHeader.PAIRING_PACKET_HEADER_SIZE];
        mInputStream.readFully(bytes);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        return PairingPacketHeader.readFrom(buffer);
    }

    @NonNull
    private PairingPacketHeader createHeader(byte type, int payloadSize) {
        return new PairingPacketHeader(PairingPacketHeader.CURRENT_KEY_HEADER_VERSION, type, payloadSize);
    }

    private boolean checkHeaderType(byte expected, byte actual) {
        if (expected != actual) {
            Log.e(TAG, "Unexpected header type (expected=" + expected + " actual=" + actual + ")");
            return false;
        }
        return true;
    }

    private boolean doExchangeMsgs() throws IOException {
        byte[] msg = mPairingAuthCtx.getMsg();

        PairingPacketHeader ourHeader = createHeader(PairingPacketHeader.SPAKE2_MSG, msg.length);
        // Write our SPAKE2 msg
        writeHeader(ourHeader, msg);

        // Read the peer's SPAKE2 msg header
        PairingPacketHeader theirHeader = readHeader();
        if (theirHeader == null || !checkHeaderType(PairingPacketHeader.SPAKE2_MSG, theirHeader.type)) return false;

        // Read the SPAKE2 msg payload and initialize the cipher for encrypting the PeerInfo and certificate.
        byte[] theirMsg = new byte[theirHeader.payloadSize];
        mInputStream.readFully(theirMsg);

        try {
            return mPairingAuthCtx.initCipher(theirMsg);
        } catch (Exception e) {
            Log.e(TAG, "Unable to initialize pairing cipher");
            //noinspection UnnecessaryInitCause
            throw (IOException) new IOException().initCause(e);
        }
    }

    private boolean doExchangePeerInfo() throws IOException {
        // Encrypt PeerInfo
        ByteBuffer buffer = ByteBuffer.allocate(PeerInfo.MAX_PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN);
        mPeerInfo.writeTo(buffer);
        byte[] outBuffer = mPairingAuthCtx.encrypt(buffer.array());
        if (outBuffer == null) {
            Log.e(TAG, "Failed to encrypt peer info");
            return false;
        }

        // Write out the packet header
        PairingPacketHeader ourHeader = createHeader(PairingPacketHeader.PEER_INFO, outBuffer.length);
        // Write out the encrypted payload
        writeHeader(ourHeader, outBuffer);

        // Read in the peer's packet header
        PairingPacketHeader theirHeader = readHeader();
        if (theirHeader == null || !checkHeaderType(PairingPacketHeader.PEER_INFO, theirHeader.type)) return false;

        // Read in the encrypted peer certificate
        byte[] theirMsg = new byte[theirHeader.payloadSize];
        mInputStream.readFully(theirMsg);

        // Try to decrypt the certificate
        byte[] decryptedMsg = mPairingAuthCtx.decrypt(theirMsg);
        if (decryptedMsg == null) {
            Log.e(TAG, "Unsupported payload while decrypting peer info.");
            return false;
        }

        // The decrypted message should contain the PeerInfo.
        if (decryptedMsg.length != PeerInfo.MAX_PEER_INFO_SIZE) {
            Log.e(TAG, "Got size=" + decryptedMsg.length + " PeerInfo.size=" + PeerInfo.MAX_PEER_INFO_SIZE);
            return false;
        }

        PeerInfo theirPeerInfo = PeerInfo.readFrom(ByteBuffer.wrap(decryptedMsg));
        Log.d(TAG, theirPeerInfo.toString());
        return true;
    }

    @Override
    public void close() {
        Arrays.fill(mPswd, (byte) 0);
        try {
            mInputStream.close();
        } catch (IOException ignore) {
        }
        try {
            mOutputStream.close();
        } catch (IOException ignore) {
        }
        if (mState != State.Ready) {
            mPairingAuthCtx.destroy();
        }
    }

    private static class PeerInfo {
        public static final int MAX_PEER_INFO_SIZE = 1 << 13;

        public static final byte ADB_RSA_PUB_KEY = 0;
        public static final byte ADB_DEVICE_GUID = 0;

        @NonNull
        public static PeerInfo readFrom(@NonNull ByteBuffer buffer) {
            byte type = buffer.get();
            byte[] data = new byte[MAX_PEER_INFO_SIZE - 1];
            buffer.get(data);
            return new PeerInfo(type, data);
        }

        private final byte type;
        private final byte[] data = new byte[MAX_PEER_INFO_SIZE - 1];

        public PeerInfo(byte type, byte[] data) {
            this.type = type;
            System.arraycopy(data, 0, this.data, 0, Math.min(data.length, MAX_PEER_INFO_SIZE - 1));
        }

        public void writeTo(@NonNull ByteBuffer buffer) {
            buffer.put(type).put(data);
        }

        @NonNull
        @Override
        public String toString() {
            return "PeerInfo{" +
                    "type=" + type +
                    ", data=" + Arrays.toString(data) +
                    '}';
        }
    }

    private static class PairingPacketHeader {
        public static final byte CURRENT_KEY_HEADER_VERSION = 1;
        public static final byte MIN_SUPPORTED_KEY_HEADER_VERSION = 1;
        public static final byte MAX_SUPPORTED_KEY_HEADER_VERSION = 1;

        public static final int MAX_PAYLOAD_SIZE = 2 * PeerInfo.MAX_PEER_INFO_SIZE;
        public static final byte PAIRING_PACKET_HEADER_SIZE = 6;

        public static final byte SPAKE2_MSG = 0;
        public static final byte PEER_INFO = 1;

        @Nullable
        public static PairingPacketHeader readFrom(@NonNull ByteBuffer buffer) {
            byte version = buffer.get();
            byte type = buffer.get();
            int payload = buffer.getInt();
            if (version < MIN_SUPPORTED_KEY_HEADER_VERSION || version > MAX_SUPPORTED_KEY_HEADER_VERSION) {
                Log.e(TAG, "PairingPacketHeader version mismatch (us=" + CURRENT_KEY_HEADER_VERSION
                        + " them=" + version + ")");
                return null;
            }
            if (type != SPAKE2_MSG && type != PEER_INFO) {
                Log.e(TAG, "Unknown PairingPacket type " + type);
                return null;
            }
            if (payload <= 0 || payload > MAX_PAYLOAD_SIZE) {
                Log.e(TAG, "Header payload not within a safe payload size (size=" + payload + ")");
                return null;
            }
            return new PairingPacketHeader(version, type, payload);
        }

        private final byte version;
        private final byte type;
        private final int payloadSize;

        public PairingPacketHeader(byte version, byte type, int payloadSize) {
            this.version = version;
            this.type = type;
            this.payloadSize = payloadSize;
        }

        public void writeTo(@NonNull ByteBuffer buffer) {
            buffer.put(version).put(type).putInt(payloadSize);
        }

        @NonNull
        @Override
        public String toString() {
            return "PairingPacketHeader{" +
                    "version=" + version +
                    ", type=" + type +
                    ", payloadSize=" + payloadSize +
                    '}';
        }
    }
}
