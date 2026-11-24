package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * `say` is the command the operator actually hears, so the two things that can
 * go wrong silently — the wrong language and an unbounded wait — are pinned here
 * (SPEC.md §3.9).
 */
class SayPlanTest {

    @Test
    fun theDefaultVoiceIsChinese() {
        assertEquals("zh", SayPlan.LANG_DEFAULT)
        assertEquals(Locale.SIMPLIFIED_CHINESE, SayPlan.locale(SayPlan.LANG_ZH))
        assertEquals(Locale.US, SayPlan.locale(SayPlan.LANG_EN))
    }

    @Test
    fun everySpellingOfALanguageLandsOnOneOfTwoVoices() {
        for (raw in listOf("zh", "ZH", "zh-cn", "zh_CN", "cn", "Chinese", "中文", "汉语")) {
            assertEquals(raw, SayPlan.LANG_ZH, SayPlan.lang(raw))
        }
        for (raw in listOf("en", "EN", "en-us", "en_GB", "English", "英文", "英语")) {
            assertEquals(raw, SayPlan.LANG_EN, SayPlan.lang(raw))
        }
    }

    @Test
    fun anUnknownLanguageIsRefusedRatherThanGuessed() {
        // Silently falling back would read an English paragraph with a Chinese
        // voice, which sounds like a bug in the phone.
        assertNull(SayPlan.lang(null))
        assertNull(SayPlan.lang(""))
        assertNull(SayPlan.lang("fr"))
        assertNull(SayPlan.lang("de-DE"))
    }

    @Test
    fun theDeviceDefaultIsTheLastLanguageCandidate() {
        val en = SayPlan.localeCandidates(SayPlan.LANG_EN)
        assertEquals(Locale.US, en[0])
        assertEquals(Locale.getDefault(), en[en.size - 1])
        val zh = SayPlan.localeCandidates(SayPlan.LANG_ZH)
        assertEquals(Locale.SIMPLIFIED_CHINESE, zh[0])
        assertEquals(Locale.getDefault(), zh[zh.size - 1])
    }

    @Test
    fun rateIsAPercentageClampedToTheUsableBand() {
        assertEquals(100, SayPlan.RATE_DEFAULT)
        assertEquals(20, SayPlan.clampRate(0))
        assertEquals(400, SayPlan.clampRate(9999))
        assertEquals(1.5f, SayPlan.speechRate(150), 0.0001f)
        assertEquals(0.2f, SayPlan.speechRate(1), 0.0001f)
    }

    @Test
    fun pitchUsesItsOwnNarrowerBand() {
        assertEquals(100, SayPlan.PITCH_DEFAULT)
        assertEquals(50, SayPlan.clampPitch(1))
        assertEquals(200, SayPlan.clampPitch(500))
        assertEquals(1.0f, SayPlan.pitch(100), 0.0001f)
    }

    @Test
    fun aHugeTextIsClippedBeforeItReachesTheEngine() {
        val long = "字".repeat(SayPlan.TEXT_MAX + 500)
        assertEquals(SayPlan.TEXT_MAX, SayPlan.clipText(long).length)
        assertEquals("abc", SayPlan.clipText("abc"))
    }

    @Test
    fun theWaitGrowsWithTheTextAndStaysInsideItsBounds() {
        // 5 s floor so a one-word utterance still finishes; 120 s ceiling so a
        // stuck engine cannot pin the poll loop for minutes.
        assertEquals(5L, SayPlan.waitSeconds(""))
        assertEquals(5L, SayPlan.waitSeconds("短"))
        assertTrue(SayPlan.waitSeconds("字".repeat(400)) > 5L)
        assertEquals(120L, SayPlan.waitSeconds("字".repeat(SayPlan.TEXT_MAX)))
    }

    @Test
    fun theReportNamesTheLanguageRateAndLength() {
        val line = SayPlan.report("你好", 150, 90, SayPlan.LANG_ZH)
        assertEquals("已朗读 2 字（lang=zh rate=150 pitch=90）", line)
        assertTrue(SayPlan.failure("x").startsWith("朗读失败: "))
    }

    @Test
    fun theHyphenatedScriptTagIsTheSameChineseVoice() {
        // "zh-Hans" is what a locale picker writes; refusing it would look like
        // the phone forgot how to speak between two sessions.
        assertEquals(SayPlan.LANG_ZH, SayPlan.lang("zh-Hans"))
        assertEquals(SayPlan.LANG_ZH, SayPlan.lang("  ZH_cn  "))
        assertEquals(SayPlan.LANG_EN, SayPlan.lang(" en_GB "))
    }

    @Test
    fun textAtTheLimitIsNotTouched() {
        val exact = "a".repeat(SayPlan.TEXT_MAX)
        assertEquals(SayPlan.TEXT_MAX, SayPlan.clipText(exact).length)
        assertEquals(exact, SayPlan.clipText(exact))
    }

    @Test
    fun theUsageLineShowsTheLanguageFlag() {
        assertTrue(SayPlan.USAGE.contains("-l zh|en"))
        assertTrue(SayPlan.USAGE.contains("rate="))
        assertTrue(SayPlan.USAGE.contains("pitch="))
    }
}
