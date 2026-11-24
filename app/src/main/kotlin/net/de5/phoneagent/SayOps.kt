package net.de5.phoneagent

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `say` — speak one sentence out loud (SPEC.md §3.9).
 *
 * TextToSpeech is asynchronous in three places (engine init, language check,
 * utterance completion), and the executor runs on the poll thread, so every
 * wait here is bounded: an engine that never initialises must produce an error,
 * not a hung agent.
 *
 * One engine per utterance on purpose. A cached engine would be cheaper, but it
 * would also keep a service binding alive forever on a phone whose only job is
 * to sit there for weeks — and a stale binding is exactly how the "no voice
 * after a week" bug looks.
 */
internal object SayOps {

    private const val INIT_TIMEOUT_SECONDS = 10L

    fun speak(context: Context, text: String, rate: Int, pitch: Int, lang: String): String {
        val initLatch = CountDownLatch(1)
        val ready = AtomicBoolean(false)
        val engine = try {
            TextToSpeech(context.applicationContext) { status ->
                ready.set(status == TextToSpeech.SUCCESS)
                initLatch.countDown()
            }
        } catch (t: Throwable) {
            return Admin.ERROR_PREFIX + "无法初始化语音引擎: " + describe(t)
        }
        try {
            if (!initLatch.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return Admin.ERROR_PREFIX + "语音引擎初始化超时（" + INIT_TIMEOUT_SECONDS + "s）"
            }
            if (!ready.get()) {
                return Admin.ERROR_PREFIX + "语音引擎不可用（系统未安装 TTS 引擎）"
            }
            val locale = pickLocale(engine, lang)
                ?: return Admin.ERROR_PREFIX + missingVoice(lang)
            engine.language = locale
            engine.setSpeechRate(SayPlan.speechRate(rate))
            engine.setPitch(SayPlan.pitch(pitch))

            val done = CountDownLatch(1)
            val failed = AtomicBoolean(false)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    done.countDown()
                }

                @Deprecated("required by the base class")
                override fun onError(utteranceId: String?) {
                    failed.set(true)
                    done.countDown()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    failed.set(true)
                    done.countDown()
                }
            })
            val params = Bundle()
            val id = "say-" + System.currentTimeMillis()
            val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
            if (queued != TextToSpeech.SUCCESS) {
                return Admin.ERROR_PREFIX + "语音引擎拒绝了这次朗读"
            }
            val waitSeconds = SayPlan.waitSeconds(text)
            if (!done.await(waitSeconds, TimeUnit.SECONDS)) {
                return Admin.ERROR_PREFIX + "朗读超时（" + waitSeconds + "s 内未播完）"
            }
            if (failed.get()) {
                return Admin.ERROR_PREFIX + "朗读失败（引擎报错）"
            }
            AgentLog.add("say", "已朗读 " + text.length + " 字（" + lang + "）")
            return SayPlan.report(text, rate, pitch, lang)
        } catch (t: Throwable) {
            return Admin.ERROR_PREFIX + "朗读失败: " + describe(t)
        } finally {
            try {
                engine.stop()
                engine.shutdown()
            } catch (t: Throwable) {
                // Nothing to do: the utterance is over either way.
            }
        }
    }

    /**
     * The first locale the engine admits to supporting, best first.
     *
     * `isLanguageAvailable` is the only honest question to ask: installing an
     * English voice pack is the user's choice, and a hard-coded "en works" would
     * fail silently as an utterance in the wrong language.
     */
    private fun pickLocale(engine: TextToSpeech, lang: String): Locale? {
        for (candidate in SayPlan.localeCandidates(lang)) {
            val result = try {
                engine.isLanguageAvailable(candidate)
            } catch (t: Throwable) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (result == TextToSpeech.LANG_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            ) {
                return candidate
            }
        }
        return null
    }

    /**
     * Why the language is missing, and what the operator can do about it.
     *
     * A missing English pack is a *settings* problem on the phone, not a bug in
     * this app, so the message points at the install screen instead of an adb
     * command that would not help.
     */
    private fun missingVoice(lang: String): String =
        if (lang == SayPlan.LANG_EN) {
            "语音引擎没有可用的英文语音包（请在系统设置 → 语言和输入 → 文字转语音 里安装英文数据）"
        } else {
            "语音引擎没有可用的中文语音包（请在系统设置 → 语言和输入 → 文字转语音 里安装中文数据）"
        }
}
