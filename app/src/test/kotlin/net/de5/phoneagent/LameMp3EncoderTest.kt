package net.de5.phoneagent

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真的把 PCM 编成 MP3（v0.7.0-pad12）。
 *
 * 为什么必须真跑：这一版新加的东西里，唯一可能「编译过、单测绿、真机上不出声」
 * 的就是编码链路。jump3r 是纯 Java，所以这里能在普通 JVM 上跑完整一遍，
 * 断言的是「字节流像不像一个 MP3」，而不是「函数被调用过」。
 *
 * 判据（见 SPEC 10 节）：
 *  - 输出大于 30000 字节（2 秒 128 kbps ≈ 32 KB，少一大截说明帧被吞了）
 *  - 第一帧的同步字：0xFF，且第二字节的高三位全 1
 *  - 帧结构完整：从第一个同步字开始逐帧走，正好走到流末尾（允许尾部 ID3v1）
 */
class LameMp3EncoderTest {

    /** 2 秒 440 Hz 正弦，16-bit 量级，交错立体声。 */
    private fun tone(frames: Int, rate: Int, channels: Int, hz: Double = 440.0): IntArray {
        val out = IntArray(frames * channels)
        for (i in 0 until frames) {
            val value = (Math.sin(2.0 * Math.PI * hz * i / rate) * 12000.0).toInt()
            if (channels == 1) {
                out[i] = value
            } else {
                out[i * 2] = value
                out[i * 2 + 1] = value
            }
        }
        return out
    }

    @Test
    fun twoSecondsOfASineBecomeALegalMp3File() {
        val bytes = ByteArrayOutputStream()
        val encoder = LameMp3Encoder(channels = 2, inRate = 44100, out = bytes)
        val frames = 44100 * 2
        encoder.writeInterleaved(tone(frames, 44100, 2), frames)
        val total = encoder.finish()
        val mp3 = bytes.toByteArray()

        assertEquals("finish() 的返回值必须等于真正写出去的字节数", mp3.size.toLong(), total)
        assertTrue("2 秒 128 kbps 至少三万字节目测才像话，实际 " + mp3.size, mp3.size > 30_000)
        assertEquals("第一个字节必须是帧同步 0xFF", 0xFF, mp3[0].toInt() and 0xFF)
        assertEquals(
            "第二字节高三位必须全 1（MPEG 帧同步），实际 " + (mp3[1].toInt() and 0xFF),
            0xE0,
            mp3[1].toInt() and 0xE0
        )
        assertTrue("整条流必须逐帧走通，见 " + firstBadFrame(mp3), firstBadFrame(mp3) == mp3.size)
    }

    @Test
    fun aMonoSourceStaysMono() {
        val bytes = ByteArrayOutputStream()
        val encoder = LameMp3Encoder(channels = 1, inRate = 44100, out = bytes)
        val frames = 44100
        encoder.writeInterleaved(tone(frames, 44100, 1), frames)
        encoder.finish()
        val mp3 = bytes.toByteArray()
        // 单声道的帧头里声道模式是 11（MONO）：第三字节高两位
        val mode = (mp3[3].toInt() and 0xC0) shr 6
        assertEquals("单声道源必须编成单声道（帧头 mode=3），实际 " + mode, 3, mode)
    }

    @Test
    fun a48kSourceIsResampledWithoutBlowingUp() {
        val bytes = ByteArrayOutputStream()
        val encoder = LameMp3Encoder(channels = 2, inRate = 48000, out = bytes)
        val frames = 48000
        encoder.writeInterleaved(tone(frames, 48000, 2), frames)
        encoder.finish()
        val mp3 = bytes.toByteArray()
        assertTrue("重采样路径也必须出得来数据，实际 " + mp3.size, mp3.size > 15_000)
        assertEquals("第一帧同步字仍在", 0xFF, mp3[0].toInt() and 0xFF)
    }

    @Test
    fun finishingTwiceWritesNothingMore() {
        val bytes = ByteArrayOutputStream()
        val encoder = LameMp3Encoder(channels = 2, inRate = 44100, out = bytes)
        val frames = 4410
        encoder.writeInterleaved(tone(frames, 44100, 2), frames)
        val first = encoder.finish()
        val size = bytes.size()
        val second = encoder.finish()
        assertEquals("第二次 finish 不许再写字节", size, bytes.size())
        assertEquals("第二次 finish 必须返回同一个总数", first, second)
    }

    /**
     * 编码速度。老板要的是「每 MB 视频耗时」这个数，而视频的时长才是决定
     * 音频长度的东西，所以这里量的是「每秒音频耗多少毫秒」。
     *
     * 阈值故意松（慢于 0.2 倍速才算失败）：机器忙的时候单次抖动很大，
     * 真慢了应该看趋势，不是卡一个 5 ms 的边界。
     */
    @Test
    fun encodingIsFasterThanRealTimeByALargeMargin() {
        val bytes = ByteArrayOutputStream()
        val encoder = LameMp3Encoder(channels = 2, inRate = 44100, out = bytes)
        val seconds = 10
        val frames = 44100 * seconds
        val pcm = tone(frames, 44100, 2)
        val began = System.nanoTime()
        encoder.writeInterleaved(pcm, frames)
        encoder.finish()
        val elapsedMs = (System.nanoTime() - began) / 1_000_000L
        val speed = seconds * 1000.0 / elapsedMs.toDouble()
        println("MP3 encode speed: " + seconds + "s audio in " + elapsedMs + "ms = " +
            String.format("%.1f", speed) + "x realtime")
        assertTrue(
            "编码必须远快于实时（10 秒音频用了 " + elapsedMs + " ms，约 " +
                String.format("%.2f", speed) + " 倍速）",
            elapsedMs < seconds * 1000L / 5L
        )
    }

    /** 逐帧走一遍 MP3 流，返回走到的位置；遇到不合法的帧就停在那儿。 */
    private fun firstBadFrame(mp3: ByteArray): Int {
        var at = 0
        while (at + 4 <= mp3.size) {
            if ((mp3[at].toInt() and 0xFF) != 0xFF || (mp3[at + 1].toInt() and 0xE0) != 0xE0) {
                return at
            }
            val version = (mp3[at + 1].toInt() shr 3) and 0x03
            val layer = (mp3[at + 1].toInt() shr 1) and 0x03
            val bitrateIndex = (mp3[at + 2].toInt() and 0xFF) shr 4
            val rateIndex = (mp3[at + 2].toInt() shr 2) and 0x03
            val padding = (mp3[at + 2].toInt() shr 1) and 0x01
            if (layer != 0x01) return at // 只处理 Layer III
            val kbps = LAYER3_BITRATES[version][bitrateIndex]
            val rate = SAMPLE_RATES[version][rateIndex]
            if (kbps <= 0 || rate <= 0) return at
            val bitrate = kbps * 1000
            val size = if (version == 0x03) {
                144 * bitrate / rate + padding
            } else {
                72 * bitrate / rate + padding
            }
            if (size <= 4) return at
            at += size
        }
        // 尾部允许 128 字节 ID3v1（我们目前不写，但别让判据太脆）
        if (mp3.size - at == 128 && (mp3[at].toInt() and 0xFF) == 'T'.code) return mp3.size
        return at
    }

    private companion object {
        /** 索引 0..3 对应 MPEG 2.5 / 保留 / MPEG 2 / MPEG 1。 */
        val LAYER3_BITRATES = arrayOf(
            intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0),
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0),
            intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        )
        val SAMPLE_RATES = arrayOf(
            intArrayOf(11025, 12000, 8000, 0),
            intArrayOf(0, 0, 0, 0),
            intArrayOf(22050, 24000, 16000, 0),
            intArrayOf(44100, 48000, 32000, 0)
        )
    }
}
