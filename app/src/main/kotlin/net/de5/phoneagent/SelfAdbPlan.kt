package net.de5.phoneagent

/**
 * `sh` 系列（自连 ADB）的纯逻辑部分。
 *
 * 这里刻意不 import 任何 android.*，所以整套文案、钳制、哨兵解析都能在
 * JVM 单测里跑——真机上最容易出错的恰恰是这些字符串处理，而不是 TLS。
 */
internal object SelfAdbPlan {

    /** 密钥与证书放在 filesDir 下的这个子目录，和 prefs 分开放。 */
    const val KEY_DIR = "adb"

    /** 私钥文件名（PKCS#8 PEM），AOSP 的 adb 也用这个文件名，方便对账。 */
    const val KEY_NAME = "adbkey"

    /** adb 格式公钥文件名：`<base64> <name>\0`。 */
    const val PUB_NAME = "adbkey.pub"

    /**
     * 只连回环地址。
     *
     * 手机自己就是 adbd 所在的主机，走 127.0.0.1 既不需要局域网权限，也不会
     * 因为 Wi-Fi 换网而断——连局域网 IP 反而会在切网时静默失败。
     */
    const val HOST = "127.0.0.1"

    /** 写进 adbkey.pub 的名字，手机「已配对设备」列表里显示的就是它。 */
    const val DEVICE_NAME = "phone-agent@dsh"

    /** 无线调试的两个 mDNS 服务名（AOSP 的常量，别改）。 */
    const val SERVICE_PAIRING = "adb-tls-pairing"
    const val SERVICE_CONNECT = "adb-tls-connect"

    const val SHELL_TIMEOUT_DEFAULT_MS = 30_000L
    const val SHELL_TIMEOUT_MIN_MS = 1_000L
    const val SHELL_TIMEOUT_MAX_MS = 300_000L

    /** 单次 `sh` 回给 relay 的输出上限；超了截断并注明，绝不静默丢。 */
    const val SHELL_OUT_MAX = 8000

    /**
     * 退出码哨兵。
     *
     * 为什么需要它：adb 的 `shell:` 服务只把 stdout/stderr 混在一个流里回，
     * 协议层不带退出码。所以在命令末尾补一句 `echo __RC=$?`，让 shell 自己
     * 把码打出来，再按「最后一次出现」把它剥掉——命令文本里即使自带
     * `__RC=` 字样也不会被误判。
     */
    const val RC_MARK = "__RC="

    const val PAIR_CODE_LENGTH = 6

    /** 配对/连接的发现预算：一轮 mDNS 4 秒，最多五轮。 */
    const val PAIR_BUDGET_MS = 20_000L
    const val CONNECT_BUDGET_MS = 20_000L
    const val DISCOVER_ROUND_MS = 4_000L

    /** `sh` 家族的用法串，Commands 与文档共用一份，避免文案漂移。 */
    const val USAGE =
        "usage: shinfo | sh [t=SECONDS] [apply] COMMAND | shpair 123456 | shconnect | " +
            "shkey | shkey regen [apply]"

    /**
     * 一次 shell 调用的结果。
     *
     * [code] 为 null 表示没拿到退出码（流被截断或根本没跑起来）；
     * [precondition] 为 true 表示卡在「还没配对/还没连接」这类前置条件上——
     * 这必须和「命令跑超时」分开报，否则操作员会去查命令而不是查连接。
     */
    internal data class Exit(
        val code: Int?,
        val text: String,
        val precondition: Boolean = false
    )

    // ---------------------------------------------------------------- 参数

    /**
     * 解析 `t=SECONDS`。不合法返回 null，由调用方回 usage——静默钳制会让
     * 操作员以为自己的参数生效了。
     */
    fun parseTimeout(text: String): Long? {
        if (!text.startsWith("t=")) return null
        val raw = text.substring(2)
        val seconds = raw.toLongOrNull() ?: return null
        if (seconds <= 0L) return null
        return seconds * 1000L
    }

    /** 把毫秒钳进 1..300 秒；null 用默认 30 秒。 */
    fun clampTimeout(millis: Long?): Long {
        if (millis == null) return SHELL_TIMEOUT_DEFAULT_MS
        return millis.coerceIn(SHELL_TIMEOUT_MIN_MS, SHELL_TIMEOUT_MAX_MS)
    }

    /** 六位配对码：无线调试里显示的永远是六位数字。 */
    fun validPairCode(code: String): Boolean =
        code.length == PAIR_CODE_LENGTH && code.all { it in '0'..'9' }

    // ---------------------------------------------------------------- 哨兵

    /** 把用户命令包成「跑完再回显退出码」的一行脚本。 */
    fun shellCommand(command: String): String = command + "\necho " + RC_MARK + "\$?"

    /**
     * 剥出退出码。
     *
     * 规则：取**最后一次**出现的 `__RC=<digits>`，且它后面只允许空白；
     * 其余情况一律当作没拿到码（[Exit.code] 为 null），把原文原样交回去，
     * 由上层按「未知退出码」处理，而不是猜一个 0 骗自己。
     */
    fun parseExit(raw: String): Exit {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val marker = text.lastIndexOf(RC_MARK)
        if (marker < 0) return Exit(null, text)
        var index = marker + RC_MARK.length
        val digitsStart = index
        while (index < text.length && text[index] in '0'..'9') index++
        if (index == digitsStart) return Exit(null, text)
        if (text.substring(index).isNotBlank()) return Exit(null, text)
        val code = text.substring(digitsStart, index).toIntOrNull() ?: return Exit(null, text)
        return Exit(code, text.substring(0, marker))
    }

    /** 超长输出截断并注明；否则原样返回（保留换行，别替操作员改格式）。 */
    fun output(text: String): String {
        if (text.length <= SHELL_OUT_MAX) return text
        return text.substring(0, SHELL_OUT_MAX) +
            "\n… 输出被截断（原 " + text.length + " 字符，上限 " + SHELL_OUT_MAX + "）"
    }

    // ---------------------------------------------------------------- 文案

    fun dryRun(command: String): String = "dry-run: would run sh: " + command

    fun notPaired(): String =
        "尚未配对：手机 设置 → 系统 → 开发者选项 → 无线调试 → 使用配对码配对设备，" +
            "拿到六位码后执行 shpair 123456"

    fun notConnected(): String =
        "尚未连接 adb：配对成功后执行 shconnect（配对只交换密钥，连接是另一步）"

    fun noPort(service: String): String =
        "没发现 " + service + " 端口：确认无线调试已打开，等 3-5 秒重试（部分 ROM 会过滤 mDNS 广播）"

    fun timeoutText(millis: Long): String = "命令超时（" + (millis / 1000L) + "s 内没有返回）"

    fun badPairCode(): String = "配对码必须是六位数字"

    fun keyFailed(): String =
        "密钥生成失败：检查 filesDir/adb 是否可写（" + KEY_DIR + "/" + KEY_NAME + "）"

    fun pairOk(): String = "配对成功，密钥已写入手机；接着执行 shconnect 建立连接"

    fun pairFailed(why: String): String = "配对失败: " + why

    fun connectOk(): String = "已连上本机 adbd（uid 2000），现在可以执行 sh apply COMMAND"

    fun connectFailed(why: String): String = "连接失败: " + why

    /** `shkey` 的对账行：公钥原文 + 指纹，手机「已配对的设备」里能逐字比。 */
    fun keyReport(publicLine: String?, fingerprint: String?): String {
        val sb = StringBuilder()
        sb.append("公钥: ").append(if (publicLine.isNullOrBlank()) "(未生成)" else publicLine).append('\n')
        sb.append("指纹: ").append(if (fingerprint.isNullOrBlank()) "(未生成)" else fingerprint)
        return sb.toString()
    }

    fun regenDryRun(): String =
        "dry-run: 会删除现有密钥并重新生成，配对关系将失效，确认请加 apply"

    fun regenDone(fingerprint: String?): String =
        "已重新生成密钥" +
            (if (fingerprint.isNullOrBlank()) "" else "，新指纹 " + fingerprint) +
            "；配对关系已失效，请在手机「已配对的设备」里删掉旧记录，再执行 shpair 六位码"

    fun shellOk(exit: Int, text: String): String =
        if (text.isEmpty()) "exit=" + exit else text + "exit=" + exit

    // ---------------------------------------------------------------- 密钥

    /**
     * 指纹格式与 AOSP `adb keygen` 打印的一致：`SHA256:` + base64（去尾部 =）。
     * 手机「已配对的设备」里显示的就是这一串，方便逐字对账。
     */
    fun fingerprint(sha256: ByteArray): String = "SHA256:" + base64(sha256, pad = false)

    /** 标准 base64；纯 Kotlin 实现，免得为了一个指纹把 android.util 拖进单测。 */
    fun base64(bytes: ByteArray, pad: Boolean = true): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xff) shl 16) or
                ((bytes[i + 1].toInt() and 0xff) shl 8) or
                (bytes[i + 2].toInt() and 0xff)
            out.append(table[(n shr 18) and 0x3f])
            out.append(table[(n shr 12) and 0x3f])
            out.append(table[(n shr 6) and 0x3f])
            out.append(table[n and 0x3f])
            i += 3
        }
        val rest = bytes.size - i
        if (rest == 1) {
            val n = (bytes[i].toInt() and 0xff) shl 16
            out.append(table[(n shr 18) and 0x3f])
            out.append(table[(n shr 12) and 0x3f])
            if (pad) out.append("==")
        } else if (rest == 2) {
            val n = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8)
            out.append(table[(n shr 18) and 0x3f])
            out.append(table[(n shr 12) and 0x3f])
            out.append(table[(n shr 6) and 0x3f])
            if (pad) out.append("=")
        }
        return out.toString()
    }

    /** 标准 base64 解码；遇到任何非法字符返回 null，绝不猜。 */
    fun unbase64(text: String): ByteArray? {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean = text.filter { !it.isWhitespace() }.trimEnd('=')
        if (clean.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(clean.length / 4 * 3 + 3)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val value = table.indexOf(c)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- 异常

    /**
     * 把异常翻成人话。
     *
     * 按 simpleName 而不是 instanceof 匹配：vendored 的异常类都在别的包里，
     * 用名字匹配可以让这个纯逻辑文件保持零依赖，同时单测能直接构造同名异常。
     */
    fun errorText(t: Throwable): String {
        val name = t.javaClass.simpleName
        val message = t.message ?: ""
        return when {
            name == "AdbPairingRequiredException" ->
                "设备要求先配对：执行 shpair 六位码（手机 无线调试 → 使用配对码配对设备）"

            name == "AdbAuthenticationFailedException" ->
                "设备拒绝了这把密钥：在手机「已配对的设备」里删掉旧的 phone-agent 记录，再执行 shkey regen apply 与 shpair"

            message.contains("Timed out while trying to find") ||
                message.contains("Could not find any valid host address") ->
                "没发现 adb 端口：确认无线调试已打开，等 3-5 秒重试（部分 ROM 会过滤 mDNS 广播）"

            message.contains("Not connected to ADB") -> notConnected()

            message.contains("Stream closed") -> "连接被对端关闭：重新执行 shconnect"

            message.contains("Connection refused") -> "端口拒绝连接：adbd 可能刚重启，执行 shconnect 重试"

            message.contains("protocol error") ->
                "TLS 握手被拒：这把密钥还没在手机上授权，执行 shpair 六位码"

            name == "SSLException" || name == "SSLHandshakeException" ->
                "TLS 握手失败: " + (if (message.isEmpty()) "证书或密钥不被接受" else message)

            name == "SocketTimeoutException" -> "连接超时：adbd 没有应答，执行 shconnect 重试"

            name == "ConnectException" -> "连不上 adbd 端口：无线调试可能已关闭"

            name == "InterruptedException" -> "等待被中断"

            message.isEmpty() -> name
            else -> name + ": " + message
        }
    }

    // ---------------------------------------------------------------- shinfo

    /**
     * `shinfo` 的多行报告。全部是读操作，不改任何状态，所以可以随时打。
     */
    fun status(hasKey: Boolean, fingerprint: String?, connected: Boolean, lastNote: String?): String {
        val sb = StringBuilder()
        sb.append("自连 ADB: ").append(if (connected) "已连接" else "未连接").append('\n')
        if (hasKey) {
            sb.append("密钥: 已生成（").append(KEY_DIR).append('/').append(KEY_NAME).append("）")
            if (!fingerprint.isNullOrBlank()) sb.append(" 指纹 ").append(fingerprint)
            sb.append('\n')
        } else {
            sb.append("密钥: 未生成（执行 shkey 或 shpair 会自动生成）\n")
        }
        sb.append("地址: ").append(HOST).append("（只连本机回环，不连局域网 IP）\n")
        sb.append("配对服务: ").append(SERVICE_PAIRING).append(" / 连接服务: ").append(SERVICE_CONNECT).append('\n')
        sb.append("命令: sh [t=SECONDS] [apply] COMMAND | shpair 123456 | shconnect | shkey [regen [apply]]\n")
        sb.append("最近: ").append(if (lastNote.isNullOrBlank()) "(本次进程内无记录)" else lastNote)
        return sb.toString()
    }
}
