package net.de5.phoneagent

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * speak -- say TEXT out loud in a Fish voice synthesized on the VPS
 * (v0.10.0-pad12, SPEC.md §3.30).
 *
 * Why not just use say: the system engine on this phone is a dry, robotic
 * voice, and the operator talks to this phone in Chinese all day. The VPS
 * already runs a Fish TTS wrapper, so the audio is one HTTP call away.
 * Everything that can fail (no config, no network, a 502, a decode error, a
 * stuck player) falls back to [SayOps.speak], because "the phone said nothing"
 * is the one outcome that makes the command useless.
 *
 * Three decisions that are not obvious:
 *
 *  - Chunks are 120 characters, not the server's 500. Synthesis costs about
 *    0.10 s per character, so a 500-character request is 62 s of silence before
 *    the first sound, and the server hard-refuses 501 with a 413. The FIRST
 *    chunk is capped at 40 characters for the same reason: 40 characters is
 *    about 4 s to first sound instead of 13 s.
 *  - Playback is a pipeline, not a batch, and since v0.11.3 it is a *parallel*
 *    one. Three threads synthesize and write chunks into cacheDir while this
 *    thread plays them with MediaPlayer and polls isPlaying; [SynthBoard] hands
 *    them over in index order and bounds the look-ahead (4 chunks). One
 *    synthesizer could not keep up -- 0.10 s per character is slower than the
 *    4-5 characters a second a listener reads -- so every sentence boundary
 *    used to be a several-second silence.
 *  - A failure after the first chunk does NOT fall back. Re-reading the whole
 *    text with the system voice would make the listener hear the beginning
 *    twice; the receipt says how many chunks were played instead.
 *
 * No third-party dependency and no client class: HttpURLConnection is enough
 * for one POST, and [FeatureContext] deliberately has no network capability.
 */
internal object FeatSpeak : FeatureModule {

    override val verb: String = "speak"

    override fun parse(rest: String): Parsed? {
        val request = SpeakPlan.parse(rest) ?: return Parsed.Bad(SpeakPlan.USAGE)
        return Parsed.Ok(SpeakPlan.normalize(request))
    }

    override fun execute(ctx: FeatureContext, args: String): ExecutionResult {
        val request = SpeakPlan.parse(args) ?: return ExecutionResult(EXIT_USAGE, "", SpeakPlan.USAGE)

        // One limit for both paths: the system voice has its own cap, and a
        // pasted essay would otherwise keep the poll loop busy for minutes.
        val text = SayPlan.clipText(request.text)
        val base = SpeakPlan.endpoint(BuildConfig.DEFAULT_TTS_BASE)
        val secret = BuildConfig.DEFAULT_TTS_SECRET.trim()
        val voice = SpeakPlan.voiceOrDefault(request.voice, BuildConfig.DEFAULT_TTS_VOICE)
        val chunks = SpeakPlan.chunks(text)

        // A clone of this repository builds with empty BuildConfig values: say
        // so in the receipt instead of dialling a server nobody configured.
        if (base.isEmpty() || secret.isEmpty() || voice.isEmpty() || chunks.isEmpty()) {
            return fallback(ctx, text, SpeakPlan.NOT_CONFIGURED)
        }
        return fish(ctx, base, secret, voice, request.speed, text, chunks)
    }

    /**
     * Synthesize and play [chunks] -- in one request when there is only one.
     *
     * A short line ([SpeakPlan.SINGLE_SHOT_MAX] characters or less) never starts
     * a [SynthBoard]: three worker threads for one chunk would be three threads
     * that find nothing to do, so it is synthesized and played right here. Longer
     * text goes through the pipeline: [SynthBoard] runs [SpeakPlan.SYNTH_WORKERS]
     * synthesizers and hands chunks to this thread in index order, never more
     * than [SpeakPlan.QUEUE_CAPACITY] ahead, so a long text neither buffers a
     * hundred MP3s nor plays them out of order. Every wait is bounded: a wedged
     * chunk comes back as a stall, not as a hung command.
     */
    private fun fish(
        ctx: FeatureContext,
        base: String,
        secret: String,
        voice: String,
        speed: Float,
        text: String,
        chunks: List<String>
    ): ExecutionResult {
        val cache = ctx.context.cacheDir
        if (chunks.size == 1) return oneShot(ctx, cache, base, secret, voice, speed, text, chunks[0])
        val board = SynthBoard(
            total = chunks.size,
            workers = SpeakPlan.SYNTH_WORKERS,
            capacity = SpeakPlan.QUEUE_CAPACITY,
            threadName = "speak-synth",
            task = { index -> synthesize(cache, base, secret, voice, speed, chunks[index], index) },
            stopsHere = { it is Synth.Failed }
        )
        val started = System.currentTimeMillis()
        board.start()

        var played = 0
        var bytes = 0L
        var firstSoundMs = -1L
        try {
            while (true) {
                val item = board.take(SpeakPlan.SYNTH_WAIT_MS) ?: break
                when (item) {
                    is SynthDelivery.Stalled ->
                        return incomplete(ctx, text, played, chunks.size, item.index, SpeakPlan.synthTimeout(item.waitedMs))
                    is SynthDelivery.Crashed ->
                        return incomplete(ctx, text, played, chunks.size, item.index, SpeakPlan.synthFailure(item.error))
                    is SynthDelivery.Value -> when (val synth = item.value) {
                        is Synth.Failed -> return incomplete(ctx, text, played, chunks.size, synth.index, synth.reason)
                        is Synth.Audio -> {
                            val out = play(synth.file.absolutePath)
                            synth.file.delete()
                            if (out.error != null) {
                                return incomplete(ctx, text, played, chunks.size, synth.index, out.error)
                            }
                            if (firstSoundMs < 0) firstSoundMs = out.startedAt - started
                            played++
                            bytes += synth.bytes
                        }
                    }
                }
            }
        } catch (t: InterruptedException) {
            // Nothing interrupts this thread today; report it as a stopped run.
            return incomplete(ctx, text, played, chunks.size, played, SpeakPlan.interrupted())
        } finally {
            board.stop()
        }

        val totalMs = System.currentTimeMillis() - started
        val line = SpeakPlan.successLine(text.length, voice, speed, chunks.size, firstSoundMs, totalMs, bytes)
        AgentLog.add("speak", line)
        return ExecutionResult(EXIT_OK, line, "")
    }

    /**
     * The single-request path: synthesize [chunk], play it, report one line.
     *
     * Same receipts as the pipeline -- a failure before any sound falls back to
     * the system voice, and the success line still says "1 块" -- only without
     * the queue, because there is nothing to order and nothing to look ahead to.
     */
    private fun oneShot(
        ctx: FeatureContext,
        cache: File,
        base: String,
        secret: String,
        voice: String,
        speed: Float,
        text: String,
        chunk: String
    ): ExecutionResult {
        val started = System.currentTimeMillis()
        val synth = synthesize(cache, base, secret, voice, speed, chunk, 0)
        if (synth is Synth.Failed) {
            return incomplete(ctx, text, 0, 1, synth.index, synth.reason)
        }
        val audio = synth as Synth.Audio
        val out = play(audio.file.absolutePath)
        audio.file.delete()
        if (out.error != null) return incomplete(ctx, text, 0, 1, audio.index, out.error)
        val line = SpeakPlan.successLine(
            text.length,
            voice,
            speed,
            1,
            out.startedAt - started,
            System.currentTimeMillis() - started,
            audio.bytes
        )
        AgentLog.add("speak", line)
        return ExecutionResult(EXIT_OK, line, "")
    }

    /**
     * Synthesizer side, one chunk: POST it, write it to the cache, hand it back.
     *
     * Runs on a [SynthBoard] worker, so it is called once per index and may run
     * at the same time as two other chunks. Every expected failure is a value,
     * never a throw: the board would otherwise report a crash where the operator
     * can read a sentence.
     */
    private fun synthesize(
        cache: File,
        base: String,
        secret: String,
        voice: String,
        speed: Float,
        chunk: String,
        index: Int
    ): Synth {
        val fetched = fetch(base, secret, voice, speed, chunk)
        if (fetched is Fetched.Failed) return Synth.Failed(index, fetched.reason)
        val audio = fetched as Fetched.Audio
        val file = File(cache, SpeakPlan.chunkFileName(index))
        return try {
            file.writeBytes(audio.bytes)
            Synth.Audio(index, file, audio.bytes.size.toLong())
        } catch (t: Throwable) {
            Synth.Failed(index, SpeakPlan.writeFailure(t))
        }
    }

    /** One POST. Every failure comes back as a sentence for the receipt. */
    private fun fetch(base: String, secret: String, voice: String, speed: Float, chunk: String): Fetched {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(base + "/tts/")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = SpeakPlan.CONNECT_TIMEOUT_MS
                readTimeout = SpeakPlan.READ_TIMEOUT_MS
                doOutput = true
                useCaches = false
                setRequestProperty("X-TTS-Secret", secret)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "audio/mpeg")
            }
            val body = SpeakPlan.requestBody(chunk, voice, speed).toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(body) }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return Fetched.Failed(SpeakPlan.httpFailure(code, readError(connection)))
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return Fetched.Failed(SpeakPlan.EMPTY_AUDIO)
            return Fetched.Audio(bytes)
        } catch (t: Throwable) {
            return Fetched.Failed(SpeakPlan.networkFailure(t))
        } finally {
            try {
                connection?.disconnect()
            } catch (t: Throwable) {
                // A connection that cannot even be disconnected is already dead.
            }
        }
    }

    /**
     * The error body is a JSON object with error and detail. It is only ever
     * used as a hint in a receipt, so a plain text read is enough -- no JSON
     * parser, no dependency.
     */
    private fun readError(connection: HttpURLConnection): String {
        return try {
            val stream = connection.errorStream ?: return ""
            val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            SpeakPlan.errorHint(text)
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * The usage every `speak` playback asks for, exposed so a plain JVM test can
     * pin it (see [speakAudioAttributes] for why it is not USAGE_ASSISTANT).
     *
     * It is a compile-time int on purpose: the test inlines it, so no
     * `android.media` class is loaded and no system service is mocked.
     */
    internal const val SPEAK_USAGE: Int = AudioAttributes.USAGE_MEDIA

    /**
     * What the player asks Android to route us to (v0.11.1-pad12).
     *
     * USAGE_MEDIA, deliberately, and **not** USAGE_ASSISTANT. Measured on the
     * operator's MEIZU 22 (Android 16) with `dumpsys audio` while v0.11.0
     * was installed:
     *
     *  - USAGE_ASSISTANT routes to STREAM_ASSISTANT: max **15**, sitting at
     *    **11**, so the utterance left the speaker at 73% no matter what the
     *    media stream said. STREAM_ASSISTANT is also where the phone's own
     *    voice assistant lives -- pushing it to 15 would change the owner's
     *    assistant instead of our playback.
     *  - USAGE_MEDIA routes to STREAM_MUSIC: max **60**, speaker at **60** --
     *    the stream the owner himself adjusts with the volume keys, and the
     *    stream the system TTS engine plays on too (`dumpsys audio` prints
     *    "STREAM_TTS (aliased to: STREAM_MUSIC)"), so `speak` and `say`
     *    are judged at the same volume.
     *
     * The rule this encodes: **play on the stream the owner already controls.**
     * v0.11.0 asked for USAGE_ASSISTANT and separately pushed STREAM_MUSIC to
     * its maximum (dumpsys: oldIndex 28 -> index 60); the owner still heard a
     * quiet voice, because the push landed on a stream nothing played to.
     *
     * Since v0.11.2-pad12 `speak` reads and writes **no** volume at all: the
     * owner sets it himself, and the app does not move the phone's sliders
     * behind his back. The usage below is the whole story -- same stream as the
     * system voice, at whatever volume that stream is on.
     *
     * CONTENT_TYPE_SPEECH stays: it tells the system this is speech rather than
     * music, which is what ducking and the volume panel read. No flags are set
     * here, same as before.
     */
    private fun speakAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(SPEAK_USAGE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Play one file to its end.
     *
     * prepare() is synchronous on purpose: this runs on the poll thread, and an
     * asynchronous prepare would need a completion listener, which needs a
     * Looper this thread does not have. Polling isPlaying is the same reason.
     *
     * @return [PlayOut] with a null error when the file played to its end.
     */
    private fun play(path: String): PlayOut {
        val player = MediaPlayer()
        var startedAt = 0L
        try {
            player.setAudioAttributes(speakAudioAttributes())
            player.setDataSource(path)
            player.prepare()
            val duration = try {
                player.duration.toLong()
            } catch (t: Throwable) {
                -1L
            }
            val cap = SpeakPlan.playbackCapMs(duration)
            player.start()
            startedAt = System.currentTimeMillis()
            val settleUntil = startedAt + SETTLE_MS
            val deadline = startedAt + cap
            while (System.currentTimeMillis() < deadline) {
                // Right after start() a player may briefly answer false; do not
                // read that as "finished" or every chunk would be cut short.
                if (System.currentTimeMillis() >= settleUntil && !player.isPlaying) {
                    return PlayOut(null, startedAt)
                }
                Thread.sleep(POLL_MS)
            }
            return PlayOut(SpeakPlan.playTimeout(cap), startedAt)
        } catch (t: Throwable) {
            return PlayOut(SpeakPlan.playFailure(t), startedAt)
        } finally {
            try {
                player.stop()
            } catch (t: Throwable) {
                // Already stopped or never started; release below is what matters.
            }
            try {
                player.release()
            } catch (t: Throwable) {
                // Nothing left to release.
            }
        }
    }

    /**
     * Something went wrong at chunk [index].
     *
     * With nothing played yet the whole text falls back to the system voice;
     * with something already heard, re-reading from the top would be worse than
     * stopping, so the receipt says how far it got.
     */
    private fun incomplete(
        ctx: FeatureContext,
        text: String,
        played: Int,
        total: Int,
        index: Int,
        reason: String
    ): ExecutionResult {
        if (played == 0) return fallback(ctx, text, reason)
        val line = SpeakPlan.partialLine(played, total, index + 1, reason)
        AgentLog.add("speak", line)
        return ExecutionResult(EXIT_PERMISSION, "", line)
    }

    /** The system voice, with the reason the Fish path was abandoned. */
    private fun fallback(ctx: FeatureContext, text: String, reason: String): ExecutionResult {
        val outcome = SayOps.speak(
            ctx.context,
            text,
            SayPlan.RATE_DEFAULT,
            SayPlan.PITCH_DEFAULT,
            SayPlan.LANG_DEFAULT
        )
        if (outcome.startsWith(Admin.ERROR_PREFIX)) {
            val line = Admin.ERROR_PREFIX + "speak 回落系统 TTS 也失败：" + outcome.removePrefix(Admin.ERROR_PREFIX)
            AgentLog.add("speak", line)
            return ExecutionResult(EXIT_PERMISSION, "", line)
        }
        val line = SpeakPlan.fallbackLine(text.length, reason)
        AgentLog.add("speak", line)
        return ExecutionResult(EXIT_OK, line, "")
    }

    /**
     * What one synthesizer hands to the player.
     *
     * There is no `Done` any more (v0.11.3-pad12): [SynthBoard] answers null
     * when the run is over, so "finished" is not something anyone can play.
     */
    private sealed class Synth {
        class Audio(val index: Int, val file: File, val bytes: Long) : Synth()
        class Failed(val index: Int, val reason: String) : Synth()
    }

    /** One HTTP result. */
    private sealed class Fetched {
        class Audio(val bytes: ByteArray) : Fetched()
        class Failed(val reason: String) : Fetched()
    }

    /** One playback result; [startedAt] is wall-clock at start(). */
    private class PlayOut(val error: String?, val startedAt: Long)

    /** How long isPlaying must have had a chance to become true. */
    private const val SETTLE_MS = 300L

    /** 50 ms: nothing is gained by asking more often. */
    private const val POLL_MS = 50L
}

/**
 * Pure logic for [FeatSpeak] (v0.10.0-pad12, SPEC.md §3.30).
 *
 * Grammar, the chunker, the JSON body and every sentence the operator can read
 * live here, so a plain JVM test can pin them without a phone, a network or a
 * MediaPlayer.
 */
internal object SpeakPlan {

    const val USAGE = "usage: speak [voice=ID] [speed=0.5-2.0] TEXT"

    const val SPEED_DEFAULT = 1.0f
    const val SPEED_MIN = 0.5f
    const val SPEED_MAX = 2.0f

    /**
     * 120, not the server's 500: synthesis is linear at about 0.10 s per
     * character, so 500 characters is 62 s of silence before the first sound.
     */
    const val CHUNK_MAX = 120

    /**
     * The first chunk is shorter than the rest. It is the only one the listener
     * waits for with nothing to listen to, so it is sized for time-to-first-
     * sound (about 4 s), not for throughput.
     */
    const val FIRST_MAX = 40

    /**
     * Text at or below this length is synthesized in **one** request (v0.11.3-pad12).
     *
     * Chunking a 44-character line into 40 + 4 buys nothing: synthesis is about
     * 0.10 s per character, so one request for 80 characters is ~8 s before the
     * first sound, while cutting it in two only moves that to ~4 s and costs an
     * extra round trip (1-2 s of server overhead) plus an audible join -- and a
     * 4-character tail synthesized on its own does not even carry the same
     * intonation as the sentence it was cut from. The owner said he can wait
     * (「这个时间也可以长一点也没有关系」), so up to 80 characters go out whole.
     * 80 is the balance point: beyond it the first sound would be past 10 s.
     */
    const val SINGLE_SHOT_MAX = 80

    /** The server answers 413 above this. The client never sends it. */
    const val SERVER_MAX = 500

    const val CONNECT_TIMEOUT_MS = 10_000

    /** 500 characters take 62 s at worst; 90 s leaves room for a slow VPS. */
    const val READ_TIMEOUT_MS = 90_000

    /**
     * How many chunks are synthesized at the same time (v0.11.3-pad12).
     *
     * One was not enough: synthesis is ~0.10 s per character and the player
     * reads 4-5 characters a second, so a 120-character chunk needed ~12 s while
     * the 40-character chunk in front of it played for ~10 s -- every sentence
     * boundary was a several-second silence. Three requests at once is what the
     * VPS already serves without queueing.
     */
    const val SYNTH_WORKERS = 3

    /**
     * How many finished chunks may wait for the player (4, was 2).
     *
     * With three synthesizers, two slots would leave the workers waiting for the
     * player instead of the other way round; four still means a 30-chunk
     * paragraph never buffers 30 MP3s.
     */
    const val QUEUE_CAPACITY = 4

    /**
     * How long the player waits for one chunk before calling its synthesizer
     * wedged (110 s).
     *
     * The upstream service is not reliable per request: ten serial syntheses
     * measured 2.3-2.9 s each **except the seventh, which took 80.8 s**. A
     * request slower than the socket timeouts has already come back as a
     * failure, so this only catches a worker that never returned at all -- and
     * it must be finite, or one wedged connection would hang `speak` forever.
     */
    const val SYNTH_WAIT_MS = CONNECT_TIMEOUT_MS + READ_TIMEOUT_MS + 10_000L

    /** Extra time on top of the decoded duration before the player is called stuck. */
    const val PLAY_SLACK_MS = 5_000L

    /** Used when the player will not report a duration (a broken or empty MP3). */
    const val PLAY_FALLBACK_MS = 120_000L

    const val NOT_CONFIGURED = "未配置 TTS 服务"

    const val EMPTY_AUDIO = "服务端返回空音频"

    /** Sentence ends: a chunk boundary is cheap here because a pause is natural. */
    private const val SENTENCE_ENDS = "。！？；…\n"

    /** Clause ends: used when one sentence is longer than the chunk limit. */
    private const val CLAUSE_ENDS = "，、：,:;"

    /** One request: what to say, in which voice, how fast. */
    class Request(val voice: String, val speed: Float, val text: String)

    /**
     * speak [voice=ID] [speed=1.0] TEXT
     *
     * Only *leading* switches are consumed (the same rule as say), so a
     * sentence that merely contains speed=2 keeps it, and the payload is the
     * rest of the line verbatim, inner spaces included.
     *
     * @return null when the line is not usable; the caller answers with [USAGE].
     */
    fun parse(rest: String): Request? {
        val tokens = tokens(rest)
        if (tokens.isEmpty()) return null
        var index = 0
        var voice = ""
        var speed = SPEED_DEFAULT
        while (index < tokens.size) {
            val head = tokens[index].text
            val lower = head.lowercase()
            if (lower.startsWith("voice=")) {
                val value = head.substring(6).trim()
                if (value.isEmpty()) return null
                voice = value
                index++
                continue
            }
            if (lower.startsWith("speed=")) {
                val value = head.substring(6).toFloatOrNull() ?: return null
                speed = clampSpeed(value)
                index++
                continue
            }
            break
        }
        if (index >= tokens.size) return null
        val text = rest.substring(tokens[index].start)
        if (text.isBlank()) return null
        return Request(voice, speed, text)
    }

    /** The normalised argument string carried by [Parsed.Ok]. */
    fun normalize(request: Request): String {
        val head = StringBuilder()
        if (request.voice.isNotEmpty()) head.append("voice=").append(request.voice).append(' ')
        if (request.speed != SPEED_DEFAULT) {
            head.append("speed=").append(speedText(request.speed)).append(' ')
        }
        return head.append(request.text).toString()
    }

    fun clampSpeed(value: Float): Float = value.coerceIn(SPEED_MIN, SPEED_MAX)

    /** Two decimals: the receipt has to show 1.00, not 1.0. */
    fun speedText(speed: Float): String = String.format(Locale.US, "%.2f", clampSpeed(speed))

    fun voiceOrDefault(given: String, baked: String): String =
        if (given.isNotBlank()) given.trim() else baked.trim()

    /** A base url with or without a trailing slash must both work. */
    fun endpoint(base: String): String = base.trim().trimEnd('/')

    fun chunkFileName(index: Int): String = "tts-" + index + ".mp3"

    /**
     * Split [text] into pieces: nothing at all for blank text, **one piece** at or
     * below [SINGLE_SHOT_MAX], otherwise the first at most [FIRST_MAX] characters
     * and every later one at most [CHUNK_MAX].
     *
     * Order of preference at each cut: a sentence end, then a clause end, then a
     * hard cut. Small sentences are packed back together, so a 200-character
     * text with one full stop is not five chunks -- every extra chunk is another
     * HTTP round trip and another audible join.
     */
    fun chunks(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        if (text.length <= SINGLE_SHOT_MAX) return listOf(text)
        val first = firstChunk(text)
        if (first.length >= text.length) return listOf(text)
        val out = ArrayList<String>()
        out.add(first)
        out.addAll(pack(cutRest(text.substring(first.length))))
        return out
    }

    /** The opening chunk: the longest natural break at or below [FIRST_MAX]. */
    private fun firstChunk(text: String): String {
        if (text.length <= FIRST_MAX) return text
        val cut = naturalCut(text, FIRST_MAX)
        return text.substring(0, cut)
    }

    /** Everything after the first chunk: sentences, then clauses, then a hard cut. */
    private fun cutRest(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val pieces = ArrayList<String>()
        for (sentence in sentences(text)) {
            if (sentence.length <= CHUNK_MAX) {
                pieces.add(sentence)
            } else {
                pieces.addAll(cutLong(sentence))
            }
        }
        return pieces
    }

    /**
     * The cut position at or below [limit]: after the last sentence end, else
     * after the last clause end, else a hard cut that never splits a pair.
     */
    private fun naturalCut(text: String, limit: Int): Int {
        val bound = minOf(limit, text.length)
        for (i in bound downTo 1) {
            if (SENTENCE_ENDS.indexOf(text[i - 1]) >= 0) return i
        }
        for (i in bound downTo 1) {
            if (CLAUSE_ENDS.indexOf(text[i - 1]) >= 0) return i
        }
        return safeCut(text, bound)
    }

    private fun sentences(text: String): List<String> {
        val out = ArrayList<String>()
        val buffer = StringBuilder()
        for (ch in text) {
            buffer.append(ch)
            if (SENTENCE_ENDS.indexOf(ch) >= 0) {
                out.add(buffer.toString())
                buffer.setLength(0)
            }
        }
        if (buffer.isNotEmpty()) out.add(buffer.toString())
        return out
    }

    /** One sentence longer than the limit: cut at a clause end, else hard. */
    private fun cutLong(sentence: String): List<String> {
        val out = ArrayList<String>()
        var rest = sentence
        while (rest.length > CHUNK_MAX) {
            val cut = naturalCut(rest, CHUNK_MAX)
            out.add(rest.substring(0, cut))
            rest = rest.substring(cut)
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    /** Never cut a surrogate pair in half: half a character is not a character. */
    private fun safeCut(text: String, limit: Int): Int {
        if (limit <= 0 || limit >= text.length) return limit
        if (Character.isLowSurrogate(text[limit]) && Character.isHighSurrogate(text[limit - 1])) {
            return limit - 1
        }
        return limit
    }

    /** Greedily merge adjacent pieces while they still fit. */
    private fun pack(pieces: List<String>): List<String> {
        val out = ArrayList<String>()
        val buffer = StringBuilder()
        for (piece in pieces) {
            if (buffer.isNotEmpty() && buffer.length + piece.length > CHUNK_MAX) {
                out.add(buffer.toString())
                buffer.setLength(0)
            }
            buffer.append(piece)
        }
        if (buffer.isNotEmpty()) out.add(buffer.toString())
        return out
    }

    /** The POST body, hand-written so the field order is fixed and testable. */
    fun requestBody(text: String, voice: String, speed: Float): String =
        "{\"text\":\"" + escape(text) + "\",\"voice\":\"" + escape(voice) +
            "\",\"speed\":" + speedText(speed) + "}"

    private fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else ->
                    if (ch < ' ') {
                        out.append(String.format(Locale.US, "\\u%04x", ch.code))
                    } else {
                        out.append(ch)
                    }
            }
        }
        return out.toString()
    }

    /** 服务端 502 / 服务端 401（invalid secret）. */
    fun httpFailure(code: Int, detail: String): String {
        val clean = detail.trim()
        return if (clean.isEmpty()) "服务端 " + code else "服务端 " + code + "（" + clean + "）"
    }

    /** The server answers JSON; its message is a hint, never a parser input. */
    fun errorHint(body: String): String {
        val text = body.trim()
        if (text.isEmpty()) return ""
        val error = field(text, "error")
        val detail = field(text, "detail")
        val joined = listOf(error, detail).filter { it.isNotEmpty() }.joinToString(": ")
        val hint = if (joined.isNotEmpty()) joined else text
        return if (hint.length <= 80) hint else hint.substring(0, 80) + "…"
    }

    private fun field(json: String, name: String): String {
        val marker = "\"" + name + "\""
        val at = json.indexOf(marker)
        if (at < 0) return ""
        val colon = json.indexOf(':', at + marker.length)
        if (colon < 0) return ""
        val open = json.indexOf('"', colon + 1)
        if (open < 0) return ""
        val close = json.indexOf('"', open + 1)
        if (close < 0) return ""
        return json.substring(open + 1, close).trim()
    }

    fun networkFailure(t: Throwable): String = "连不上 TTS 服务：" + reason(t)

    fun writeFailure(t: Throwable): String = "写缓存失败：" + reason(t)

    fun playFailure(t: Throwable): String = "播放失败：" + reason(t)

    fun interrupted(): String = "播放被中断"

    private fun reason(t: Throwable): String =
        t.message?.trim().takeUnless { it.isNullOrEmpty() } ?: t.javaClass.simpleName

    fun playTimeout(capMs: Long): String = "播放超时（" + seconds(capMs) + "）"

    /** speak 已播 1/3 块，第 2 块失败：合成卡死（已等 110.00s）… */
    fun synthTimeout(waitedMs: Long): String = "合成卡死（已等 " + seconds(waitedMs) + "）"

    /** A synthesizer that threw instead of reporting a reason. */
    fun synthFailure(t: Throwable): String = "合成失败：" + reason(t)

    /** Duration plus slack, or a fixed generous cap when the duration is unknown. */
    fun playbackCapMs(durationMs: Long): Long =
        if (durationMs > 0L) durationMs + PLAY_SLACK_MS else PLAY_FALLBACK_MS

    /** 2.30s: two decimals, so a receipt can be compared byte for byte. */
    fun seconds(ms: Long): String = String.format(Locale.US, "%.2f", ms / 1000.0) + "s"

    /**
     * speak 已朗读 240 字（fish voice=7f92 speed=1.00 3 块 首声 2.40s 总 14.80s 712000B）
     *
     * Bytes are printed exactly rather than in KB: a receipt that rounds is a
     * receipt nobody can compare with the server's own log.
     */
    fun successLine(
        chars: Int,
        voice: String,
        speed: Float,
        chunks: Int,
        firstSoundMs: Long,
        totalMs: Long,
        bytes: Long
    ): String =
        "speak 已朗读 " + chars + " 字（fish voice=" + voice + " speed=" + speedText(speed) +
            " " + chunks + " 块 首声 " + seconds(firstSoundMs) + " 总 " + seconds(totalMs) +
            " " + bytes + "B）"

    /** speak 已朗读 24 字（回落系统 TTS：服务端 502） */
    fun fallbackLine(chars: Int, reason: String): String =
        "speak 已朗读 " + chars + " 字（回落系统 TTS：" + reason + "）"

    /** speak 已播 2/3 块，第 3 块失败：服务端 502，未回落（避免重复朗读） */
    fun partialLine(played: Int, total: Int, chunk: Int, reason: String): String =
        "speak 已播 " + played + "/" + total + " 块，第 " + chunk + " 块失败：" + reason +
            "，未回落（避免重复朗读）"

    /** A whitespace-separated token plus where it starts, for verbatim slicing. */
    private class Token(val text: String, val start: Int)

    private fun tokens(rest: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < rest.length) {
            while (i < rest.length && rest[i].isWhitespace()) i++
            if (i >= rest.length) break
            val start = i
            while (i < rest.length && !rest[i].isWhitespace()) i++
            out.add(Token(rest.substring(start, i), start))
        }
        return out
    }
}
