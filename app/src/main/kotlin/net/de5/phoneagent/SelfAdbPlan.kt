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

    /** 密钥来源的旁注文件名，只给人看，协议不读它。 */
    const val ORIGIN_NAME = "adbkey.origin"

    /**
     * adb 公钥二进制结构体的长度：3 个 uint32 + 2 × 256 字节模数/RR。
     *
     * 这个 524 不是我们定的，是 AOSP `android_pubkey.cpp` 的结构体大小；
     * 导入外部密钥时必须逐字节对上，否则手机端解出来是另一把公钥。
     */
    const val PUB_BLOB_SIZE = 524

    /** 结构体第一个 uint32 是「模数占几个 32 位字」，2048 位 = 64。 */
    const val PUB_MODULUS_WORDS = 64

    const val PEM_BEGIN = "-----BEGIN PRIVATE KEY-----"
    const val PEM_END = "-----END PRIVATE KEY-----"

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

    /**
     * adbd 的 shell 服务名。
     *
     * OPEN 的 destination 必须是 `<service>:<args>` 的完整形式，adbd 靠冒号前的
     * 名字找服务；只发命令文本（如 `id`）会匹配不到任何服务，adbd 直接回 CLSE，
     * 表现成「流被对端拒绝」——真机上就是 sh apply 全部 exit 4。
     */
    const val SERVICE_SHELL = "shell"

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
            "shkey [show] | shkey regen [apply] | shkey import URL [PUBURL] | shkey adopt"

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

    /**
     * 把用户命令包成 OPEN 的 destination：`shell:<命令>\necho __RC=$?`。
     *
     * 两件事都不能省：
     * 1) 必须以 `shell:` 开头，否则 adbd 找不到服务，直接 CLSE 拒绝 OPEN；
     * 2) 末尾补 `echo __RC=$?`，因为 shell 服务只回 stdout/stderr，协议层不带退出码。
     */
    fun shellCommand(command: String): String =
        SERVICE_SHELL + ":" + command + "\necho " + RC_MARK + "\$?"

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

    // ---------------------------------------------------------------- 导入密钥

    /**
     * 私钥 PEM 的形状检查。
     *
     * 只查「是不是 PKCS#8 PEM、base64 能不能解」——真正的密码学校验（能否
     * 解析成 RSAPrivateCrtKey、公钥是否匹配）留给 SelfAdbKeys，因为那需要
     * JCA，而这个文件要能在纯 JVM 单测里跑。
     *
     * 返回 null 表示形状没问题。
     */
    fun pemProblem(text: String?): String? {
        if (text.isNullOrBlank()) return "导入失败：私钥内容为空（URL 可能 404 或返回了 HTML）"
        if (!text.contains(PEM_BEGIN)) {
            return "导入失败：私钥不是 PKCS#8 PEM（需要 " + PEM_BEGIN + " 开头）"
        }
        if (!text.contains(PEM_END)) {
            return "导入失败：私钥 PEM 没有结束行 " + PEM_END + "（文件可能被截断）"
        }
        val der = unbase64(stripPemText(text)) ?: return "导入失败：私钥 PEM 里有非法 base64 字符"
        if (der.isEmpty()) return "导入失败：私钥 PEM 解码后是空的"
        return null
    }

    /** 去掉 PEM 的头尾行，只留 base64 正文（正文里可能夹换行，unbase64 会忽略空白）。 */
    fun stripPemText(pem: String): String = pem
        .replace(PEM_BEGIN, "")
        .replace(PEM_END, "")

    /**
     * adb 格式公钥（base64 + 空格 + 名字 + NUL）→ 524 字节结构体。
     *
     * 取第一个空白或 NUL 之前的 base64 段；后面那段名字只用于展示。
     */
    fun pubBlob(text: String?): ByteArray? {
        if (text.isNullOrBlank()) return null
        val head = text.trimStart().takeWhile { !it.isWhitespace() && it != '\u0000' }
        if (head.isEmpty()) return null
        return unbase64(head)
    }

    /** 公钥结构体的形状检查；返回 null 表示形状没问题。 */
    fun pubProblem(text: String?): String? {
        if (text.isNullOrBlank()) return "导入失败：公钥内容为空（URL 可能 404 或返回了 HTML）"
        val blob = pubBlob(text)
            ?: return "导入失败：公钥不是合法的 base64（需要 adb 的 adbkey.pub 格式）"
        if (blob.size < PUB_BLOB_SIZE) {
            return "导入失败：公钥只有 " + blob.size + " 字节，adb 结构体需要 " + PUB_BLOB_SIZE + " 字节"
        }
        // 前 4 字节是「模数占几个 32 位字」，小端；2048 位密钥固定是 64。
        val words = (blob[0].toInt() and 0xff) or
            ((blob[1].toInt() and 0xff) shl 8) or
            ((blob[2].toInt() and 0xff) shl 16) or
            ((blob[3].toInt() and 0xff) shl 24)
        if (words != PUB_MODULUS_WORDS) {
            return "导入失败：公钥模数长度是 " + words + " 字，不是 2048 位（" + PUB_MODULUS_WORDS + " 字）"
        }
        return null
    }

    /** 公钥里带的名字（adbkey.pub 空格后那段），没有就回 null。 */
    fun pubName(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trimStart()
        val cut = trimmed.indexOfFirst { it.isWhitespace() }
        if (cut <= 0) return null
        return trimmed.substring(cut + 1).trim().trim('\u0000').ifBlank { null }
    }

    /** 密钥来源：自生成还是导入。写进 adbkey.origin 供 shkey show 显示。 */
    fun originLabel(origin: String?): String = when (origin?.trim()?.lowercase()) {
        "imported" -> "导入"
        "generated" -> "自生成"
        null, "" -> "未知（旧版本留下的密钥）"
        else -> origin.trim()
    }

    fun importOk(name: String?, fingerprint: String?): String =
        "已导入密钥 " + (name ?: DEVICE_NAME) +
            (if (fingerprint.isNullOrBlank()) "" else " 指纹 " + fingerprint) +
            "\n现在可以执行 shconnect（这把密钥若已在手机上授权，就不需要再配对）"

    fun importFailed(why: String): String = "导入失败：" + why

    /** shkey show：来源 + 公钥 + 指纹，三行，只读。 */
    fun showReport(origin: String?, publicLine: String?, fingerprint: String?): String {
        val sb = StringBuilder()
        sb.append("来源: ").append(originLabel(origin)).append('\n')
        sb.append("公钥: ").append(if (publicLine.isNullOrBlank()) "(未生成)" else publicLine).append('\n')
        sb.append("指纹: ").append(if (fingerprint.isNullOrBlank()) "(未生成)" else fingerprint)
        return sb.toString()
    }

    /**
     * shkey adopt：把本机公钥塞进手机的授权表。
     *
     * 为什么不做：/data/misc/adb/adb_keys 属于 adbd 的私有目录，user 版
     * 系统上连 uid 2000 都进不去（lead 真机实测 ls -l /data/misc/adb/ 直接
     * Permission denied，getprop ro.build.type 回 user）。所以这条路在非
     * 工程机上没有实现，只留一条能指路的说明。
     */
    fun adoptUnsupported(): String =
        "shkey adopt 在本机不可用：/data/misc/adb/adb_keys 属于 adbd 私有目录，" +
            "user 版系统上 uid 2000 也读不到（真机实测 Permission denied）。" +
            "请改用 shkey import <url> 导入一把已被手机授权的密钥。"

    /**
     * 配对被 hidden API 拦死时的定论文案。
     *
     * 这条必须能指路：操作员看到 NoSuchMethodException 只会以为是我们代码
     * 写错了，而真正的原因是本机系统禁止非 SDK 接口。
     */
    fun hiddenApiBlocked(): String =
        "配对失败：本机系统禁止非 SDK 接口（exportKeyingMaterial）。" +
            "请改用 shkey import <url> 导入一把已被手机授权的密钥。"

    /** 异常链里是否有「exportKeyingMaterial 被 hidden API 拦掉」的痕迹。 */
    fun isHiddenApiBlock(t: Throwable): Boolean {
        var node: Throwable? = t
        var depth = 0
        while (node != null && depth < 8) {
            val name = node.javaClass.simpleName
            val message = node.message ?: ""
            val reflective = name == "NoSuchMethodException" ||
                name == "IllegalAccessException" ||
                name == "NoSuchFieldException"
            if (reflective && message.contains("exportKeyingMaterial")) return true
            if (message.contains("exportKeyingMaterial") && message.contains("NoSuchMethod")) return true
            node = node.cause
            depth++
        }
        return false
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

            // 同一类异常两种成因，必须分开报：
            // ① adbd 收到 OPEN 后直接回 CLSE（服务名/参数不对），TCP 其实是通的；
            // ② 端口根本连不上（无线调试关了 / adbd 重启换了端口）。
            name == "ConnectException" && message.contains("actively rejected") ->
                "adbd 拒绝了这条命令（服务名不被接受）：执行 shinfo 确认连接状态，再重试；反复出现请执行 shkey show 核对密钥指纹"

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
        sb.append("命令: ").append(USAGE.removePrefix("usage: ")).append('\n')
        sb.append("最近: ").append(if (lastNote.isNullOrBlank()) "(本次进程内无记录)" else lastNote)
        return sb.toString()
    }
}
