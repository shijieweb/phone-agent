package net.de5.phoneagent

/**
 * Pure logic for 「摄像头看图」 (v0.9.0-pad12): blob names, scaling, the two
 * sentences the owner reads, and the one-line note body the relay expects.
 *
 * Why a separate file: everything here is a *contract*, not a framework call.
 * The blob name has to satisfy the relay's `^[A-Za-z0-9_.-]{1,80}$`, the note
 * body has to carry exactly the six fields the VPS side reads (a typo there is a
 * silent 400 far away from the phone), and the two sentences are what the phone's
 * owner actually looks at. None of that can be checked by opening a camera, so
 * all of it lives where a plain JVM can assert it — see CamPlanTest.
 *
 * Nothing here imports android.*: the framework half (Camera2, ImageReader,
 * TextureView) is in [CameraActivity], which only decides *when* to call these.
 */
internal object CamPlan {

    /** Longest side of the JPEG that leaves the phone. 1280 keeps a frame ~150 KB. */
    const val MAX_PX = 1280

    /** Re-encode quality. 80 is the point where a 1280px frame stays readable at ~150 KB. */
    const val JPEG_QUALITY = 80

    /** The text box stops here. A spoken sentence is well under it; a paste is not. */
    const val MAX_TEXT = 500

    /**
     * How long to wait between the tap on 发送 and the still capture.
     *
     * The owner types with the keyboard, presses 发送 and the phone is still
     * moving in their hand; capturing at once gives a blurred frame of the
     * ceiling. 300 ms is long enough for the hand to settle and short enough that
     * the scene has not changed.
     */
    const val SETTLE_MS = 300L

    /**
     * 阶段 A: from the tap on 发送 to the JPEG bytes landing. The owner has to hold
     * the phone still, so the sentence has to say so (v0.12.0-pad12).
     *
     * The split between this and [DONE_HOLD_TEXT] is a real moment -- the bytes
     * arriving in the ImageReader -- not a timer: a timer would either tell him to
     * move while the sensor is still integrating, or make him wait for the upload.
     */
    const val HOLD_TEXT = "正在拍，先别动"

    /** 阶段 B: the frame is captured, the upload is mine to worry about. He can move. */
    const val DONE_HOLD_TEXT = "拍好了，可以动了（正在发送）"

    /**
     * The top-right button that releases the camera (v0.11.3-pad12, retargeted in
     * v0.11.4-pad12).
     *
     * It moved to the top of the screen when 返回 was dropped from the bottom row:
     * the owner sometimes only wants to send a sentence, and 关闭摄像头 says what it
     * does -- 返回 next to 发送 read like "go back one step" and was pressed by
     * mistake.
     *
     * v0.11.4 made it a switch instead of a way out. The owner's words: closing the
     * camera means "stop capturing pictures, so the next 发送 goes out without one",
     * not "leave this page". So this label belongs to the ON state and the page
     * stays where it is; leaving is the system back gesture.
     */
    const val CLOSE_CAMERA_TEXT = "关闭摄像头"

    /**
     * The same button once the camera is released (v0.11.4-pad12).
     *
     * It names the state the button leads *to*, not the state it is in: the button
     * always says what the next tap does. 打开摄像头 is therefore the only label the
     * owner can read as "the camera is off right now".
     */
    const val OPEN_CAMERA_TEXT = "打开摄像头"

    /**
     * What the preview area says while the camera is released (v0.11.4-pad12).
     *
     * A frozen last frame with no explanation reads as "the camera is still on and
     * nobody is moving"; this sentence is the difference between a stalled picture
     * and a switched-off camera, and it also promises the thing that matters for the
     * next send -- 这次只发文字.
     */
    const val CAM_OFF_TEXT = "摄像头已关闭，这次只发文字"

    /** The relay accepts `^[A-Za-z0-9_.-]{1,80}$` for a blob name (SPEC.md §4). */
    const val NAME_MAX_CHARS = 80

    /**
     * The two words the camera sends when it opens and closes (v0.11.0-pad12,
     * SPEC.md §3.31). They are the whole point of the feature: with these two
     * notes the operator can tell "the camera is on right now" from "the last
     * frame arrived five minutes ago", which a look note alone cannot express.
     */
    const val MODE_OPEN = "cam-open"
    const val MODE_CLOSE = "cam-close"

    private const val NAME_PREFIX = "cam-"
    private const val NAME_EXT = ".jpg"

    /** Callers pass four hex nibbles; anything else is replaced by this. */
    private const val SUFFIX_CHARS = 4
    private const val FALLBACK_SUFFIX = "0000"

    /**
     * `cam-<epochMillis>-<4 lower-case hex>.jpg`.
     *
     * The suffix is sanitised instead of trusted: it is pasted into a URL *and*
     * into a file name on the relay, so one stray byte is a 400 that the phone
     * reports as "upload failed" with no hint about why. A suffix that is not
     * exactly four [0-9a-z] characters becomes [FALLBACK_SUFFIX] — a name that
     * collides is recoverable (the note carries the millis), a name the relay
     * rejects is not.
     *
     * A negative [millis] (clock not yet set) becomes 0 rather than "-123…": the
     * relay would accept the minus sign, but a name that sorts before every real
     * one is worse than an obvious "the clock was wrong".
     */
    fun blobName(millis: Long, suffix: String): String {
        val stamp = if (millis < 0L) 0L else millis
        return NAME_PREFIX + stamp + "-" + sanitizeSuffix(suffix) + NAME_EXT
    }

    private fun sanitizeSuffix(suffix: String): String {
        val lowered = suffix.lowercase()
        if (lowered.length != SUFFIX_CHARS) return FALLBACK_SUFFIX
        for (ch in lowered) {
            val ok = (ch in 'a'..'z') || (ch in '0'..'9')
            if (!ok) return FALLBACK_SUFFIX
        }
        return lowered
    }

    /**
     * The factor that brings the longest side down to [maxPx].
     *
     * Never enlarges: a frame that is already small (a 640x480 camera, a device
     * that ignored our size request) is sent as it is — upscaling costs bytes and
     * invents pixels the operator would then be judging.
     *
     * Any illegal input returns 1.0f, i.e. "send it unchanged", because the caller
     * has no better answer: a zero width means the decode failed, and a
     * divide-by-zero here would crash a background thread with the camera open.
     */
    fun fitScale(srcW: Int, srcH: Int, maxPx: Int): Float {
        if (srcW <= 0 || srcH <= 0 || maxPx <= 0) return 1.0f
        val longest = if (srcW > srcH) srcW else srcH
        if (longest <= maxPx) return 1.0f
        return maxPx.toFloat() / longest.toFloat()
    }

    /**
     * The line under the buttons after a successful send.
     *
     * Both numbers are in it on purpose: the owner wants to know the picture went
     * (图 148 KB), and the operator wants to know what was sent without fetching
     * it (1280x720, 文字 12 字). Same sentence for both, so the phone never has to
     * explain itself twice.
     */
    fun summaryLine(bytes: Int, w: Int, h: Int, chars: Int): String =
        "已发送：图 " + sizeText(bytes) + "（" + w + "x" + h + "）+ 文字 " + chars + " 字"

    /**
     * The line under the buttons after a send that carried no picture
     * (v0.11.4-pad12).
     *
     * Deliberately a different sentence from [summaryLine], not a summaryLine with
     * zeros in it: 已发送：图 0 B（0x0）+ 文字 12 字 would read as "a picture went,
     * and it was empty", which is the one thing the owner must not think. The
     * parenthetical 没带图 is the whole message.
     *
     * [chars] counts code points, so an emoji is one character, not two. A zero or
     * negative count still produces a sentence -- the send happened.
     */
    fun textOnlyLine(chars: Int): String {
        if (chars <= 0) return "已发送：文字（没带图）"
        return "已发送：文字 " + chars + " 字（没带图）"
    }

    /** `NNN B` under 1 KiB, `NNN KB` above it (rounded to the nearest KiB). */
    fun sizeText(bytes: Int): String {
        val safe = if (bytes < 0) 0 else bytes
        if (safe < 1024) return safe.toString() + " B"
        return ((safe + 512) / 1024).toString() + " KB"
    }

    /**
     * The line under the buttons after a failed send.
     *
     * Always the same prefix, so a screenshot of the page says "this failed"
     * before it says anything else; a blank reason still produces a sentence
     * rather than "发送失败: ".
     */
    fun errorLine(reason: String): String {
        val trimmed = reason.trim()
        return "发送失败: " + (if (trimmed.isEmpty()) "未知原因" else trimmed)
    }

    /** 阶段 A 的那句话, for the send path. */
    fun holdLine(): String = HOLD_TEXT

    /** 阶段 B 的那句话, spoken the moment the bytes are in hand. */
    fun doneHoldLine(): String = DONE_HOLD_TEXT

    /**
     * The line after a failed *send* (v0.12.0-pad12).
     *
     * Deliberately not [errorLine]: "javax.net.ssl.SSLException: ..." tells a person
     * holding a phone nothing he can act on. The reason belongs in AgentLog; the
     * screen says what to do next, and the typed text is still in the box for that
     * second press.
     */
    fun retryLine(): String = "没传上去，再按一次发送"

    /**
     * What actually gets sent: trimmed, and cut at [MAX_TEXT].
     *
     * Cut by code point, not by char: half of an emoji is not a shorter string,
     * it is an unpaired surrogate, and the JSON encoder turns that into a
     * replacement character on the far side.
     */
    fun clampText(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.codePointCount(0, trimmed.length) <= MAX_TEXT) return trimmed
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_TEXT))
    }

    /**
     * The body of `POST /agent/note` (SPEC.md §3.29), as text.
     *
     * Built here rather than with JSONObject so the six field names are asserted
     * by a test: the VPS side reads them literally, and a renamed field would look
     * like a successful send on the phone and like a note with no picture on mine.
     */
    fun noteBody(id: String, text: String, blob: String, w: Int, h: Int): String =
        "{\"id\":" + quote(id) +
            ",\"kind\":\"look\"" +
            ",\"text\":" + quote(text) +
            ",\"blob\":" + quote(blob) +
            ",\"w\":" + w +
            ",\"h\":" + h + "}"

    /**
     * The body of `POST /agent/note` for the camera opening and closing
     * (v0.11.0-pad12, SPEC.md §3.31), as text.
     *
     * Only two words are accepted. The camera is a privacy switch, so a typo in
     * the caller must not reach the log as a third state that nobody can explain
     * later: an unknown [kind] becomes an empty string and the note is dropped
     * by [modeNote] instead of being sent.
     */
    fun modeBody(id: String, kind: String): String {
        val clean = if (kind == MODE_OPEN || kind == MODE_CLOSE) kind else ""
        return "{\"id\":" + quote(id) + ",\"kind\":" + quote(clean) + "}"
    }

    /** True when [kind] is one of the two states worth sending. */
    fun modeNote(kind: String): Boolean = kind == MODE_OPEN || kind == MODE_CLOSE

    private fun quote(value: String): String {
        val out = StringBuilder(value.length + 8)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) {
                    out.append("\\u")
                    val hex = Integer.toHexString(ch.code)
                    var pad = hex.length
                    while (pad < 4) {
                        out.append('0')
                        pad++
                    }
                    out.append(hex)
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    /**
     * Which size out of `StreamConfigurationMap.getOutputSizes` to use.
     *
     * [sizes] are `{width, height}` pairs. The score prefers a 16:9 shape first
     * (weight 4) and a long side near the target second, because the two failures
     * are not equally bad: a 4:3 frame is letterboxed in the preview and looks
     * cropped to the operator, while a 960-wide 16:9 frame is just slightly
     * smaller. Never the largest available — a 4000x3000 JPEG is 2 MB over the
     * mobile uplink, and the operator reads it on a 1280px-wide screen anyway.
     *
     * Returns null when nothing usable is offered, so the caller can say so
     * instead of opening a camera that cannot produce a frame.
     */
    fun pickSize(sizes: List<IntArray>, targetW: Int, targetH: Int): IntArray? {
        if (targetW <= 0 || targetH <= 0) return null
        val tLong = if (targetW > targetH) targetW else targetH
        val tShort = if (targetW > targetH) targetH else targetW
        val tAspect = tLong.toDouble() / tShort.toDouble()
        var best: IntArray? = null
        var bestScore = Double.MAX_VALUE
        for (size in sizes) {
            if (size.size < 2) continue
            val w = size[0]
            val h = size[1]
            if (w <= 0 || h <= 0) continue
            val longSide = if (w > h) w else h
            val shortSide = if (w > h) h else w
            val aspectDiff = Math.abs(longSide.toDouble() / shortSide.toDouble() - tAspect)
            val longDiff = Math.abs(longSide - tLong).toDouble() / tLong.toDouble()
            val score = aspectDiff * 4.0 + longDiff
            if (score < bestScore) {
                bestScore = score
                best = size
            }
        }
        return best
    }
}
