package net.de5.phoneagent

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Outcome of trying to put an APK on disk. Never an exception: the caller reports text. */
internal sealed class Download {
    class Ok(val file: File) : Download()
    class Failed(val message: String) : Download()
}

/**
 * Pulls an APK from a URL into our private cache so [Admin.launchInstall] can
 * hand the system installer a real file (via [ApkProvider]'s content:// URI).
 *
 * [discard] exists for callers that do not hand the file on; `install` does not
 * use it, because the installer reads the file after the command returns.
 *
 * The bytes are streamed straight to disk: a 60 MB APK held in a ByteArray
 * would be a needless OOM risk on a phone running a foreground service.
 *
 * Note for whoever debugs a "cleartext not permitted" failure: our
 * networkSecurityConfig only whitelists the relay hosts for plain HTTP, so an
 * install URL must be https unless its host is one of those.
 */
internal object Downloader {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val BUFFER_BYTES = 64 * 1024

    /** Refuse absurd payloads: 200 MB is far beyond any APK we ship. */
    private const val MAX_BYTES = 200L * 1024L * 1024L

    private const val CACHE_SUBDIR = "apk"

    fun fetch(context: Context, url: String): Download {
        val dir = File(context.cacheDir, CACHE_SUBDIR)
        try {
            dir.mkdirs()
        } catch (t: Throwable) {
            return Download.Failed("无法创建缓存目录: " + describe(t))
        }
        pruneOld(dir)
        val temp = File(dir, "install-" + System.currentTimeMillis() + ".apk")

        var connection: HttpURLConnection? = null
        var input: InputStream? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/octet-stream, */*")
            }
            val code = connection.responseCode
            if (code < 200 || code > 299) {
                discard(temp)
                return Download.Failed("下载失败: HTTP " + code)
            }
            input = connection.inputStream
            if (input == null) {
                discard(temp)
                return Download.Failed("下载失败: 响应为空")
            }
            var total = 0L
            FileOutputStream(temp).use { out ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_BYTES) {
                        out.flush()
                        discard(temp)
                        return Download.Failed("下载失败: 文件超过 " + (MAX_BYTES / 1024 / 1024) + "MB")
                    }
                    out.write(buffer, 0, read)
                }
                out.flush()
                try {
                    out.fd.sync()
                } catch (t: Throwable) {
                    // fsync is best effort; the data is already written.
                }
            }
            if (total <= 0L) {
                discard(temp)
                return Download.Failed("下载失败: 0 字节")
            }
            if (!Admin.looksLikeApk(temp)) {
                discard(temp)
                return Download.Failed("下载到的不是 apk（缺少 PK 头）: " + url)
            }
            AgentLog.add("install", "已下载 " + total + " 字节 -> " + temp.name)
            return Download.Ok(temp)
        } catch (t: Throwable) {
            discard(temp)
            return Download.Failed("下载失败: " + describe(t))
        } finally {
            try {
                input?.close()
            } catch (t: Throwable) {
                // ignore
            }
            try {
                connection?.disconnect()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    /**
     * Delete APK leftovers from earlier installs.
     *
     * [Admin.launchInstall] cannot delete the file it hands to the installer —
     * the installer reads it lazily — so the cleanup has to happen on the next
     * download instead. A day is far longer than any install confirmation takes
     * and keeps cacheDir from growing by a few dozen MB per upgrade.
     */
    /**
     * 服务启动时也裁一次（v0.5.8-pad12）。
     *
     * 只在下次下载前裁的话，装完就再也不下载的机器会一直留着几十 MB 的旧 APK：
     * 那台手机恰恰是最安静的一台，谁也不会来点「安装」。
     */
    fun pruneNow(context: Context) {
        try {
            val dir = File(context.cacheDir, CACHE_SUBDIR)
            if (dir.isDirectory) pruneOld(dir)
        } catch (t: Throwable) {
            // Best effort: see pruneOld.
        }
    }

    /**
     * cacheDir/apk 里的文件清单（v0.5.8-pad12，给 `logs size` / `logs trim` 用）。
     *
     * 放在这里而不是 Executor 里：这个目录和它的 24 小时规则是 [Downloader] 的，
     * 别处再拼一次路径，早晚会和 STALE_MS 对不上。读不到就返回空表——少报一个
     * 残留，也比编一个数好。
     */
    fun cached(context: Context): List<LogSizes.Apk> {
        val dir = File(context.cacheDir, CACHE_SUBDIR)
        val files = try {
            dir.listFiles()
        } catch (t: Throwable) {
            null
        } ?: return emptyList()
        val out = ArrayList<LogSizes.Apk>(files.size)
        for (file in files) {
            if (!file.isFile) continue
            try {
                out.add(LogSizes.Apk(file.name, file.length(), file.lastModified()))
            } catch (t: Throwable) {
                // 单个文件读不到就跳过，不影响其余条目。
            }
        }
        return out
    }

    /** 删掉 [stale] 点名的那些；返回真正删掉的个数，供报告对账。 */
    fun discardStale(context: Context, stale: List<LogSizes.Apk>): Int {
        if (stale.isEmpty()) return 0
        val dir = File(context.cacheDir, CACHE_SUBDIR)
        var gone = 0
        for (apk in stale) {
            try {
                if (File(dir, apk.name).delete()) gone++
            } catch (t: Throwable) {
                // Best effort: 删不掉就留着，下次再试。
            }
        }
        return gone
    }

    private fun pruneOld(dir: File) {
        try {
            val cutoff = System.currentTimeMillis() - STALE_MS
            val stale = dir.listFiles() ?: return
            for (file in stale) {
                if (file.isFile && file.lastModified() < cutoff) discard(file)
            }
        } catch (t: Throwable) {
            // Best effort: a cache that cannot be pruned is not a failure.
        }
    }

    /** Anything older than this is a leftover, not a pending install. */
    private const val STALE_MS = 24L * 60L * 60L * 1000L

    /** Remove a cached APK after a failure, or when the caller is done with it. */
    fun discard(file: File?) {
        if (file == null) return
        try {
            if (file.exists()) file.delete()
        } catch (t: Throwable) {
            // A leftover cache file is harmless: the whole dir is clearable.
        }
    }
}
