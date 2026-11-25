package net.de5.phoneagent

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

/**
 * 视频 → MP3 的解码侧（v0.7.0-pad12）。
 *
 * 为什么这么绕：targetSdk 34 起不许 exec 私有目录里的可执行文件，所以 ffmpeg
 * 命令行这条路是死的；把 ffmpeg 打进 APK 又要多 20 MB。能用的只有系统自带的
 * MediaExtractor + MediaCodec（解码）配 jump3r（编码，纯 Java）。
 *
 * 这一层只做三件事：找音轨、把 PCM 喂给 [LameMp3Encoder]、报进度。
 * 句子和数字都在 [Mp3Plan] 里，那边能在普通 JVM 上单测；这里只能在真机上跑，
 * 所以尽量薄。
 */
internal object Mp3Converter {

    /** 一次转换的结果。[channels]/[inRate] 是源的口径，写出来是给人核对的。 */
    class ConvertResult(
        val bytes: Long,
        val elapsedMs: Long,
        val channels: Int,
        val inRate: Int
    )

    /** 扫描结果：目录读不到时 [error] 非空，[rows] 为空。 */
    class ScanResult(val rows: List<Mp3Plan.Row>, val error: String?)

    /**
     * 列目录里所有认得的视频，并逐个数出「有没有音轨」「同名 mp3 在不在」。
     *
     * 只读：不碰文件内容，也不建任何东西。
     */
    fun scan(dirPath: String): ScanResult {
        val dir = File(dirPath)
        if (!dir.isDirectory) return ScanResult(emptyList(), "目录不存在: " + dirPath)
        val files = try {
            dir.listFiles()
        } catch (t: Throwable) {
            return ScanResult(emptyList(), "读不到目录: " + describe(t))
        } ?: return ScanResult(emptyList(), "读不到目录: " + dirPath)

        val rows = ArrayList<Mp3Plan.Row>()
        for (file in files) {
            if (!file.isFile) continue
            if (!Mp3Plan.isVideoName(file.name)) continue
            val probe = probe(file)
            rows.add(
                Mp3Plan.Row(
                    name = file.name,
                    bytes = file.length(),
                    durationMs = probe.durationMs,
                    hasAudio = probe.hasAudio,
                    targetExists = File(dir, Mp3Plan.targetName(file.name)).isFile
                )
            )
        }
        rows.sortBy { it.name.lowercase() }
        return ScanResult(rows, null)
    }

    private class Probe(val hasAudio: Boolean, val durationMs: Long)

    /**
     * 只问两件事：有没有音轨、多长。
     *
     * 解不出来的容器按「无音轨」处理：对操作员来说结果一样（这个文件转不了），
     * 而把它报成「失败」会让一次扫描里混进一堆本来就该跳过的文件。
     */
    private fun probe(file: File): Probe {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }
                return Probe(true, durationUs / 1000L)
            }
            return Probe(false, 0L)
        } catch (t: Throwable) {
            AgentLog.add("mp3", "探测失败 " + file.name + ": " + describe(t))
            return Probe(false, 0L)
        } finally {
            try {
                extractor.release()
            } catch (ignored: Throwable) {
                // release 抛异常只说明底层 native 已经没了，没什么可做的。
            }
        }
    }

    /**
     * 真的转一个文件。成功返回结果，失败抛异常（调用方转成一行失败文案）。
     *
     * 先写 target.part 再改名：中途失败不会留下一个能播一半的 mp3，
     * 也就不会让下一次 run 误以为「已经有了」而跳过。
     */
    fun convert(source: File, target: File, onProgress: (Int) -> Unit): ConvertResult {
        val began = System.currentTimeMillis()
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val part = File(target.parentFile, target.name + ".part")
        var stream: FileOutputStream? = null
        var encoder: LameMp3Encoder? = null

        try {
            extractor.setDataSource(source.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = index
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) throw IllegalStateException("没有音轨")
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("音轨没有 MIME")
            val inRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            // 解码器吐出来的可能是 16-bit 也可能是 float（API 21+ 允许源这么声明），
            // 两种都要认，否则 float 源会被当成 16-bit 编出一片噪声。
            val pcmFloat = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT

            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()

            if (part.exists()) part.delete()
            stream = FileOutputStream(part)
            val lame = LameMp3Encoder(channels = channels, inRate = inRate, out = stream)
            encoder = lame

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcm = IntArray(4096)
            var lastPercent = -1

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val input = codec.getInputBuffer(inIndex)
                        if (input == null) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, 0)
                        } else {
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val output = codec.getOutputBuffer(outIndex)
                        if (output != null) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val frames: Int
                            if (pcmFloat) {
                                val floats = output.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                frames = floats.remaining() / channels
                                val needed = frames * channels
                                if (pcm.size < needed) pcm = IntArray(needed)
                                for (i in 0 until needed) {
                                    pcm[i] = (floats.get(i) * 32768.0f).toInt().coerceIn(-32768, 32767)
                                }
                            } else {
                                val shorts = output.order(ByteOrder.nativeOrder()).asShortBuffer()
                                frames = shorts.remaining() / channels
                                val needed = frames * channels
                                if (pcm.size < needed) pcm = IntArray(needed)
                                for (i in 0 until needed) pcm[i] = shorts.get(i).toInt()
                            }
                            if (frames > 0) lame.writeInterleaved(pcm, frames)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (durationUs > 0L) {
                        val percent = Mp3Plan.percent(extractor.sampleTime, durationUs)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 解码器改了口径（采样率/声道数）时这里能读到新值，但编码器
                    // 已经按轨道头建好了。真遇到这种源，宁可报错也不要悄悄错位。
                    val changed = codec.outputFormat
                    val newRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val newChannels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (newRate != inRate || newChannels != channels) {
                        throw IllegalStateException(
                            "解码器改变了格式（" + inRate + "Hz/" + channels + "ch → " +
                                newRate + "Hz/" + newChannels + "ch），暂不支持"
                        )
                    }
                }
            }

            val total = lame.finish()
            encoder = null
            stream.flush()
            stream.close()
            stream = null
            onProgress(100)
            if (!part.renameTo(target)) {
                // 有些文件系统上 rename 会因为目标已存在而失败，先删再试一次。
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IllegalStateException("改名失败: " + target.name)
            }
            return ConvertResult(
                bytes = total,
                elapsedMs = System.currentTimeMillis() - began,
                channels = channels,
                inRate = inRate
            )
        } catch (t: Throwable) {
            try {
                encoder?.finish()
            } catch (ignored: Throwable) {
                // 已经在失败路径上，收尾再抛就把真正的原因盖掉了。
            }
            try {
                stream?.close()
            } catch (ignored: Throwable) {
            }
            if (part.exists()) part.delete()
            throw t
        } finally {
            try {
                decoder?.stop()
            } catch (ignored: Throwable) {
            }
            try {
                decoder?.release()
            } catch (ignored: Throwable) {
            }
            try {
                extractor.release()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** 解码器等缓冲区的上限：一帧 PCM 正常几毫秒就到，10 ms 是安全余量。 */
    private const val TIMEOUT_US = 10_000L
}
