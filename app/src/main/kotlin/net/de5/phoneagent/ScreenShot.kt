package net.de5.phoneagent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.util.Base64
import android.view.Display
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One step of the "re-encode smaller" ladder of SPEC.md section 4.
 *
 * Pure data + a pure function so the ladder can be unit tested without a
 * Bitmap, a display or an accessibility service (SPEC.md section 10).
 */
data class ShotAttempt(
    /** Longest side of the downscaled image. */
    val maxPx: Int,
    /** JPEG quality. */
    val quality: Int,
    /** 0 = first attempt, 1 = halved maxPx, 2 = quality dropped to 35. */
    val step: Int,
    /** Human readable description, reported on stderr. */
    val note: String
)

object ShotLadder {

    /** stdout is truncated at 200 000 chars server side; stay well below. */
    const val MAX_BASE64_CHARS = 185_000

    const val MIN_MAXPX = 320
    const val FALLBACK_QUALITY = 35

    fun initial(maxPx: Int, quality: Int): ShotAttempt =
        ShotAttempt(maxPx, quality, 0, "maxPx=$maxPx q=$quality")

    /**
     * @param base64Length length of the base64 produced by [previous].
     * @return the next attempt to try, or null when the ladder is exhausted
     *         (either it fits, or we gave up).
     */
    fun next(previous: ShotAttempt, base64Length: Int): ShotAttempt? {
        if (base64Length <= MAX_BASE64_CHARS) return null
        return when (previous.step) {
            0 -> {
                val px = if (previous.maxPx / 2 < MIN_MAXPX) MIN_MAXPX else previous.maxPx / 2
                ShotAttempt(px, previous.quality, 1, "re-encoded maxPx=$px q=${previous.quality}")
            }
            1 -> {
                val q = if (previous.quality > FALLBACK_QUALITY) FALLBACK_QUALITY else previous.quality
                ShotAttempt(previous.maxPx, q, 2, "re-encoded maxPx=${previous.maxPx} q=$q")
            }
            else -> null
        }
    }
}

/**
 * Accessibility screenshot -> downscaled JPEG -> base64 (SPEC.md section 4).
 *
 * Must be called from a **background** thread: `takeScreenshot` itself is
 * dispatched to the main thread (accessibility calls belong there) and the
 * caller blocks until the callback lands on [callbackExecutor].
 */
object ScreenShot {

    private const val CAPTURE_TIMEOUT_MS = 20_000L
    private const val QUALITY_MIN = 20
    private const val QUALITY_MAX = 90

    /** The platform hands the bitmap back here, off the main thread. */
    private val callbackExecutor: Executor = Executors.newSingleThreadExecutor()

    fun capture(service: AccessibilityService, maxPx: Int, quality: Int): ExecutionResult {
        val latch = CountDownLatch(1)
        var shot: AccessibilityService.ScreenshotResult? = null
        var failure: Int = 0

        val posted = MainThread.post {
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    callbackExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            shot = screenshot
                            latch.countDown()
                        }

                        override fun onFailure(errorCode: Int) {
                            failure = errorCode
                            latch.countDown()
                        }
                    }
                )
            } catch (t: Throwable) {
                failure = AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                latch.countDown()
            }
        }

        if (!posted) return ExecutionResult(EXIT_TIMEOUT, "", "screenshot could not be dispatched")
        if (!latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return ExecutionResult(EXIT_TIMEOUT, "", "screenshot timed out")
        }

        val result = shot
        if (result == null) return errorResult(failure)

        val buffer: HardwareBuffer? = result.hardwareBuffer
        if (buffer == null) {
            return ExecutionResult(EXIT_USAGE, "", "screenshot hardware buffer unavailable")
        }

        // SPEC.md section 4: wrap, close the hardware buffer immediately, then
        // scale down.
        val source: Bitmap? = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
        buffer.close()
        if (source == null) return ExecutionResult(EXIT_USAGE, "", "screenshot decode failed")

        return try {
            encodeWithLadder(source, maxPx, quality)
        } finally {
            source.recycle()
        }
    }

    private fun encodeWithLadder(source: Bitmap, maxPx: Int, quality: Int): ExecutionResult {
        var attempt = ShotLadder.initial(maxPx, quality)
        var base64 = ""
        var width = 0
        var height = 0
        var byteCount = 0

        while (true) {
            val scaled = scale(source, attempt.maxPx)
                ?: return ExecutionResult(EXIT_USAGE, "", "screenshot scale failed")
            width = scaled.width
            height = scaled.height
            val bytes = encodeJpeg(scaled, attempt.quality)
            if (scaled !== source) scaled.recycle()
            byteCount = bytes.size
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            if (base64.length <= ShotLadder.MAX_BASE64_CHARS) break

            val next = ShotLadder.next(attempt, base64.length)
                ?: return ExecutionResult(EXIT_USAGE, "", "screenshot too large")
            attempt = next
        }

        var stderr = "w=$width h=$height bytes=$byteCount b64=${base64.length}"
        if (attempt.step > 0) stderr = "$stderr (${attempt.note})"
        return ExecutionResult(EXIT_OK, base64, stderr)
    }

    /** Scale so the longest side is at most [maxPx]; returns the source when it already fits. */
    private fun scale(source: Bitmap, maxPx: Int): Bitmap? {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return null
        val longest = if (width > height) width else height
        if (longest <= maxPx) return source
        val factor = maxPx.toFloat() / longest.toFloat()
        val newWidth = (width.toFloat() * factor).toInt()
        val newHeight = (height.toFloat() * factor).toInt()
        if (newWidth <= 0 || newHeight <= 0) return null
        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }

    private fun encodeJpeg(source: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            source.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(QUALITY_MIN, QUALITY_MAX), out)
        } finally {
            out.close()
        }
        return out.toByteArray()
    }

    private fun errorResult(errorCode: Int): ExecutionResult = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            ExecutionResult(EXIT_NO_ACCESSIBILITY, "", "screenshot denied (NO_ACCESSIBILITY_ACCESS)")

        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            ExecutionResult(EXIT_TIMEOUT, "", "screenshot rate-limited (INTERVAL_TIME_SHORT)")

        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
            ExecutionResult(EXIT_USAGE, "", "screenshot invalid display (INVALID_DISPLAY)")

        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW ->
            ExecutionResult(EXIT_USAGE, "", "screenshot invalid window (INVALID_WINDOW)")

        else ->
            ExecutionResult(EXIT_USAGE, "", "screenshot internal error (INTERNAL_ERROR)")
    }
}
