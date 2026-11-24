package net.de5.phoneagent

import java.util.Random

/**
 * Blob names, the one-line JSON the controller parses, and the limits that go
 * with them (SPEC.md section 4).
 *
 * Pure Kotlin on purpose: the relay accepts a name only when it matches
 * ^[A-Za-z0-9_.-]{1,80}$, and getting that wrong is a silent 400 far away from
 * the phone -- so it is decided (and unit tested) here.
 */
internal object ShotBlob {

    /** Relay-side hard limit for one blob ("PUT /agent/blob/<name>"). */
    const val MAX_BYTES = 16L * 1024 * 1024

    /** Relay-side name length limit. */
    const val NAME_MAX_CHARS = 80

    private const val NAME_PREFIX = "shot-"
    private const val SUFFIX_CHARS = 6
    private const val SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** "shot-<epochMillis>-<6 chars>", always inside the relay's accepted window. */
    fun name(millis: Long, suffix: String): String {
        val clean = sanitizeSuffix(suffix)
        return NAME_PREFIX + millis + "-" + clean
    }

    /** Six random lower-case alphanumerics, the collision breaker of [name]. */
    fun randomSuffix(random: Random = Random()): String {
        val out = StringBuilder(SUFFIX_CHARS)
        var i = 0
        while (i < SUFFIX_CHARS) {
            out.append(SUFFIX_ALPHABET[random.nextInt(SUFFIX_ALPHABET.length)])
            i++
        }
        return out.toString()
    }

    /**
     * The single stdout line both "shot" and "#rc: shot" answer with.
     *
     * Field names are part of the contract with scripts/rc.py, which reads "name"
     * and then fetches the bytes; "method" says the pixels came from the
     * accessibility screenshot API rather than a window handle.
     */
    fun json(name: String, bytes: Int, width: Int, height: Int, quality: Int): String =
        "{\"name\":\"" + name + "\",\"bytes\":" + bytes + ",\"w\":" + width +
            ",\"h\":" + height + ",\"q\":" + quality + ",\"method\":\"screen\"}"

    /** The failure shape of the built-in protocol: {"ok":false,"err":"..."}. */
    fun error(message: String): String = "{\"ok\":false,\"err\":\"" + escape(message) + "\"}"

    /** What the relay accepts: ^[A-Za-z0-9_.-]{1,80}$ */
    fun isValidName(name: String): Boolean {
        if (name.isEmpty() || name.length > NAME_MAX_CHARS) return false
        return name.all {
            (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') ||
                it == '.' || it == '_' || it == '-'
        }
    }

    private fun sanitizeSuffix(suffix: String): String {
        val lowered = suffix.lowercase()
        val kept = StringBuilder(SUFFIX_CHARS)
        var i = 0
        while (i < lowered.length && kept.length < SUFFIX_CHARS) {
            val ch = lowered[i]
            if ((ch in 'a'..'z') || (ch in '0'..'9')) kept.append(ch)
            i++
        }
        while (kept.length < SUFFIX_CHARS) kept.append('0')
        return kept.toString()
    }

    /** Minimal JSON string escaping for the error message. */
    private fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) out.append('?') else out.append(ch)
            }
        }
        return out.toString()
    }
}
