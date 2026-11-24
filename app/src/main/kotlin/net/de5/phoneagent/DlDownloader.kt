package net.de5.phoneagent

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * `dl` — put a file where VLC can play it (SPEC.md section 3.6).
 *
 * Why MediaStore and not `File("/sdcard/Movies")`: from Android 10 scoped
 * storage, a plain write into a public directory needs
 * `MANAGE_EXTERNAL_STORAGE` — a "special access" toggle a person has to grant in
 * system settings, which a headless agent must not depend on. MediaStore hands
 * back the same visible path (`/sdcard/Movies/<name>`) with zero permissions,
 * because we own the row we insert.
 *
 * Bytes are streamed straight into that row's stream: a 1 GB film in a
 * ByteArray would be an instant OOM on a phone also running a foreground
 * service.
 */
internal object DlDownloader {

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER_BYTES = 64 * 1024

    /** One transfer at a time: two `dl`s would fight over the same Wi-Fi anyway. */
    private val busy = AtomicBoolean(false)

    /** State shared with the worker thread so a timeout can actually stop it. */
    private class Job {
        val aborted = AtomicBoolean(false)
        val written = AtomicLong(0L)
        val stream = AtomicReference<InputStream?>(null)
        val connection = AtomicReference<HttpURLConnection?>(null)

        /** True once the half-written row is gone, so the caller need not retry the delete. */
        @Volatile
        var cleanedUp = false
    }

    /**
     * Run the transfer on a worker thread and wait at most [timeoutSeconds].
     *
     * The poll loop must never sit inside a network read for minutes. On timeout
     * the worker's stream is closed (its current `read` throws), the
     * half-written row is deleted by the worker's own finally block, and the
     * caller gets a definite answer with the number of bytes that had landed.
     */
    fun download(context: Context, url: String, name: String, timeoutSeconds: Int): DlOutcome {
        if (!busy.compareAndSet(false, true)) {
            return DlOutcome.Failed("已有下载在进行", 0L, DlFail.LOCAL)
        }
        val job = Job()
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<DlOutcome?>(null)
        val worker = Thread({
            try {
                outcome.set(run(context, url, name, job))
            } catch (t: Throwable) {
                outcome.set(DlOutcome.Failed(describe(t), job.written.get(), DlFail.LOCAL))
            } finally {
                latch.countDown()
            }
        }, "dl")
        worker.isDaemon = true
        worker.start()

        val finished = try {
            latch.await(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (finished) {
            busy.set(false)
            return outcome.get() ?: DlOutcome.Failed("下载线程没有返回结果", job.written.get(), DlFail.LOCAL)
        }

        // Timed out: stop the worker for real, then report what it had written.
        job.aborted.set(true)
        closeQuietly(job.stream.get())
        try {
            job.connection.get()?.disconnect()
        } catch (t: Throwable) {
            // The disconnect only exists to unblock the read.
        }
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        busy.set(false)
        val written = if (job.cleanedUp) 0L else job.written.get()
        return DlOutcome.Failed(DownloadPlan.timeoutFailure(timeoutSeconds), written, DlFail.TIMEOUT)
    }

    private fun run(context: Context, url: String, name: String, job: Job): DlOutcome {
        val target = insertRow(context, name)
            ?: return DlOutcome.Failed("无法在 Movies 里创建文件", 0L, DlFail.LOCAL)
        var ok = false
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "*/*")
                setRequestProperty("User-Agent", "phone-agent")
            }
            job.connection.set(connection)
            val code = connection.responseCode
            if (code < 200 || code > 299) {
                return DlOutcome.Failed(DownloadPlan.httpFailure(code), 0L, DlFail.REMOTE)
            }
            val input = connection.inputStream
                ?: return DlOutcome.Failed("响应为空", 0L, DlFail.REMOTE)
            job.stream.set(input)
            val output: OutputStream = context.contentResolver.openOutputStream(target)
                ?: return DlOutcome.Failed("无法写入 Movies 里的文件", 0L, DlFail.LOCAL)
            output.use {
                copy(input, it, job)
            }
            if (job.aborted.get()) {
                return DlOutcome.Failed("已取消", job.written.get(), DlFail.TIMEOUT)
            }
            publish(context, target)
            ok = true
            val written = job.written.get()
            if (DownloadPlan.isMedia(name)) scan(context, DownloadPlan.publicPath(name))
            AgentLog.add("dl", "已下载 " + name + " " + written + " 字节")
            return DlOutcome.Ok(written, DownloadPlan.publicPath(name))
        } catch (t: Throwable) {
            if (job.aborted.get()) {
                return DlOutcome.Failed("已取消", job.written.get(), DlFail.TIMEOUT)
            }
            // A size-cap trip carries its own sentence; anything else is the
            // framework's own wording, prefixed so it is never read as a success.
            val kind = if (t is IllegalStateException) DlFail.REMOTE else DlFail.LOCAL
            return DlOutcome.Failed(describe(t), job.written.get(), kind)
        } finally {
            if (!ok) {
                // A failed or aborted transfer must not leave a 0-byte ghost in
                // the gallery; the row is ours, so deleting it is always allowed.
                try {
                    context.contentResolver.delete(target, null, null)
                } catch (t: Throwable) {
                    // Nothing else to do: the row is pending and invisible.
                }
                job.cleanedUp = true
            }
        }
    }

    /** @throws IllegalStateException when the size cap or the abort flag trips. */
    private fun copy(input: InputStream, output: OutputStream, job: Job) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            if (job.aborted.get()) throw IllegalStateException("已取消")
            val read = input.read(buffer)
            if (read <= 0) break
            val total = job.written.addAndGet(read.toLong())
            if (DownloadPlan.overLimit(total)) {
                throw IllegalStateException(DownloadPlan.sizeFailure())
            }
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    /**
     * Create the MediaStore row up front so we can stream into its stream.
     *
     * The collection decides what the file *is*: a film must land in the video
     * collection or VLC's library will not list it, even though the path is
     * identical either way. IS_PENDING keeps it invisible until [publish].
     */
    private fun insertRow(context: Context, name: String): Uri? {
        val extension = DownloadPlan.extensionOf(name)
        val collection = when {
            VIDEO.contains(extension) -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            AUDIO.contains(extension) -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(extension))
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return try {
            context.contentResolver.insert(collection, values)
        } catch (t: Throwable) {
            AgentLog.add("dl", Admin.ERROR_PREFIX + "插入媒体库失败: " + describe(t))
            null
        }
    }

    /** Make the pending row visible; until this runs other apps cannot see it. */
    private fun publish(context: Context, target: Uri) {
        try {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(target, values, null, null)
        } catch (t: Throwable) {
            // The bytes are on disk; a failed publish only delays visibility.
        }
    }

    private fun scan(context: Context, path: String) {
        try {
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        } catch (t: Throwable) {
            // Best effort: the MediaStore row already exists.
        }
    }

    private fun mimeOf(extension: String): String = when (extension) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "ts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        "mpg", "mpeg" -> "video/mpeg"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp", "ts", "flv", "wmv", "mpg", "mpeg")
    private val AUDIO = setOf("mp3", "m4a", "flac", "aac", "ogg", "wav", "opus")

    private fun closeQuietly(stream: InputStream?) {
        try {
            stream?.close()
        } catch (t: Throwable) {
            // ignore
        }
    }
}
