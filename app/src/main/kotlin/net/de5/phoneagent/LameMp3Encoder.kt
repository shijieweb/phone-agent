package net.de5.phoneagent

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.BRHist
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.OutputStream

/**
 * PCM → MP3，用 jump3r（LAME 的纯 Java 移植，v0.7.0-pad12）。
 *
 * 为什么不用 ffmpeg：targetSdk 34 起 App 不许 exec 私有目录里的可执行文件，
 * 而把 ffmpeg 打进 APK 要多 20 MB。jump3r 是源码内嵌（app/src/main/java/de/sciss/jump3r），
 * 只多约 350 KB 的 dex。
 *
 * **这个文件不 import 任何 android.*，所以能在普通 JVM 单测里真跑编码**
 * （见 LameMp3EncoderTest：内存造 2 秒 440 Hz 正弦，断言输出是合法 MP3）。
 *
 * 接线顺序不是随便写的：LAME 的 C 版是全局函数互相调用，移植成对象之后必须
 * 按原 nInitParams 的顺序把模块两两挂上，漏一条就会在某次量化里 NPE。
 * 顺序来自 jump3r 自带的 LameEncoder（那个文件依赖 javax.sound，没有内嵌）。
 */
internal class LameMp3Encoder(
    channels: Int,
    inRate: Int,
    private val out: OutputStream,
    bitrate: Int = Mp3Plan.BITRATE,
    outRate: Int = Mp3Plan.OUT_RATE
) {

    private val lame = Lame()
    private val gfp: LameGlobalFlags
    private var closed = false

    /** 已经写进 [out] 的字节数（只算 MP3 帧，不含任何标签）。 */
    var bytesWritten: Long = 0L
        private set

    /** 送进去的 PCM 帧数（一个立体声采样对算一帧），用来算平均码率。 */
    var framesWritten: Long = 0L
        private set

    private val left = IntArray(CHUNK)
    private val right = IntArray(CHUNK)
    private val buffer = ByteArray(OUT_BUFFER)

    init {
        require(channels == 1 || channels == 2) { "channels must be 1 or 2, was " + channels }
        require(inRate > 0) { "inRate must be positive, was " + inRate }
        require(outRate > 0) { "outRate must be positive, was " + outRate }

        val gaud = GetAudio()
        val ga = GainAnalysis()
        val bs = BitStream()
        val p = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val parse = Parse()
        // BRHist 只服务 --brhist 那个命令行统计，这里不接线也要 new：它参与
        // 编译期的类型检查，留着比删掉省事。
        BRHist()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()

        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        p.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        gaud.setModules(parse, mpg)
        parse.setModules(ver, id3, p)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)

        val flags = lame.lame_init() ?: throw IllegalStateException("jump3r: lame_init 失败")
        flags.num_channels = channels
        flags.in_samplerate = inRate
        flags.out_samplerate = outRate
        // 单声道源保持单声道：硬升成立体声只会白占码率。lame_init_params 内部
        // 还会再按 num_channels == 1 强制一次 MONO，这里写清楚是为了读代码的人。
        flags.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.STEREO
        flags.brate = bitrate
        flags.quality = 5
        id3.id3tag_init(flags)
        // 自动 ID3 在移植版里不可靠（实测既不出 ID3v2 头也不出尾部 TAG），
        // 与其写一个半截标签，不如一个都不写：播放器认帧同步就能放。
        flags.write_id3tag_automatic = false
        val rc = lame.lame_init_params(flags)
        if (rc != 0) throw IllegalStateException("jump3r: lame_init_params 失败 rc=" + rc)
        gfp = flags
    }

    /**
     * 送一段交错的 PCM 进去。[samples] 的排布是 L,R,L,R…（单声道就 L,L,L…），
     * 量级是 ±32768（MediaCodec 解出来的 16-bit 就是这个量级）。
     *
     * 注意这里收的是 **16-bit 量级**，而 [toLameInt] 会把它抬到
     * `lame_encode_buffer_int` 真正期望的量级——两者差 16 位，见那个函数的注释。
     *
     * [frames] 是采样帧数，不是 IntArray 的长度。
     */
    fun writeInterleaved(samples: IntArray, frames: Int) {
        check(!closed) { "encoder already finished" }
        require(frames >= 0) { "frames must not be negative" }
        val needed = if (gfp.num_channels == 1) frames else frames * 2
        require(samples.size >= needed) {
            "need " + needed + " ints for " + frames + " frames, got " + samples.size
        }

        var done = 0
        while (done < frames) {
            val take = minOf(CHUNK, frames - done)
            for (i in 0 until take) {
                if (gfp.num_channels == 1) {
                    left[i] = toLameInt(samples[done + i])
                    right[i] = toLameInt(samples[done + i])
                } else {
                    left[i] = toLameInt(samples[(done + i) * 2])
                    right[i] = toLameInt(samples[(done + i) * 2 + 1])
                }
            }
            val got = lame.lame_encode_buffer_int(gfp, left, right, take, buffer, 0, buffer.size)
            if (got < 0) throw IllegalStateException("jump3r: 编码失败 rc=" + got)
            if (got > 0) {
                out.write(buffer, 0, got)
                bytesWritten += got.toLong()
            }
            framesWritten += take.toLong()
            done += take
        }
    }

    /**
     * 16-bit 样本 → `lame_encode_buffer_int` 期望的 ±2^31 量级。
     *
     * 为什么必须抬 16 位：那个入口的注释写着「internal code expects +/- 32768.0」，
     * 而它自己在这一步做的是 `buffer_l[i] * (1.0f / (1L << 16))`
     * （`de/sciss/jump3r/mp3/Lame.java:1935-1936`）。也就是说**它收 ±2^31，
     * 除以 65536 之后才落到 LAME 内部的 ±32768**。把 16-bit 值原样递进去，
     * 进 LAME 的只剩 ±0.5，编出来是 -90 dB 的静音——文件能播、没声音。
     *
     * v0.7.0-pad12 就是漏了这一步（真机报障：mean_volume -91.0 dB /
     * max_volume -90.3 dB）。改这里之前先看 [LameMp3EncoderTest] 里
     * `theSampleScaleMatchesWhatLameActuallyExpects`，那条用例会把量级钉死。
     *
     * 边界：`-32768 shl 16` 正好是 `Int.MIN_VALUE`，LAME 乘 1/65536 得
     * `-32768.0f`，落在满量程负端，不需要特判。
     */
    internal fun toLameInt(sample16: Int): Int = sample16 shl 16

    /**
     * 冲掉编码器里剩下的帧并关掉。返回总字节数。
     *
     * 调用两次是安全的（第二次直接返回），因为界面上「开始」和「退出」两条
     * 路径都可能走到这里。
     */
    fun finish(): Long {
        if (closed) return bytesWritten
        closed = true
        try {
            val tail = lame.lame_encode_flush(gfp, buffer, 0, buffer.size)
            if (tail < 0) throw IllegalStateException("jump3r: flush 失败 rc=" + tail)
            if (tail > 0) {
                out.write(buffer, 0, tail)
                bytesWritten += tail.toLong()
            }
            out.flush()
        } finally {
            lame.lame_close(gfp)
        }
        return bytesWritten
    }

    private companion object {
        /** LAME 的帧长：1152 个采样一帧，和 C 版 frontend 一致。 */
        const val CHUNK = 1152

        /** 一帧 MP3 最多 1441 字节（320 kbps），8192 是 C 版 frontend 的老数字。 */
        const val OUT_BUFFER = 8192
    }
}
