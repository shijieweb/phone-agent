package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码口径的判据（v0.7.2-pad12）。
 *
 * 为什么值得单独一个文件：v0.7.0/v0.7.1 用的是 MediaExtractor 的轨道 format，
 * 而权威来源是 MediaCodec 的 outputFormat。两条真实后果——HE-AAC 的正常视频被
 * 判「解码器改变了格式，暂不支持」，以及 float 声明配 short 输出时读出噪声。
 * 这两个错都在真机上才炸，而判据本身是纯算术，所以在这里一条一条钉死。
 */
class DecodeShapeTest {

    /**
     * 妈妈真会遇到的场景：手机拍的 HE-AAC，轨道头写 22050，解码器 SBR 翻倍成 44100。
     * 旧代码拿轨道头建编码器，然后在 INFO_OUTPUT_FORMAT_CHANGED 里判失败。
     */
    @Test
    fun heAacTrackHeaderLosesToTheDecoderOutput() {
        val shape = decodeShapeOf(
            trackRate = 22050, trackChannels = 2, trackPcmEncoding = null,
            outRate = 44100, outChannels = 2, outPcmEncoding = null
        )
        assertEquals("HE-AAC 的 SBR 翻倍必须按解码器输出算", 44100, shape.rate)
        assertEquals(2, shape.channels)
        assertNull(shape.unsupported)
    }

    /** 轨道声明 float、解码器实际吐 short（androidx/media #565）：按 short 读才对。 */
    @Test
    fun aFloatTrackWithShortOutputIsReadAsShort() {
        val shape = decodeShapeOf(
            trackRate = 44100, trackChannels = 2, trackPcmEncoding = PCM_FLOAT,
            outRate = 44100, outChannels = 2, outPcmEncoding = 2
        )
        assertEquals("解码器说 short 就得按 short 读", false, shape.isFloat)
    }

    /** 两边都没声明 PCM 编码时默认 short —— 这是 Android 音频解码器的默认值。 */
    @Test
    fun nobodyDeclaringPcmMeansShort() {
        val shape = decodeShapeOf(
            trackRate = 44100, trackChannels = 2, trackPcmEncoding = null,
            outRate = 44100, outChannels = 2, outPcmEncoding = null
        )
        assertEquals(false, shape.isFloat)
    }

    /** 解码器把 5.1 折叠成单声道：以解码器为准，正常转。 */
    @Test
    fun theDecoderFoldingToMonoWins() {
        val shape = decodeShapeOf(
            trackRate = 48000, trackChannels = 6, trackPcmEncoding = null,
            outRate = 48000, outChannels = 1, outPcmEncoding = null
        )
        assertEquals("解码器输出 1 声道就用 1 声道", 1, shape.channels)
        assertNull("折叠成立体声/单声道是可以转的", shape.unsupported)
    }

    /** 输出采样率读成 0（声明了但值不可用）：回落轨道声明。 */
    @Test
    fun anInvalidOutputRateFallsBackToTheTrack() {
        val shape = decodeShapeOf(
            trackRate = 48000, trackChannels = 2, trackPcmEncoding = null,
            outRate = 0, outChannels = 2, outPcmEncoding = null
        )
        assertEquals(48000, shape.rate)
    }

    /**
     * 解码器说 6 声道：必须明确失败，绝不回落成 2。
     *
     * 按 2 路去喂 6 路交错数据会让样本整体错位，编出来是刺耳噪声——那比失败更糟，
     * 家里没人能现场判断是文件坏了还是功能坏了。
     */
    @Test
    fun sixChannelsFromTheDecoderIsRefusedNotDowngraded() {
        val shape = decodeShapeOf(
            trackRate = 48000, trackChannels = 6, trackPcmEncoding = null,
            outRate = 48000, outChannels = 6, outPcmEncoding = null
        )
        assertTrue("6 声道必须带一句能看懂的拒绝理由", shape.unsupported != null)
        assertTrue(
            "理由里要出现声道数，别只说「不支持」",
            shape.unsupported!!.contains("6")
        )
    }

    /** 轨道声明 6 声道、解码器实际输出 2 声道：按解码器正常转。 */
    @Test
    fun aSurroundTrackThatTheDecoderDownmixesIsFine() {
        val shape = decodeShapeOf(
            trackRate = 48000, trackChannels = 6, trackPcmEncoding = null,
            outRate = 48000, outChannels = 2, outPcmEncoding = null
        )
        assertNull(shape.unsupported)
        assertEquals(2, shape.channels)
    }

    /** 解码器什么都没说、轨道也非法（0 声道）：给 Android 的默认口径 44100/2/short。 */
    @Test
    fun everythingMissingFallsBackToThePlatformDefault() {
        val shape = decodeShapeOf(
            trackRate = 0, trackChannels = 0, trackPcmEncoding = null,
            outRate = null, outChannels = null, outPcmEncoding = null
        )
        assertEquals(44100, shape.rate)
        assertEquals(2, shape.channels)
        assertEquals(false, shape.isFloat)
        assertNull(shape.unsupported)
    }

    /** 解码器明说 float：按 float 读。 */
    @Test
    fun aFloatOutputIsReadAsFloat() {
        val shape = decodeShapeOf(
            trackRate = 44100, trackChannels = 2, trackPcmEncoding = null,
            outRate = 44100, outChannels = 2, outPcmEncoding = PCM_FLOAT
        )
        assertEquals(true, shape.isFloat)
    }
}
