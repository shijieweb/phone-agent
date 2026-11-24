package net.de5.phoneagent

/**
 * The "#rc:" built-in action protocol of the controller layer.
 *
 * The controller (scripts/rc.py on the managed PC) does not send "shot"; it sends
 * `#rc: shot {"width":1280,"quality":72}` and then reads the "name"
 * field of the one-line JSON we answer with, to fetch the bytes over HTTP. Keeping
 * the parsing here -- pure Kotlin, no Android and no org.json -- means the whole
 * contract is unit tested on a plain JVM (SPEC.md section 10).
 */

/** The literal prefix that turns a line into a built-in action instead of a shell command. */
internal const val RC_PREFIX = "#rc:"

/** "shot" width/quality window for the built-in protocol (SPEC.md section 4). */
internal const val RC_SHOT_MAXPX_DEFAULT = 720
internal const val RC_SHOT_MAXPX_MIN = 320
internal const val RC_SHOT_MAXPX_MAX = 1440

internal const val RC_SHOT_QUALITY_DEFAULT = 55

/**
 * Deliberately wider than the human "shot" window (20..90): the controller asks
 * for the quality it wants, and 10 is a legitimate "make it tiny" request.
 */
internal const val RC_SHOT_QUALITY_MIN = 10
internal const val RC_SHOT_QUALITY_MAX = 95

/** Outcome of parsing one "#rc:" line. */
internal sealed class RcLine {
    /** "#rc: shot {json}" -- already clamped to the documented window. */
    data class Shot(val maxPx: Int, val quality: Int) : RcLine()

    /** A verb this build does not implement ("wins", "uia", "act", ...). */
    data class Unknown(val verb: String) : RcLine()

    /** A "#rc:" line we cannot honour at all (no verb, malformed JSON). */
    data class Bad(val detail: String) : RcLine()
}

/** True when [line] is a built-in action line rather than a shell command. */
internal fun isRcLine(line: String): Boolean = line.trimStart().startsWith(RC_PREFIX)

/**
 * Parse one built-in action line.
 *
 * Grammar: "#rc: VERB [JSON]". Only "shot" is implemented; every other verb is
 * [RcLine.Unknown] on purpose -- a verb we do not know must never fall through to
 * the shell, where it would silently run as a command that does not exist.
 *
 * "hwnd" / "rect" are ignored silently: Android has no window handles, and the
 * controller sends them for the Windows agents.
 */
internal fun parseRcLine(line: String): RcLine {
    val trimmed = line.trimStart()
    val body = if (trimmed.startsWith(RC_PREFIX)) {
        trimmed.substring(RC_PREFIX.length)
    } else {
        trimmed
    }.trim()
    if (body.isEmpty()) return RcLine.Bad("缺少动作名")

    val cut = firstSpace(body)
    val verb = if (cut < 0) body else body.substring(0, cut)
    val rest = if (cut < 0) "" else body.substring(cut + 1)

    if (!verb.equals("shot", ignoreCase = true)) return RcLine.Unknown(verb)

    val payload = rest.trim()
    if (payload.isEmpty()) {
        return RcLine.Shot(RC_SHOT_MAXPX_DEFAULT, RC_SHOT_QUALITY_DEFAULT)
    }
    if (!isJsonObject(payload)) return RcLine.Bad("JSON 解析失败")

    val maxPx = (jsonIntField(payload, "width") ?: RC_SHOT_MAXPX_DEFAULT)
        .coerceIn(RC_SHOT_MAXPX_MIN, RC_SHOT_MAXPX_MAX)
    val quality = (jsonIntField(payload, "quality") ?: RC_SHOT_QUALITY_DEFAULT)
        .coerceIn(RC_SHOT_QUALITY_MIN, RC_SHOT_QUALITY_MAX)
    return RcLine.Shot(maxPx, quality)
}

private fun firstSpace(value: String): Int {
    var i = 0
    while (i < value.length) {
        if (value[i].isWhitespace()) return i
        i++
    }
    return -1
}

/**
 * Cheap structural check for a JSON **object**.
 *
 * We only ever need {"width":1280,"quality":72}, so this validates the shape
 * (braces balance, quotes close) instead of building a parser. A payload that
 * fails here is reported as a bad payload rather than silently defaulted: the
 * controller must see that its request did not arrive intact.
 */
internal fun isJsonObject(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.length < 2) return false
    if (trimmed[0] != '{' || trimmed[trimmed.length - 1] != '}') return false

    var depth = 0
    var inString = false
    var escaped = false
    for (ch in trimmed) {
        if (inString) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
            }
            continue
        }
        when (ch) {
            '"' -> inString = true
            '{', '[' -> depth++
            '}', ']' -> {
                depth--
                if (depth < 0) return false
            }
        }
    }
    return depth == 0 && !inString
}

/**
 * The integer value of [key] in a flat JSON object, or null when the key is
 * missing or does not hold an integer. Quoted numbers ("1280") are not accepted:
 * the controller sends numbers.
 */
internal fun jsonIntField(json: String, key: String): Int? {
    val needle = "\"" + key + "\""
    var from = 0
    while (true) {
        val at = json.indexOf(needle, from, ignoreCase = true)
        if (at < 0) return null
        var i = at + needle.length
        while (i < json.length && json[i].isWhitespace()) i++
        if (i < json.length && json[i] == ':') {
            i++
            while (i < json.length && json[i].isWhitespace()) i++
            val start = i
            if (i < json.length && (json[i] == '-' || json[i] == '+')) i++
            val digitsFrom = i
            while (i < json.length && json[i].isDigit()) i++
            if (i > digitsFrom) return json.substring(start, i).toIntOrNull()
            return null
        }
        from = at + needle.length
    }
}
