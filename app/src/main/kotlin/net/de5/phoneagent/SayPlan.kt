package net.de5.phoneagent

import java.util.Locale

/**
 * Argument handling for `say` (SPEC.md §3.9).
 *
 * The operator types a percentage because that is what a human can reason
 * about; TextToSpeech wants a multiplier around 1.0. The conversion and the
 * clamping live here, not in the Android glue, so the numbers are provable.
 *
 * The language is a two-value switch (`zh`, `en`) rather than a full locale: a
 * remote operator says "read this in English" or nothing at all, and every
 * spelling of that wish has to land on the same voice.
 */
internal object SayPlan {

    const val RATE_DEFAULT = 100
    const val RATE_MIN = 20
    const val RATE_MAX = 400

    const val PITCH_DEFAULT = 100
    const val PITCH_MIN = 50
    const val PITCH_MAX = 200

    /**
     * A single utterance, not an audiobook: the relay has to stay responsive and
     * the operator is listening on the other end of a chat.
     */
    const val TEXT_MAX = 2000

    /** Chinese is the default: the operator speaks Chinese to this phone. */
    const val LANG_DEFAULT = "zh"
    const val LANG_ZH = "zh"
    const val LANG_EN = "en"

    const val USAGE = "usage: say [-l zh|en] [rate=PERCENT] [pitch=PERCENT] TEXT"

    /** Every spelling an operator might type, mapped onto the two voices we have. */
    fun lang(raw: String?): String? {
        val value = (raw ?: "").trim().lowercase()
        return when (value) {
            "zh", "zh-cn", "zh_cn", "zh-hans", "cn", "chinese", "中文", "汉语" -> LANG_ZH
            "en", "en-us", "en_us", "en-gb", "en_gb", "english", "英文", "英语" -> LANG_EN
            else -> null
        }
    }

    /** The locale asked about first for a language. */
    fun locale(lang: String): Locale =
        if (lang == LANG_EN) Locale.US else Locale.SIMPLIFIED_CHINESE

    /**
     * Every locale the engine is asked about, best first.
     *
     * The device default is the last resort: an English-only ROM that also
     * speaks Chinese through its own default locale is still better than
     * refusing to talk.
     */
    fun localeCandidates(lang: String): List<Locale> =
        if (lang == LANG_EN) {
            listOf(Locale.US, Locale.ENGLISH, Locale.getDefault())
        } else {
            listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINA, Locale.getDefault())
        }

    fun clampRate(value: Int): Int = value.coerceIn(RATE_MIN, RATE_MAX)

    fun clampPitch(value: Int): Int = value.coerceIn(PITCH_MIN, PITCH_MAX)

    fun speechRate(percent: Int): Float = clampRate(percent) / 100f

    fun pitch(percent: Int): Float = clampPitch(percent) / 100f

    /**
     * How long the executor waits for the utterance to finish.
     *
     * Four characters per second at normal rate is a slow-but-safe reading
     * pace, so the wait is generous; the cap keeps a runaway engine from
     * pinning the poll loop for minutes.
     */
    fun waitSeconds(text: String): Long {
        val chars = clipText(text).length.toLong()
        val estimate = chars / 4L + 5L
        return estimate.coerceIn(5L, 120L)
    }

    fun clipText(value: String): String =
        if (value.length <= TEXT_MAX) value else value.substring(0, TEXT_MAX)

    fun report(text: String, rate: Int, pitch: Int, lang: String): String =
        "已朗读 " + clipText(text).length + " 字（lang=" + lang + " rate=" + clampRate(rate) +
            " pitch=" + clampPitch(pitch) + "）"

    fun failure(reason: String): String = "朗读失败: " + reason
}
