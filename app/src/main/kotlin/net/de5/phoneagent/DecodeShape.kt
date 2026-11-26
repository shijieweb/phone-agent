package net.de5.phoneagent

/**
 * 一次解码会话真正该用的口径：采样率、声道数、PCM 是不是 float（v0.7.2-pad12）。
 *
 * 为什么单独抽出来：v0.7.0/v0.7.1 是从 MediaExtractor 的轨道 format 读这三个数的，
 * 而权威来源是 MediaCodec 的 outputFormat。两个真实后果：
 *
 * 1. 轨道声明 float、解码器实际吐 short（androidx/media issue #565 里 pcm-encoding 4
 *    被改成了 2）→ 把 short 当 float 读，编出来一片噪声。
 * 2. 轨道头报 22050、解码器输出 44100（HE-AAC 的 SBR 把采样率翻倍，手机拍的视频里
 *    很常见）→ 旧代码在 INFO_OUTPUT_FORMAT_CHANGED 里直接判「解码器改变了格式，
 *    暂不支持」，一条完全正常的视频被判失败。
 *
 * 这个文件不 import android.*，所以整套判据能在普通 JVM 上单测（DecodeShapeTest）。
 */
internal data class DecodeShape(
    val rate: Int,
    val channels: Int,
    val isFloat: Boolean,
    /**
     * 非空表示「这一条转不了」，值是给操作员看的整句话。
     *
     * 为什么不从纯函数里抛异常：判据要在单测里一条一条断言，抛异常就得每条用例
     * 套 try/catch，写出来全是噪音。让调用方（Mp3Converter）看到非空就抛。
     */
    val unsupported: String? = null
)

/** AudioFormat.ENCODING_PCM_FLOAT 的数值。这里不 import android.media 就是为了可单测。 */
internal const val PCM_FLOAT = 4

/** 什么都读不到时的兜底：Android 音频解码器的默认输出口径。 */
internal const val FALLBACK_RATE = 44100
internal const val FALLBACK_CHANNELS = 2

/**
 * 解码器输出优先，缺字段回落轨道声明，最后给兜底值。
 *
 * 三条规则按优先级写死，不做任何「看起来更聪明」的推断：
 *
 * - **rate**：outRate 有效（大于 0）用它；否则 trackRate 有效用它；都无效给 44100。
 * - **channels**：outChannels 是 1 或 2 用它；outChannels 大于 2 直接判不支持
 *   （见下）；否则 trackChannels 是 1 或 2 用它；都无效给 2。
 * - **isFloat**：outPcmEncoding 等于 PCM_FLOAT 才是 true；outPcmEncoding 为 null 时
 *   再看 trackPcmEncoding；两个都没声明就是 false（16-bit short 是 Android 音频
 *   解码器的默认值，不是我们的猜测）。
 *
 * **环绕声必须明确失败，不许回落。** 解码器说 6 声道，就说明交错数据是 6 路；按 2 路
 * 去喂 LAME 会让样本整体错位，编出来是刺耳的噪声——那比失败更糟，家里没人能现场判断
 * 是「文件坏了」还是「功能坏了」。所以 outChannels 大于 2 时把整句话放进 unsupported，
 * 调用方立刻抛。轨道声明 6 声道、但解码器实际输出 2 声道，仍按解码器正常转。
 *
 * 参数里的 null 一律表示「这一侧没有声明这个字段」，0 或负数表示「声明了但值不可用」。
 */
internal fun decodeShapeOf(
    trackRate: Int,
    trackChannels: Int,
    trackPcmEncoding: Int?,
    outRate: Int?,
    outChannels: Int?,
    outPcmEncoding: Int?
): DecodeShape {
    val rate = when {
        outRate != null && outRate > 0 -> outRate
        trackRate > 0 -> trackRate
        else -> FALLBACK_RATE
    }
    val surround = when {
        outChannels != null && outChannels > 2 -> outChannels
        (outChannels == null || outChannels <= 0) && trackChannels > 2 -> trackChannels
        else -> 0
    }
    val channels = when {
        outChannels != null && (outChannels == 1 || outChannels == 2) -> outChannels
        trackChannels == 1 || trackChannels == 2 -> trackChannels
        else -> FALLBACK_CHANNELS
    }
    val isFloat = when {
        outPcmEncoding != null -> outPcmEncoding == PCM_FLOAT
        trackPcmEncoding != null -> trackPcmEncoding == PCM_FLOAT
        else -> false
    }
    val unsupported = if (surround > 2) {
        "这条视频是 " + surround + " 声道（环绕声），暂不支持，需要先在别处转成立体声"
    } else {
        null
    }
    return DecodeShape(rate, channels, isFloat, unsupported)
}
