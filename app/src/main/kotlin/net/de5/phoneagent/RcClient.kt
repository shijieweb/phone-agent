package net.de5.phoneagent

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Who is calling: filled from `android.os.Build` by [RcAgentService]. */
data class AgentInfo(
    val hostname: String,
    val user: String,
    val os: String,
    val ver: String
)

/** One command handed out by `/agent/poll`. */
data class PollResult(
    val cid: String,
    val cmd: String,
    val created: Double,
    val timeout: Int
)

/**
 * Outcome of one blob upload.
 *
 * [bytes] is the number of bytes the relay accepted, or -1 on failure, in which
 * case [error] says why in one short line that is safe to paste into stderr.
 */
data class BlobUpload(val bytes: Int, val error: String?) {
    val ok: Boolean get() = error == null
}

/**
 * Outcome of one `POST /agent/note` (SPEC.md section 3.29).
 *
 * [seq] is the relay's running counter for this agent, kept because a note that
 * was stored but whose ack was lost looks exactly like a note that was never
 * sent — the counter is the only thing that tells the two apart afterwards.
 */
data class NoteResult(val ok: Boolean, val seq: Long, val error: String?)

/**
 * Whether the operator has actually pulled a note down (v0.11.5-pad12, SPEC.md section 3.29).
 *
 * A stored note only proves the phone reached the relay. [read] is the relay's
 * answer to "has anyone looked at it yet", which is the fact the owner actually
 * asked for; [readAt] is the relay clock when it was first pulled (0 while unread).
 *
 * `null` from [RcClient.noteStatus] means *unknown* -- the question could not be
 * asked -- and must never be rendered as "not read yet": one is a fact, the other
 * is a network error.
 */
data class NoteStatus(val read: Boolean, val readAt: Long)

/**
 * The three relay calls of SPEC.md section 2, on `java.net.HttpURLConnection`.
 *
 * Every call opens a fresh connection, so this class is safe to use from the
 * poll thread and the heartbeat thread at the same time. Network problems are
 * surfaced as exceptions; the retry policy lives in [RcAgentService].
 */
class RcClient(
    private val baseUrl: String,
    private val secret: String,
    private val agentId: String,
    private val info: AgentInfo
) {

    /** Registration + heartbeat (`last_seen`). */
    fun hello(env: String): Boolean {
        val body = JSONObject()
        body.put("id", agentId)
        body.put("hostname", info.hostname)
        body.put("user", info.user)
        body.put("os", info.os)
        body.put("ver", info.ver)
        body.put("ip", "")
        body.put("env", env)
        val response = post("/agent/hello", body, HELLO_READ_TIMEOUT_MS) ?: return false
        return response.optBoolean("ok", false)
    }

    /**
     * Long poll. The server clamps `wait` to 25 s, so the read timeout has to
     * be comfortably larger than that.
     *
     * @return null when nothing was queued within `wait` — that is normal.
     */
    fun poll(waitSeconds: Int): PollResult? {
        val body = JSONObject()
        body.put("id", agentId)
        body.put("wait", waitSeconds)
        val response = post("/agent/poll", body, POLL_READ_TIMEOUT_MS) ?: return null

        val cmd = response.optJSONObject("cmd") ?: return null
        val line = cmd.optString("cmd", "")
        if (line.isEmpty()) return null
        return PollResult(
            cid = cmd.optString("cid", ""),
            cmd = line,
            created = cmd.optDouble("created", 0.0),
            timeout = cmd.optInt("timeout", DEFAULT_TIMEOUT)
        )
    }

    /** Report one result. Always send one, even on failure. */
    fun result(
        cid: String,
        cmd: String,
        exit: Int,
        dur: Double,
        stdout: String,
        stderr: String
    ): Boolean {
        val body = JSONObject()
        body.put("id", agentId)
        body.put("cid", cid)
        body.put("cmd", cmd)
        body.put("exit", exit)
        body.put("dur", dur)
        body.put("user", info.user)
        body.put("stdout", stdout)
        body.put("stderr", stderr)
        val response = post("/agent/result", body, HELLO_READ_TIMEOUT_MS) ?: return false
        return response.optBoolean("ok", false)
    }

    /**
     * `POST /agent/note` (SPEC.md section 3.29): the one-line report that goes
     * with a blob, saying what the picture is and who asked for it.
     *
     * The body is built by [CamPlan.noteBody] and handed in as text, so the six
     * field names are pinned by a unit test instead of by a JSONObject call that
     * only the relay would notice going wrong.
     *
     * Unlike [post] this never throws: the caller is a background thread in
     * [CameraActivity] whose only job left is to put one sentence on screen.
     */
    fun postNote(json: String): NoteResult {
        return try {
            val response = post("/agent/note", JSONObject(json), HELLO_READ_TIMEOUT_MS)
            if (response == null) {
                NoteResult(false, -1L, "empty answer")
            } else if (response.optBoolean("ok", false)) {
                NoteResult(true, response.optLong("seq", -1L), null)
            } else {
                NoteResult(false, -1L, response.optString("error", "relay said no"))
            }
        } catch (t: Throwable) {
            NoteResult(false, -1L, describe(t))
        }
    }

    /**
     * `POST /agent/note-status` (v0.11.5-pad12): has the operator read note [seq]?
     *
     * The relay answers `{"ok":true,"seq":N,"read":true|false,"read_at":N}`, or
     * 404 `{"ok":false,"error":"no such note"}` for a seq it never stored. Both
     * the 404 and any transport problem come back as `null`, because the caller
     * ([CameraActivity]'s ack thread) only has two useful things to say: "read" and
     * "nobody has looked yet". A failed question is neither, and is retried.
     *
     * Never throws: the caller is a background thread whose remaining job is one
     * sentence on screen, exactly like [postNote].
     */
    fun noteStatus(seq: Long): NoteStatus? {
        if (seq < 0L) return null
        val body = JSONObject()
        body.put("id", agentId)
        body.put("seq", seq)
        return try {
            val response = post("/agent/note-status", body, HELLO_READ_TIMEOUT_MS) ?: return null
            if (!response.optBoolean("ok", false)) return null
            NoteStatus(response.optBoolean("read", false), response.optLong("read_at", 0L))
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Push raw bytes to `/agent/blob/<name>` (SPEC.md section 2).
     *
     * The body is the file itself — no base64, no JSON — so a full-size
     * screenshot crosses the relay without ever touching the 200 KB stdout
     * budget. Every failure is swallowed into a [BlobUpload] with `bytes = -1`;
     * the caller decides whether to fall back.
     */
    fun uploadBlob(name: String, bytes: ByteArray): BlobUpload {
        if (!ShotBlob.isValidName(name)) {
            return BlobUpload(-1, "invalid blob name: " + name)
        }
        if (bytes.isEmpty()) return BlobUpload(-1, "empty blob")
        if (bytes.size.toLong() > ShotBlob.MAX_BYTES) {
            return BlobUpload(-1, "blob too large: " + bytes.size + " bytes")
        }

        val path = "/agent/blob/" + name
        return try {
            val url = URL(joinUrl(baseUrl, path))
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "PUT"
                connection.setRequestProperty("X-RC-Secret", secret)
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = BLOB_CONNECT_TIMEOUT_MS
                connection.readTimeout = BLOB_READ_TIMEOUT_MS
                connection.doOutput = true
                connection.doInput = true
                connection.instanceFollowRedirects = true

                connection.setFixedLengthStreamingMode(bytes.size)
                val out = connection.outputStream
                try {
                    out.write(bytes)
                    out.flush()
                } finally {
                    out.close()
                }

                val code = connection.responseCode
                if (code < 200 || code > 299) {
                    BlobUpload(-1, "HTTP " + code)
                } else {
                    // The ack is tiny; read it so a rejected-but-200 relay cannot
                    // pass for a stored blob.
                    val text = readAll(connection)
                    if (text.contains("\"ok\":false") || text.contains("\"ok\": false")) {
                        BlobUpload(-1, "relay rejected the blob")
                    } else {
                        BlobUpload(bytes.size, null)
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            BlobUpload(-1, describe(t))
        }
    }

    private fun readAll(connection: HttpURLConnection): String {
        val stream = try {
            connection.inputStream
        } catch (t: Throwable) {
            return ""
        } ?: return ""
        return try {
            String(stream.readBytes(), Charsets.UTF_8)
        } catch (t: Throwable) {
            ""
        } finally {
            try {
                stream.close()
            } catch (t: Throwable) {
                // Nothing useful to do: the connection is dropped anyway.
            }
        }
    }

    private fun post(path: String, body: JSONObject, readTimeoutMs: Int): JSONObject? {
        val url = URL(joinUrl(baseUrl, path))
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("X-RC-Secret", secret)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.doInput = true
            connection.instanceFollowRedirects = true

            val payload = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            val out = connection.outputStream
            try {
                out.write(payload)
                out.flush()
            } finally {
                out.close()
            }

            val code = connection.responseCode
            if (code < 200 || code > 299) {
                throw IllegalStateException("relay returned HTTP $code for $path")
            }
            val stream = connection.inputStream ?: return null
            val text = try {
                String(stream.readBytes(), Charsets.UTF_8)
            } finally {
                stream.close()
            }
            if (text.isEmpty()) return null
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** SPEC.md section 2: must exceed the 25 s long poll. */
        const val POLL_READ_TIMEOUT_MS = 45_000
        const val CONNECT_TIMEOUT_MS = 10_000
        const val HELLO_READ_TIMEOUT_MS = 15_000
        const val DEFAULT_TIMEOUT = 120

        /** A 16 MB body over a slow uplink needs room; 30 s / 60 s is the contract. */
        const val BLOB_CONNECT_TIMEOUT_MS = 30_000
        const val BLOB_READ_TIMEOUT_MS = 60_000
    }
}
