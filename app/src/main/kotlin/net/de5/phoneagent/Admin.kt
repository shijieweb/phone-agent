package net.de5.phoneagent

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * Everything that needs more than an ordinary app's authority, in one place.
 *
 * ## What is actually reachable from a sideloaded app
 *
 * Two very different things get called "privileged" on Android, and confusing
 * them is how a feature ends up looking implemented but never working:
 *
 *  - `signature|privileged` (INSTALL_PACKAGES, DELETE_PACKAGES, REBOOT): only
 *    a platform-signed app or one in `/system/priv-app` gets these. Being
 *    **Device Owner is not enough** and `pm grant` is refused, because the
 *    permission carries no `development` protection flag. This is why the
 *    install/uninstall paths here go through the system confirmation UI instead
 *    of pretending a silent session could work.
 *  - `signature|privileged|development` (WRITE_SECURE_SETTINGS, READ_LOGS, DUMP,
 *    PACKAGE_USAGE_STATS): also privileged, but the `development` flag lets
 *    `adb shell pm grant` hand them to a normally-installed app. These are the
 *    ones we actually build on, and each one is checked at runtime.
 *
 * Contract (the caller relies on it):
 *  - every entry point returns the **text to report to the relay**;
 *  - a failure returns a string starting with "错误: " and a human-readable
 *    reason (the operator is on the other end of a chat, not a debugger);
 *  - nothing here ever throws. A privileged call on a phone that does not hold
 *    the grant throws SecurityException, and that must become a sentence, not a
 *    crash of the poll loop.
 */
internal object Admin {

    /** Every failure text starts with this, so callers can map it to an exit code. */
    const val ERROR_PREFIX = "错误: "

    private const val LOGCAT_TIMEOUT_SECONDS = 10L

    /** logcat is chatty and the relay truncates stdout: stop reading early. */
    private const val LOGCAT_MAX_LINES = 200

    /** The privileged permissions that `pm grant` can actually hand us. */
    private val DEVELOPMENT_PERMISSIONS = listOf(
        android.Manifest.permission.WRITE_SECURE_SETTINGS,
        android.Manifest.permission.READ_LOGS,
        android.Manifest.permission.DUMP,
        android.Manifest.permission.PACKAGE_USAGE_STATS
    )

    private fun error(message: String): String = ERROR_PREFIX + message

    // ------------------------------------------------------- role and grants

    fun isDeviceOwner(ctx: Context): Boolean = try {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        dpm != null && dpm.isDeviceOwnerApp(ctx.packageName)
    } catch (t: Throwable) {
        false
    }

    fun isAdminActive(ctx: Context): Boolean = try {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        dpm != null && dpm.isAdminActive(component(ctx))
    } catch (t: Throwable) {
        false
    }

    private fun component(ctx: Context): ComponentName =
        ComponentName(ctx.packageName, AgentDeviceAdmin::class.java.name)

    private fun dpm(ctx: Context): DevicePolicyManager? = try {
        ctx.getSystemService(DevicePolicyManager::class.java)
    } catch (t: Throwable) {
        null
    }

    /**
     * One-line authority report, used by `admin status`.
     *
     * Every field is measured, never assumed: "we declared the permission" and
     * "we hold the permission" are different facts, and only the second one
     * decides whether a command works.
     */
    fun stateText(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("deviceOwner=").append(isDeviceOwner(ctx))
        sb.append(" adminActive=").append(isAdminActive(ctx))
        for (permission in DEVELOPMENT_PERMISSIONS) {
            sb.append(' ')
                .append(shortName(permission))
                .append('=')
                .append(granted(ctx, permission))
        }
        sb.append(" installUnknownApps=").append(canRequestInstall(ctx))
        sb.append(" a11yListed=").append(isAccessibilityListed(ctx))
        return sb.toString()
    }

    /** A multi-line version for the operator, who wants it readable. */
    fun stateReport(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("Device Owner: ").append(if (isDeviceOwner(ctx)) "是" else "否")
        sb.append("\n设备管理器: ").append(if (isAdminActive(ctx)) "已激活" else "未激活")
        sb.append("\n静默安装: 不可用（INSTALL_PACKAGES 为 signature|privileged，无 development 位）")
        sb.append("\n安装未知应用: ").append(if (canRequestInstall(ctx)) "已允许" else "未允许")
        sb.append("\n特权权限:")
        for (permission in DEVELOPMENT_PERMISSIONS) {
            sb.append("\n  ").append(shortName(permission))
                .append('=').append(if (granted(ctx, permission)) "已授予" else "未授予")
        }
        sb.append("\n无障碍条目: ").append(if (isAccessibilityListed(ctx)) "已列出" else "缺失")
        sb.append(extendedReport(ctx))
        return sb.toString()
    }

    /**
     * The v0.4.0 surface, grouped by **how the grant is actually given**.
     *
     * The grouping is the whole point: an operator who runs `pm grant` on
     * MANAGE_EXTERNAL_STORAGE gets a silent success and no capability, and then
     * reports the app as broken. These three lists are the three mechanisms, and
     * [extendedReport] prints one line per entry with the measured state.
     */
    private val PM_GRANT_PERMISSIONS = listOf(
        android.Manifest.permission.READ_SMS,
        android.Manifest.permission.SEND_SMS,
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.READ_CALL_LOG,
        android.Manifest.permission.CALL_PHONE,
        android.Manifest.permission.ANSWER_PHONE_CALLS,
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.READ_CALENDAR,
        android.Manifest.permission.ACCESS_FINE_LOCATION
    )

    /** An app-op: `pm grant` accepts the command and changes nothing. */
    private const val APPOP_MANAGE_STORAGE = "MANAGE_EXTERNAL_STORAGE"

    /**
     * The readable-by-measurement part of the extended surface.
     *
     * Each line is "name=已授予/未授予（怎么给）" so the operator can act without
     * a second document: the hint is the exact adb command.
     */
    fun extendedReport(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("\n扩展权限:")
        for (permission in PM_GRANT_PERMISSIONS) {
            sb.append("\n  ").append(shortName(permission))
                .append('=').append(if (granted(ctx, permission)) "已授予" else "未授予")
        }
        sb.append("\n  所有文件访问=").append(if (hasAllFilesAccess(ctx)) "已开启" else "未开启")
            .append("（appops: adb shell appops set --uid ").append(ctx.packageName)
            .append(' ').append(APPOP_MANAGE_STORAGE).append(" allow）")
        sb.append("\n  悬浮窗=").append(if (canDrawOverlays(ctx)) "已允许" else "未允许")
            .append("（adb shell appops set ").append(ctx.packageName)
            .append(" SYSTEM_ALERT_WINDOW allow）")
        sb.append("\n  勿扰权限=").append(if (hasDndAccess(ctx)) "已授予" else "未授予")
            .append("（adb shell cmd notification allow_dnd ").append(ctx.packageName).append(')')
        sb.append("\n  通知使用权=").append(if (isNotificationListenerEnabled(ctx)) "已开启" else "未开启")
            .append("（adb shell cmd notification allow_listener ").append(ctx.packageName)
            .append('/').append(ctx.packageName).append(".NotifListenerService）")
        sb.append("\n  短信/通话权限只能用 pm grant，所有文件访问只能用 appops，")
        sb.append("通知使用权与勿扰只能在设置里开（或上面的 cmd notification）")
        return sb.toString()
    }

    /** MANAGE_EXTERNAL_STORAGE is an app-op; Environment answers it directly. */
    fun hasAllFilesAccess(ctx: Context): Boolean = try {
        android.os.Environment.isExternalStorageManager()
    } catch (t: Throwable) {
        false
    }

    fun canDrawOverlays(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (t: Throwable) {
        false
    }

    fun hasDndAccess(ctx: Context): Boolean = try {
        val manager = ctx.getSystemService(android.app.NotificationManager::class.java)
        manager != null && manager.isNotificationPolicyAccessGranted
    } catch (t: Throwable) {
        false
    }

    /** True when our listener component appears in the enabled listener list. */
    fun isNotificationListenerEnabled(ctx: Context): Boolean = try {
        val flat = Settings.Secure.getString(
            ctx.contentResolver,
            "enabled_notification_listeners"
        ) ?: ""
        flat.contains(ctx.packageName)
    } catch (t: Throwable) {
        false
    }

    private fun shortName(permission: String): String =
        permission.substringAfterLast('.').lowercase()

    /** True when WRITE_SECURE_SETTINGS was actually granted. */
    fun canWriteSecure(ctx: Context): Boolean =
        granted(ctx, android.Manifest.permission.WRITE_SECURE_SETTINGS)

    fun granted(ctx: Context, permission: String): Boolean = try {
        ctx.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** "Install unknown apps" is an app-op, not a runtime grant — ask the framework. */
    fun canRequestInstall(ctx: Context): Boolean = try {
        ctx.packageManager.canRequestPackageInstalls()
    } catch (t: Throwable) {
        false
    }

    /**
     * Drop Device Owner and our own admin registration.
     *
     * clearDeviceOwnerApp only works for the current owner, and only once: after
     * it the app can never become owner again without a factory reset, so the
     * reported text says so explicitly.
     */
    fun releaseOwner(ctx: Context): String {
        val manager = dpm(ctx) ?: return error("无法取得 DevicePolicyManager")
        val sb = StringBuilder()
        try {
            if (manager.isDeviceOwnerApp(ctx.packageName)) {
                manager.clearDeviceOwnerApp(ctx.packageName)
                sb.append("已清除 Device Owner（如需再次启用必须恢复出厂设置）")
            } else {
                sb.append("本来就不是 Device Owner")
            }
        } catch (t: Throwable) {
            return error("清除 Device Owner 失败: " + describe(t))
        }
        try {
            if (manager.isAdminActive(component(ctx))) {
                manager.removeActiveAdmin(component(ctx))
                sb.append("；已移除设备管理器")
            }
        } catch (t: Throwable) {
            sb.append("；移除设备管理器失败: ").append(describe(t))
        }
        AgentLog.add("admin", sb.toString())
        return sb.toString()
    }

    // ------------------------------------------------------ install/uninstall

    /**
     * Push the cached APK at the system installer UI.
     *
     * There is no silent path from a sideloaded app (see the class comment), so
     * the honest design is: start the installer with our file, report "needs one
     * tap", and let the agent press that tap with `confirm`. The full automation
     * is `install <url>` followed by `confirm 安装`.
     *
     * The APK is shared as `content://` through [ApkProvider], never as
     * `file://`: since Android 7 a file:// URI in an Intent is a
     * FileUriExposedException, and the URI grant flag only works for content.
     */
    fun launchInstall(ctx: Context, apkPath: String): String {
        val file = File(apkPath)
        if (!file.isFile) return error("apk 不存在: " + apkPath)
        if (file.length() <= 0L) return error("apk 为空: " + apkPath)
        if (!looksLikeApk(file)) return error("不是 apk（缺少 PK 头）: " + apkPath)

        // Without this app-op the system installer shows its own warning screen
        // and refuses, which looks like our bug if we do not check it here.
        if (!canRequestInstall(ctx)) {
            return error(
                "未允许「安装未知应用」：请在系统设置里为本应用打开该开关后重试"
            )
        }

        return try {
            val uri = ApkProvider.uriFor(ctx, file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, APK_MIME)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            AgentLog.add("install", "已拉起安装确认页 " + file.name)
            "已拉起安装确认页（需确认安装）：" + file.name
        } catch (t: Throwable) {
            error("拉起安装界面失败: " + describe(t))
        }
    }

    /**
     * Ask the system to uninstall [pkg], via its confirmation UI.
     *
     * DELETE_PACKAGES is signature-only, so this is the only route. A package
     * that is already gone is success, not an error — a retried script should
     * converge instead of failing forever.
     */
    fun launchUninstall(ctx: Context, pkg: String): String {
        if (pkg == ctx.packageName) return error("不能卸载自己")
        if (!isInstalled(ctx, pkg)) return "包未安装: " + pkg + "（无需卸载）"
        return try {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            AgentLog.add("uninstall", "已拉起卸载确认页 " + pkg)
            "已拉起卸载确认页（需确认卸载）：" + pkg
        } catch (t: Throwable) {
            error("拉起卸载界面失败: " + describe(t))
        }
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (t: Throwable) {
        false
    }

    /** An APK is a ZIP: the first two bytes are always "PK". */
    fun looksLikeApk(file: File): Boolean = try {
        FileInputStream(file).use { input ->
            val head = ByteArray(2)
            input.read(head) == 2 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
        }
    } catch (t: Throwable) {
        false
    }

    /** The MIME type the package installer claims. */
    const val APK_MIME = "application/vnd.android.package-archive"

    // --------------------------------------------------------------- settings

    /**
     * settings global|secure|system KEY [VALUE].
     *
     * Write order matters: a Device Owner's DevicePolicyManager.setGlobalSetting
     * is the supported path for "global" and survives enterprise policy, so we try
     * it first; everything else goes through Settings with WRITE_SECURE_SETTINGS.
     *
     * Reading works for any app with no special permission.
     */
    fun setSetting(ctx: Context, namespace: String, key: String, value: String?): String {
        val space = namespace.lowercase()
        return try {
            if (value == null) {
                val current = readSetting(ctx, space, key)
                return key + " = " + (current ?: "(null)")
            }
            if (space == "global" && isDeviceOwner(ctx)) {
                val manager = dpm(ctx)
                if (manager != null) {
                    manager.setGlobalSetting(component(ctx), key, value)
                    return "已设置 global " + key + " = " + value + "（DevicePolicyManager）"
                }
            }
            if (!canWriteSecure(ctx)) {
                return error(
                    "写入需要 WRITE_SECURE_SETTINGS（未授予：先执行 adb shell pm grant " +
                        ctx.packageName + " android.permission.WRITE_SECURE_SETTINGS）"
                )
            }
            when (space) {
                "global" -> Settings.Global.putString(ctx.contentResolver, key, value)
                "secure" -> Settings.Secure.putString(ctx.contentResolver, key, value)
                "system" -> Settings.System.putString(ctx.contentResolver, key, value)
                else -> return error("未知命名空间: " + namespace)
            }
            val now = readSetting(ctx, space, key)
            "已设置 " + space + " " + key + " = " + (now ?: value)
        } catch (t: SecurityException) {
            error("无权限写入 " + space + " " + key + "（" + describe(t) + "）")
        } catch (t: Throwable) {
            error("写入失败: " + describe(t))
        }
    }

    private fun readSetting(ctx: Context, space: String, key: String): String? = when (space) {
        "global" -> Settings.Global.getString(ctx.contentResolver, key)
        "secure" -> Settings.Secure.getString(ctx.contentResolver, key)
        "system" -> Settings.System.getString(ctx.contentResolver, key)
        else -> null
    }

    // ----------------------------------------------------------------- logcat

    /**
     * logs [n] — a logcat tail.
     *
     * READ_LOGS is signature|development: grantable with `pm grant`, never with
     * Device Owner alone. Without it logcat prints its own permission error and we
     * report that text as-is rather than guessing.
     *
     * Reading stops after LOGCAT_MAX_LINES lines: the relay truncates stdout at
     * 190 000 chars and a chatty log would push the useful part out.
     */
    fun readLogcat(lines: Int): String {
        val wanted = lines.coerceIn(1, LOGCAT_MAX_LINES * 2)
        var process: Process? = null
        val collected = ArrayList<String>(LOGCAT_MAX_LINES)
        return try {
            process = ProcessBuilder("logcat", "-d", "-t", wanted.toString()).start()
            val output = process.inputStream.bufferedReader()
            val reader = Thread {
                try {
                    var line = output.readLine()
                    while (line != null && collected.size < LOGCAT_MAX_LINES) {
                        synchronized(collected) { collected.add(line) }
                        line = output.readLine()
                    }
                } catch (t: Throwable) {
                    // The process was killed or the pipe closed: keep what we have.
                }
            }
            reader.isDaemon = true
            reader.start()
            if (!process.waitFor(LOGCAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                try {
                    process.destroy()
                } catch (t: Throwable) {
                    // ignore
                }
            }
            reader.join(1000L)
            val text = synchronized(collected) { collected.toList() }
            if (text.isEmpty()) {
                return error(
                    "logcat 无输出（READ_LOGS 未授予：adb shell pm grant " +
                        "net.de5.phoneagent android.permission.READ_LOGS）"
                )
            }
            val joined = text.joinToString("\n")
            if (text.size >= LOGCAT_MAX_LINES) {
                joined + "\n(logcat 已截断到 " + LOGCAT_MAX_LINES + " 行)"
            } else {
                joined
            }
        } catch (t: Throwable) {
            try {
                process?.destroy()
            } catch (inner: Throwable) {
                // ignore
            }
            error("读取 logcat 失败: " + describe(t))
        }
    }

    // ---------------------------------------------------------- accessibility

    /** Our accessibility component, in the flat form the settings store expects. */
    const val A11Y_COMPONENT =
        "net.de5.phoneagent/net.de5.phoneagent.PhoneAccessibilityService"

    /**
     * Re-write ENABLED_ACCESSIBILITY_SERVICES to include us.
     *
     * Android silently drops an accessibility service after a crash loop or an
     * aggressive battery manager, and re-enabling it normally means walking the
     * system settings UI on a phone nobody is holding. With WRITE_SECURE_SETTINGS
     * we can append ourselves to the existing list instead — every other entry is
     * preserved verbatim, because wiping the list would break the operator's
     * other accessibility apps.
     */
    fun enableAccessibilitySelfHeal(ctx: Context): String {
        if (!canWriteSecure(ctx)) {
            return error(
                "自愈需要 WRITE_SECURE_SETTINGS（未授予：先执行 adb shell pm grant " +
                    ctx.packageName + " android.permission.WRITE_SECURE_SETTINGS）"
            )
        }
        return try {
            val current = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val entries = current.split(':').filter { it.isNotBlank() }
            if (entries.any { it.equals(A11Y_COMPONENT, ignoreCase = true) }) {
                val enabled = Settings.Secure.getInt(
                    ctx.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                )
                if (enabled != 1) {
                    Settings.Secure.putInt(
                        ctx.contentResolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        1
                    )
                    return "无障碍条目已存在，已重新打开总开关"
                }
                return "无障碍条目已存在"
            }
            val updated = if (entries.isEmpty()) {
                A11Y_COMPONENT
            } else {
                entries.joinToString(":") + ":" + A11Y_COMPONENT
            }
            Settings.Secure.putString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                updated
            )
            Settings.Secure.putInt(
                ctx.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            AgentLog.add("a11y", "已补写无障碍服务条目")
            "已补写无障碍服务条目（原有 " + entries.size + " 条保留）"
        } catch (t: SecurityException) {
            error("写无障碍设置被拒绝（无 WRITE_SECURE_SETTINGS）")
        } catch (t: Throwable) {
            error("无障碍自愈失败: " + describe(t))
        }
    }

    /**
     * Force the accessibility service to reconnect.
     *
     * An entry that is already in the list can still be dead: Android drops the
     * binding after a crash loop while leaving the setting in place, and nothing
     * short of toggling the component re-creates it. Toggling the list entry and
     * the master switch is what walking the settings UI would do.
     */
    fun forceReconnectAccessibility(ctx: Context): String {
        if (!canWriteSecure(ctx)) {
            return error("自愈需要 WRITE_SECURE_SETTINGS（未授予）")
        }
        return try {
            val resolver = ctx.contentResolver
            val current = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val entries = current.split(':')
                .filter { it.isNotBlank() }
                .filterNot { it.equals(A11Y_COMPONENT, ignoreCase = true) }
            val without = entries.joinToString(":")
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                without
            )
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            val back = if (entries.isEmpty()) {
                A11Y_COMPONENT
            } else {
                entries.joinToString(":") + ":" + A11Y_COMPONENT
            }
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                back
            )
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            AgentLog.add("a11y", "已强制重连无障碍服务")
            "已强制重连无障碍服务（保留原有 " + entries.size + " 条）"
        } catch (t: SecurityException) {
            error("写无障碍设置被拒绝（无 WRITE_SECURE_SETTINGS）")
        } catch (t: Throwable) {
            error("强制重连失败: " + describe(t))
        }
    }

    /**
     * True when the accessibility master switch is on.
     *
     * Separate from [isAccessibilityListed] because the two fail independently:
     * a listed entry with the switch off is a phone where nothing is bound, and
     * the repair ("turn the switch back on") is different from the repair for a
     * missing entry ("append ourselves back to the list").
     */
    fun accessibilityEnabled(ctx: Context): Boolean = try {
        Settings.Secure.getInt(
            ctx.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1
    } catch (t: Throwable) {
        false
    }

    /** True when our accessibility service is listed in secure settings. */
    fun isAccessibilityListed(ctx: Context): Boolean = try {
        val current = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        current.split(':').any { it.equals(A11Y_COMPONENT, ignoreCase = true) }
    } catch (t: Throwable) {
        false
    }

    // ------------------------------------------------------------ lock/reboot

    /** lock — lock the screen immediately (needs an active device admin). */
    fun lockNow(ctx: Context): String {
        val manager = dpm(ctx) ?: return error("无法取得 DevicePolicyManager")
        return try {
            if (!manager.isAdminActive(component(ctx))) {
                return error("设备管理器未激活，无法锁屏")
            }
            manager.lockNow()
            "已锁屏"
        } catch (t: SecurityException) {
            error("锁屏被拒绝: " + describe(t))
        } catch (t: Throwable) {
            error("锁屏失败: " + describe(t))
        }
    }

    /**
     * reboot — not reachable from a sideloaded app, at all.
     *
     * Two doors, both locked. `PowerManager.reboot` needs
     * `android.permission.REBOOT`, which is `signature|privileged` - platform
     * signing, so no side-loaded app can ever hold it, and `pm grant` cannot
     * hand out a signature permission either. `DevicePolicyManager.reboot` is
     * the owner-only door, but this app cannot become Device Owner without
     * provisioning-time setup (a factory reset), and even a Device Owner build
     * would be a *different* deployment than the one this command ships in.
     *
     * So the message says what is actually true instead of naming a role the
     * operator could chase forever.
     */
    fun rebootNow(ctx: Context): String {
        if (!isDeviceOwner(ctx)) {
            return error(
                "重启需要系统签名权限（REBOOT），侧载应用无法申请；平板已设 DO 也不够，只能手动重启或 root"
            )
        }
        val manager = dpm(ctx) ?: return error("无法取得 DevicePolicyManager")
        return try {
            AgentLog.add("admin", "收到重启指令")
            manager.reboot(component(ctx))
            "已重启"
        } catch (t: SecurityException) {
            error("重启被拒绝: " + describe(t))
        } catch (t: Throwable) {
            error("重启失败: " + describe(t))
        }
    }
}
