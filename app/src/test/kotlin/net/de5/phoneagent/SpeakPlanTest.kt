package net.de5.phoneagent

import android.media.AudioAttributes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * `speak` (v0.10.0-pad12, SPEC.md §3.30).
 *
 * Three things here are load-bearing and none of them can be seen on a phone
 * without a stopwatch:
 *
 *  - the chunk sizes. Synthesis costs about 0.10 s per character, so the first
 *    chunk is capped at 40 characters (about 4 s of silence) and every later one
 *    at 120 (about 12 s), even though the server would accept 500 (about 62 s).
 *  - the receipt. A partial playback must NOT fall back to the system voice, or
 *    the listener hears the opening twice.
 *  - the synthesis fan-out (v0.11.3-pad12): three chunks at a time, four queued,
 *    handed over in index order -- the order and the bounds themselves are
 *    pinned in [SynthBoardTest].
 *  - the wire format. The body is hand-written JSON, so its exact bytes are
 *    pinned here -- a stray backslash is a 400 from the server.
 */
class SpeakPlanTest {

    // ---------------------------------------------------------------- grammar

    @Test
    fun switchesAreOnlyConsumedWhileTheyLead() {
        val request = SpeakPlan.parse("voice=7f92 speed=1.5 你好 世界")
        assertTrue(request != null)
        assertEquals("7f92", request?.voice)
        assertEquals(1.5f, request?.speed ?: 0f, 0.0001f)
        assertEquals("你好 世界", request?.text)
    }

    @Test
    fun aSentenceThatMerelyContainsSpeedKeepsItVerbatim() {
        val request = SpeakPlan.parse("把 speed=2 这个词读出来")
        assertTrue(request != null)
        assertEquals("", request?.voice)
        assertEquals(SpeakPlan.SPEED_DEFAULT, request?.speed ?: 0f, 0.0001f)
        assertEquals("把 speed=2 这个词读出来", request?.text)
    }

    @Test
    fun innerSpacesOfThePayloadSurvive() {
        val request = SpeakPlan.parse("voice=v1  一二三   四五六")
        assertEquals("一二三   四五六", request?.text)
    }

    @Test
    fun unusableLinesAreRefusedRatherThanGuessed() {
        assertNull(SpeakPlan.parse(""))
        assertNull(SpeakPlan.parse("   "))
        assertNull(SpeakPlan.parse("speed=1.5"))
        assertNull(SpeakPlan.parse("voice="))
        assertNull(SpeakPlan.parse("voice= hi"))
        assertNull(SpeakPlan.parse("speed=abc hi"))
    }

    @Test
    fun speedIsClampedToTheServersRange() {
        assertEquals(0.5f, SpeakPlan.parse("speed=0.1 hi")?.speed ?: 0f, 0.0001f)
        assertEquals(2.0f, SpeakPlan.parse("speed=9 hi")?.speed ?: 0f, 0.0001f)
        assertEquals("1.00", SpeakPlan.speedText(1.0f))
        assertEquals("0.50", SpeakPlan.speedText(0.1f))
        assertEquals("2.00", SpeakPlan.speedText(3.0f))
        assertEquals("1.25", SpeakPlan.speedText(1.25f))
    }

    @Test
    fun theNormalisedArgumentStringKeepsOnlyTheSwitchesThatMatter() {
        assertEquals("voice=v1 你好", SpeakPlan.normalize(SpeakPlan.Request("v1", 1.0f, "你好")))
        assertEquals("speed=1.50 你好", SpeakPlan.normalize(SpeakPlan.Request("", 1.5f, "你好")))
        assertEquals("你好", SpeakPlan.normalize(SpeakPlan.Request("", 1.0f, "你好")))
        assertEquals("usage: speak [voice=ID] [speed=0.5-2.0] TEXT", SpeakPlan.USAGE)
    }

    @Test
    fun theBakedVoiceIsUsedOnlyWhenTheLineNamesNone() {
        assertEquals("v1", SpeakPlan.voiceOrDefault("v1", "baked"))
        assertEquals("baked", SpeakPlan.voiceOrDefault("", "baked"))
        assertEquals("baked", SpeakPlan.voiceOrDefault("   ", "baked"))
        assertEquals("", SpeakPlan.voiceOrDefault("", "  "))
        assertEquals("http://43.166.6.110", SpeakPlan.endpoint(" http://43.166.6.110/ "))
        assertEquals("http://43.166.6.110", SpeakPlan.endpoint("http://43.166.6.110"))
        assertEquals("tts-0.mp3", SpeakPlan.chunkFileName(0))
        assertEquals("tts-12.mp3", SpeakPlan.chunkFileName(12))
    }

    // --------------------------------------------------------------- chunking

    @Test
    fun theLimitsAreTheMeasuredOnes() {
        assertEquals(40, SpeakPlan.FIRST_MAX)
        assertEquals(120, SpeakPlan.CHUNK_MAX)
        // The server refuses 501 with a 413; the client never asks for that.
        assertEquals(500, SpeakPlan.SERVER_MAX)
        assertTrue(SpeakPlan.CHUNK_MAX < SpeakPlan.SERVER_MAX)
        assertTrue(SpeakPlan.FIRST_MAX < SpeakPlan.CHUNK_MAX)
        // v0.11.3-pad12: at or below this the text is one request, not a first
        // chunk plus a tail -- 80 characters is ~8 s of synthesis, which is as
        // long as the first sound may wait.
        assertEquals(80, SpeakPlan.SINGLE_SHOT_MAX)
        assertTrue(SpeakPlan.FIRST_MAX < SpeakPlan.SINGLE_SHOT_MAX)
        assertTrue(SpeakPlan.SINGLE_SHOT_MAX <= SpeakPlan.CHUNK_MAX)
    }

    @Test
    fun blankTextIsNoChunksAtAll() {
        assertEquals(emptyList<String>(), SpeakPlan.chunks(""))
        assertEquals(emptyList<String>(), SpeakPlan.chunks("   "))
    }

    @Test
    fun aShortLineIsOneChunkAndIsNotRewritten() {
        val text = "现在就回家。"
        assertEquals(listOf(text), SpeakPlan.chunks(text))
        val exactly = "测".repeat(SpeakPlan.FIRST_MAX)
        assertEquals(listOf(exactly), SpeakPlan.chunks(exactly))
    }

    @Test
    fun aLineUpToEightyCharactersIsOneRequestAndEightyOneIsTwo() {
        // v0.11.3-pad12. Cutting a 60-character line into 40 + 20 (and a
        // 44-character one into 40 + 4) bought an earlier first sound at the
        // price of an extra round trip, an audible join, and a tail synthesized
        // on its own without the sentence's intonation. The owner said he can
        // wait, so the whole line goes out in one request up to 80 characters;
        // 81 is the first length that chunks again.
        val sixty = "测".repeat(60)
        assertEquals(listOf(sixty), SpeakPlan.chunks(sixty))
        val eighty = "测".repeat(SpeakPlan.SINGLE_SHOT_MAX)
        assertEquals(listOf(eighty), SpeakPlan.chunks(eighty))
        val eightyOne = "测".repeat(SpeakPlan.SINGLE_SHOT_MAX + 1)
        val chunks = SpeakPlan.chunks(eightyOne)
        assertEquals(2, chunks.size)
        assertEquals(40, chunks[0].length)
        assertEquals(41, chunks[1].length)
        assertEquals(eightyOne, chunks.joinToString(""))
    }

    @Test
    fun aHundredAndTwentyCharactersStayWithinTheLaterLimit() {
        val chunks = SpeakPlan.chunks("测".repeat(120))
        assertEquals(2, chunks.size)
        assertEquals(40, chunks[0].length)
        assertEquals(80, chunks[1].length)
        assertTrue(chunks.all { it.length <= SpeakPlan.CHUNK_MAX })
    }

    @Test
    fun twoHundredCharactersBecomeThreeChunks() {
        val chunks = SpeakPlan.chunks("测".repeat(200))
        assertEquals(3, chunks.size)
        assertEquals(listOf(40, 120, 40), chunks.map { it.length })
        assertEquals("测".repeat(200), chunks.joinToString(""))
    }

    @Test
    fun aCutPrefersASentenceEndThenAClauseEndThenTheHardLimit() {
        // Both payloads are over SINGLE_SHOT_MAX on purpose: a short line is one
        // chunk now, and one chunk cannot demonstrate a preference.
        assertEquals("你好世界。", SpeakPlan.chunks("你好世界。" + "a".repeat(90))[0])
        assertEquals(
            "一二三四五六七八九十，",
            SpeakPlan.chunks("一二三四五六七八九十，" + "b".repeat(90))[0]
        )
    }

    @Test
    fun aTwoHundredAndFortyCharacterUtteranceStartsWithinFourtyCharacters() {
        // Eight 30-character sentences: 240 characters, all punctuated.
        val sentence = "测".repeat(29) + "。"
        val text = sentence.repeat(8)
        assertEquals(240, text.length)
        val chunks = SpeakPlan.chunks(text)
        assertTrue(chunks.size >= 3)
        assertTrue(chunks[0].length <= SpeakPlan.FIRST_MAX)
        for (index in 1 until chunks.size) {
            assertTrue(chunks[index].length <= SpeakPlan.CHUNK_MAX)
        }
        assertEquals(text, chunks.joinToString(""))
        // The first chunk is one whole sentence, so the first sound is natural.
        assertEquals(sentence, chunks[0])
    }

    @Test
    fun aLongSentenceIsCutAtCommasRatherThanMidWord() {
        val clause = "一二三四五六七八九十，"
        val text = clause.repeat(12) + clause
        val chunks = SpeakPlan.chunks(text)
        assertEquals(text, chunks.joinToString(""))
        for (index in 0 until chunks.size) {
            assertTrue(chunks[index].length <= SpeakPlan.CHUNK_MAX)
            assertTrue(chunks[index].endsWith("，"))
        }
    }

    @Test
    fun aChunkNeverSplitsASurrogatePair() {
        // 39 ASCII characters then an emoji: the hard cut at 40 would land in
        // the middle of the pair, so it backs off by one.
        // 39 + one emoji (two UTF-16 units) + 60 = 101 characters, i.e. over
        // SINGLE_SHOT_MAX, so the cut at 40 is really exercised.
        val text = "a".repeat(39) + "\uD83D\uDE00" + "b".repeat(60)
        val chunks = SpeakPlan.chunks(text)
        assertEquals(39, chunks[0].length)
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun everyChunkIsNonEmptyAndTheTextIsNeverLost() {
        val text = "第一句。第二句，还有一点内容！第三句；第四句…" + "x".repeat(300) + "完。"
        val chunks = SpeakPlan.chunks(text)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.none { it.isEmpty() })
        assertTrue(chunks[0].length <= SpeakPlan.FIRST_MAX)
        for (index in 1 until chunks.size) {
            assertTrue(chunks[index].length <= SpeakPlan.CHUNK_MAX)
        }
        assertEquals(text, chunks.joinToString(""))
    }

    // ------------------------------------------------------------- wire format

    @Test
    fun theBodyIsExactlyWhatTheServerExpects() {
        assertEquals(
            "{\"text\":\"你好\",\"voice\":\"v1\",\"speed\":1.00}",
            SpeakPlan.requestBody("你好", "v1", 1.0f)
        )
        assertEquals(
            "{\"text\":\"a\\\"b\\\\c\\nd\",\"voice\":\"v1\",\"speed\":1.00}",
            SpeakPlan.requestBody("a\"b\\c\nd", "v1", 1.0f)
        )
        assertEquals(
            "{\"text\":\"x\",\"voice\":\"v\",\"speed\":1.50}",
            SpeakPlan.requestBody("x", "v", 1.5f)
        )
    }

    // --------------------------------------------------------------- receipts

    @Test
    fun aFinishedRunReportsCharsChunksFirstSoundAndBytes() {
        assertEquals(
            "speak 已朗读 240 字（fish voice=7f92f8af speed=1.00 4 块 首声 2.40s 总 14.80s 712000B）",
            SpeakPlan.successLine(240, "7f92f8af", 1.0f, 4, 2400L, 14800L, 712000L)
        )
    }

    @Test
    fun aFallbackSaysWhyTheFishPathWasAbandoned() {
        assertEquals(
            "speak 已朗读 24 字（回落系统 TTS：服务端 502）",
            SpeakPlan.fallbackLine(24, "服务端 502")
        )
        assertEquals("未配置 TTS 服务", SpeakPlan.NOT_CONFIGURED)
        assertEquals("服务端返回空音频", SpeakPlan.EMPTY_AUDIO)
    }

    @Test
    fun aPartialRunNeverClaimsToHaveReadEverything() {
        assertEquals(
            "speak 已播 2/3 块，第 3 块失败：服务端 502，未回落（避免重复朗读）",
            SpeakPlan.partialLine(2, 3, 3, "服务端 502")
        )
    }

    @Test
    fun serverErrorsKeepTheServersOwnWords() {
        assertEquals("服务端 502", SpeakPlan.httpFailure(502, ""))
        assertEquals("服务端 401（invalid secret）", SpeakPlan.httpFailure(401, " invalid secret "))
        assertEquals(
            "invalid secret: bad key",
            SpeakPlan.errorHint("{\"error\":\"invalid secret\",\"detail\":\"bad key\"}")
        )
        assertEquals("", SpeakPlan.errorHint("   "))
        // Not JSON: the raw body is the hint, clipped so a receipt stays a line.
        assertEquals("oops", SpeakPlan.errorHint("oops"))
        val long = SpeakPlan.errorHint("z".repeat(200))
        assertTrue(long.length <= 81)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun aStuckPlayerIsBoundedByTheDecodedDuration() {
        assertEquals(SpeakPlan.PLAY_FALLBACK_MS, SpeakPlan.playbackCapMs(0L))
        assertEquals(SpeakPlan.PLAY_FALLBACK_MS, SpeakPlan.playbackCapMs(-1L))
        assertEquals(3000L + SpeakPlan.PLAY_SLACK_MS, SpeakPlan.playbackCapMs(3000L))
        assertEquals("播放超时（8.00s）", SpeakPlan.playTimeout(8000L))
        assertEquals("2.40s", SpeakPlan.seconds(2400L))
        assertEquals("0.00s", SpeakPlan.seconds(0L))
    }

    @Test
    fun theTimeoutsAreTheAgreedOnes() {
        assertEquals(10_000, SpeakPlan.CONNECT_TIMEOUT_MS)
        assertEquals(90_000, SpeakPlan.READ_TIMEOUT_MS)
    }

    @Test
    fun parallelSynthesisIsThreeWideAndGivesUpOnAWedgedChunk() {
        // v0.11.3-pad12. One synthesizer could not keep up (0.10 s per
        // character against 4-5 characters a second of playback), so every
        // sentence boundary waited seconds. Three run at once, four finished
        // chunks may queue, and a chunk that never arrives is abandoned after
        // 110 s -- the upstream service took 80.8 s for 1 request in 10, so the
        // wait has to be finite or one dead connection hangs the command.
        assertEquals(3, SpeakPlan.SYNTH_WORKERS)
        assertEquals(4, SpeakPlan.QUEUE_CAPACITY)
        assertEquals(110_000L, SpeakPlan.SYNTH_WAIT_MS)
        assertTrue(SpeakPlan.SYNTH_WAIT_MS > SpeakPlan.READ_TIMEOUT_MS)
        assertEquals("合成卡死（已等 110.00s）", SpeakPlan.synthTimeout(110_000L))
        assertEquals("合成失败：boom", SpeakPlan.synthFailure(IllegalStateException("boom")))
    }

    // -------------------------------------------------------------- audio route

    @Test
    fun speakAsksForTheMediaStream() {
        // USAGE_MEDIA, not USAGE_ASSISTANT (v0.11.1-pad12). Measured on the
        // MEIZU 22 / Android 16 with `dumpsys audio`: USAGE_ASSISTANT routes to
        // STREAM_ASSISTANT (max 15, sitting at 11 = 73%), USAGE_MEDIA routes to
        // STREAM_MUSIC (max 60, speaker at 60) -- the stream the owner himself
        // adjusts, and the one "STREAM_TTS (aliased to: STREAM_MUSIC)" puts the
        // system voice on. Both halves are pinned: the value we want, and the
        // one v0.11.0 shipped and must not come back.
        assertEquals(AudioAttributes.USAGE_MEDIA, FeatSpeak.SPEAK_USAGE)
        assertTrue(FeatSpeak.SPEAK_USAGE != AudioAttributes.USAGE_ASSISTANT)
    }

    @Test
    fun speakNeverTouchesThePhonesVolume() {
        // v0.11.2-pad12: the owner adjusts his own volume, so `speak` must not
        // read or write it. There is no pure-logic way to say "this class does
        // not call AudioManager" -- AudioManager only exists on a device -- so
        // the shape of the source is what is pinned: no volume-guard class, no
        // stream accessor, no setStreamVolume anywhere in FeatSpeak.kt.
        //
        // The file is read from the module directory Gradle runs unit tests in;
        // if that ever moves, the test is skipped rather than failed, and the
        // device check in manualcheck.md remains the real proof.
        val source = File("src/main/kotlin/net/de5/phoneagent/FeatSpeak.kt")
        Assume.assumeTrue("FeatSpeak.kt not found from " + File(".").absolutePath, source.isFile)
        val text = source.readText()
        assertFalse(text.contains("VolumeGuard"))
        assertFalse(text.contains("AudioManager"))
        assertFalse(text.contains("setStreamVolume"))
        assertFalse(text.contains("getStreamVolume"))
        assertTrue(text.contains("USAGE_MEDIA"))
    }
}
