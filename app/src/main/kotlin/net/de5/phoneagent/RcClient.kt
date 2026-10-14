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
    }
}
