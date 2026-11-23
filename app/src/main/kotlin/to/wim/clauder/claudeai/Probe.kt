package to.wim.clauder.claudeai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** What one call to claude.ai returned: an HTTP code and body, or the error that stopped it. */
data class CallResult(val code: Int? = null, val body: String = "", val error: String? = null) {
    val ok: Boolean get() = code in 200..299
}

/** The calls the probe makes. ClaudeAiClient does them over the network; tests use a fake. */
interface ClaudeAiCalls {
    suspend fun get(path: String): CallResult

    /** Reads a server-sent event stream for at most [millis], then closes it. Body = the lines read. */
    suspend fun stream(path: String, millis: Long): CallResult
}

/**
 * Tries the endpoints Clauder would use and describes what came back, so a live test can confirm the
 * shapes found in the Claude app. The report holds only HTTP codes, field names, counts and status
 * values: no names, messages or the sign-in itself, so it is safe to paste into a thread.
 */
object Probe {
    const val STREAM_MILLIS = 8_000L

    suspend fun run(calls: ClaudeAiCalls): String {
        val lines = mutableListOf<String>()
        val channels = calls.get(ClaudeAi.Paths.channels(limit = 20))
        lines += line("channels", channels) { describeChannels(it) }
        val channelId = if (channels.ok) firstChannelId(channels.body) else null
        if (channelId != null) {
            lines += line("threads", calls.get(ClaudeAi.Paths.threads(channelId, limit = 20))) { describeThreads(it) }
            lines += line("project chat", calls.get(ClaudeAi.Paths.timeline(channelId, limit = 10))) { describeTimeline(it) }
            lines += line("channel stream", calls.stream(ClaudeAi.Paths.channelStream(channelId), STREAM_MILLIS)) { describeStream(it) }
        } else {
            lines += "threads: skipped (no project)"
        }
        lines += line("notifications summary", calls.get(ClaudeAi.Paths.NOTIFICATIONS_SUMMARY)) { keysOf(JSONObject(it)) }
        lines += line("sessions watch", calls.stream(ClaudeAi.Paths.SESSIONS_WATCH, STREAM_MILLIS)) { describeStream(it) }
        return lines.joinToString("\n")
    }

    fun line(name: String, result: CallResult, describe: (String) -> String): String {
        val head = "$name: " + (result.code?.let { "HTTP $it" } ?: "error ${result.error}")
        if (!result.ok) return head + challengeNote(result)
        val detail = try {
            describe(result.body)
        } catch (e: JSONException) {
            "not JSON (${result.body.length} chars)"
        }
        return "$head, $detail"
    }

    /** Cloudflare's bot check answers 403 with an HTML page that says "Just a moment". */
    private fun challengeNote(result: CallResult): String =
        if (result.body.contains("Just a moment") || result.body.contains("challenge-platform")) ", Cloudflare check" else ""

    fun firstChannelId(body: String): String? = try {
        JSONObject(body).optJSONArray("data")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
    } catch (e: JSONException) {
        null
    }

    fun describeChannels(body: String): String {
        val o = JSONObject(body)
        val data = o.optJSONArray("data") ?: JSONArray()
        val first = data.optJSONObject(0)
        return "projects=${data.length()}, keys=${keysOf(o)}" + (first?.let { ", project keys=${keysOf(it)}" } ?: "")
    }

    fun describeThreads(body: String): String {
        val o = JSONObject(body)
        val sections = o.optJSONArray("sections") ?: JSONArray()
        val counts = (0 until sections.length()).mapNotNull { sections.optJSONObject(it) }.joinToString(", ") {
            val shown = it.optJSONArray("data")?.length() ?: 0
            "${it.optString("status", "?")}=${it.opt("count") ?: "?"}/$shown"
        }
        val root = (0 until sections.length())
            .mapNotNull { sections.optJSONObject(it)?.optJSONArray("data")?.optJSONObject(0) }
            .firstOrNull()
        val session = root?.optJSONArray("bound_sessions")?.optJSONObject(0)
        val statuses = (0 until sections.length())
            .flatMap { i ->
                val data = sections.optJSONObject(i)?.optJSONArray("data") ?: JSONArray()
                (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("thread_status")?.takeIf { s -> s.isNotBlank() } }
            }
            .toSortedSet()
        return buildString {
            append("sections=[$counts], keys=${keysOf(o)}")
            if (statuses.isNotEmpty()) append(", thread_status=${statuses.joinToString(",", "[", "]")}")
            root?.let { append(", thread keys=${keysOf(it)}") }
            session?.let { append(", session keys=${keysOf(it)}") }
        }
    }

    /**
     * The project chat's messages as author kind, top-level or reply, and time: enough to check that
     * a reply from Claude after the person's last message can be told apart. No message text.
     */
    fun describeTimeline(body: String): String {
        val o = JSONObject(body)
        val data = o.optJSONArray("data") ?: JSONArray()
        val messages = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
        val rows = messages.take(6).joinToString(" ") { m ->
            val root = m.optString("thread_root_id").takeIf { it.isNotBlank() && it != "null" }
            val place = if (root == null || root == m.optString("id")) "top" else "reply"
            "${authorKind(m)}/$place@${m.optString("created_at", "?")}"
        }
        return "messages=${messages.size}, keys=${keysOf(o)}" +
            (messages.firstOrNull()?.let { ", message keys=${keysOf(it)}" } ?: "") +
            ", newest=[$rows]"
    }

    /** person, claude (a session or agent wrote it), or other (a server notice, say). */
    fun authorKind(message: JSONObject): String {
        fun has(key: String) = !message.isNull(key) && message.optString(key).isNotBlank()
        return when {
            has("author_session_id") || has("author_agent_id") -> "claude"
            has("author_account_id") -> "person"
            else -> "other"
        }
    }

    /** Event names and the field names of each event's data, from the lines of an event stream. */
    fun describeStream(body: String): String {
        val events = sortedSetOf<String>()
        val dataKeys = sortedSetOf<String>()
        var dataLines = 0
        body.lineSequence().forEach { raw ->
            val lineText = raw.trimEnd('\r')
            when {
                lineText.startsWith("event:") -> events += lineText.removePrefix("event:").trim()
                lineText.startsWith("data:") -> {
                    dataLines++
                    try {
                        val o = JSONObject(lineText.removePrefix("data:").trim())
                        o.keys().forEach { dataKeys += it }
                        o.optString("type").takeIf { it.isNotBlank() }?.let { events += "type:$it" }
                    } catch (e: JSONException) {
                        // A data line that isn't JSON (a keep-alive, say) only counts.
                    }
                }
            }
        }
        return "events=${events.joinToString(",", "[", "]")}, data lines=$dataLines, data keys=${dataKeys.joinToString(",", "[", "]")}"
    }

    fun keysOf(o: JSONObject): String = o.keys().asSequence().toSortedSet().joinToString(",", "[", "]")
}
