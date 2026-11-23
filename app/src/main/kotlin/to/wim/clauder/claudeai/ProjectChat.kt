package to.wim.clauder.claudeai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One top-level message in a project chat, newest first in [ProjectChat.messages]. */
data class ChatMessage(val id: String, val author: ChatMessage.Author, val createdAtMillis: Long, val text: String?) {
    enum class Author { Person, Claude, Other }
}

/**
 * What the phone remembers of one project chat between reads, without any message text:
 * [mark] is the project's chat mark at the last read, [lastClaudeMillis] the newest message from
 * Claude seen there, [alertFor] the person's message the shown alert answers (null: no alert),
 * [unreadSeen] whether claude.ai said there was something unread since that alert, and
 * [waitingSince] when the person's message that still has no answer was sent.
 */
data class ChatWatch(
    val mark: String?,
    val lastClaudeMillis: Long,
    val alertFor: String? = null,
    val unreadSeen: Boolean = false,
    val waitingSince: Long? = null,
)

/** An alert that Claude replied in a project chat: the newest reply's start, and whether it sounds. */
data class ChatReply(val projectId: String, val projectName: String, val text: String, val sound: Boolean)

/**
 * Alerts when Claude replies in a project chat after the person's latest message there. The project
 * chat's Claude (the coordinator) sends no hook events, so this reads claude.ai's own project chat.
 */
object ProjectChat {
    /** What one check found for a project: the watch to keep, an alert to show, or the alert to take away. */
    data class Outcome(val watch: ChatWatch, val reply: ChatReply? = null, val cancel: Boolean = false)

    /** The top-level messages in a project chat (claude.ai's timeline), newest first. */
    fun messages(body: String): List<ChatMessage> = try {
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .filter { m ->
                val root = m.text("thread_root_id")
                root == null || root == m.text("id")
            }
            .mapNotNull { m ->
                val id = m.text("id") ?: return@mapNotNull null
                val created = millis(m.text("created_at")) ?: return@mapNotNull null
                ChatMessage(id, author(m), created, text(m.opt("body")))
            }
            .sortedByDescending { it.createdAtMillis }
    } catch (e: JSONException) {
        emptyList()
    }

    /** A message from a session or agent is Claude's; one with only an account is a person's. */
    fun author(message: JSONObject): ChatMessage.Author = when {
        message.text("author_session_id") != null || message.text("author_agent_id") != null -> ChatMessage.Author.Claude
        message.text("author_account_id") != null -> ChatMessage.Author.Person
        else -> ChatMessage.Author.Other
    }

    /**
     * The start of a message as plain text, for the alert. Cards (a checklist, a decision, a tool
     * note) arrive as a JSON object in the body and have no text to show, so they give null.
     */
    fun text(body: Any?): String? {
        val raw = when (body) {
            is String -> body
            is JSONObject -> body.text("text") ?: body.text("markdown") ?: return null
            else -> return null
        }.trim()
        if (raw.startsWith("{")) {
            try {
                JSONObject(raw)
                return null
            } catch (e: JSONException) {
                // Text that only starts with a brace.
            }
        }
        val plain = raw
            .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
            .replace(Regex("""(?m)^\s{0,3}(#{1,6}|>|[-*+]|\d+[.)])\s+"""), "")
            .replace(Regex("""[*_`~]+"""), "")
        val line = plain.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (line.isEmpty()) return null
        return if (line.length <= MAX_TEXT) line else line.take(MAX_TEXT - 1).trimEnd() + "…"
    }

    /**
     * Checks [project]'s chat. [messages] is null when the chat was not read again because its mark
     * didn't change. The first look at a chat ([previous] null) only remembers where it stands, so
     * old replies don't alert. A reply alerts when Claude wrote it after the person's latest message,
     * it is newer than anything seen before and at most [MAX_AGE_MS] old. Only the first reply to a
     * message sounds; later ones update the alert quietly. The alert goes away once claude.ai says
     * the chat has nothing unread, after having said it had.
     */
    fun check(project: RemoteProject, previous: ChatWatch?, messages: List<ChatMessage>?, nowMillis: Long): Outcome {
        if (messages == null) {
            val watch = previous ?: ChatWatch(project.chatMark, nowMillis)
            return unread(project, watch, nowMillis)
        }
        val newestClaude = messages.filter { it.author == ChatMessage.Author.Claude }.maxOfOrNull { it.createdAtMillis }
        val lastClaude = maxOf(newestClaude ?: 0, previous?.lastClaudeMillis ?: 0)
        val person = messages.firstOrNull { it.author == ChatMessage.Author.Person }
        val newest = messages.firstOrNull()
        val waitingSince = newest?.takeIf {
            it.author == ChatMessage.Author.Person && nowMillis - it.createdAtMillis <= WAIT_MS
        }?.createdAtMillis
        val base = (previous ?: ChatWatch(project.chatMark, lastClaude)).copy(
            mark = project.chatMark,
            lastClaudeMillis = lastClaude,
            waitingSince = waitingSince,
        )
        if (previous == null || person == null) return unread(project, base, nowMillis)
        val reply = messages.firstOrNull {
            it.author == ChatMessage.Author.Claude && it.text != null &&
                it.createdAtMillis > person.createdAtMillis && it.createdAtMillis > previous.lastClaudeMillis
        }
        if (reply == null || nowMillis - reply.createdAtMillis > MAX_AGE_MS) return unread(project, base, nowMillis)
        val sound = previous.alertFor != person.id
        return Outcome(
            watch = base.copy(alertFor = person.id, unreadSeen = project.hasUnread),
            reply = ChatReply(project.id, project.name, reply.text!!, sound),
        )
    }

    private fun unread(project: RemoteProject, watch: ChatWatch, nowMillis: Long): Outcome {
        val waiting = watch.waitingSince?.takeIf { nowMillis - it <= WAIT_MS }
        if (watch.alertFor == null) return Outcome(watch.copy(waitingSince = waiting))
        if (project.hasUnread) return Outcome(watch.copy(unreadSeen = true, waitingSince = waiting))
        if (!watch.unreadSeen) return Outcome(watch.copy(waitingSince = waiting))
        return Outcome(watch.copy(alertFor = null, unreadSeen = false, waitingSince = waiting), cancel = true)
    }

    /** What checking every project's chat found: the watches to keep, alerts to show and to take away. */
    data class Round(val watches: Map<String, ChatWatch>, val replies: List<ChatReply>, val cancel: List<String>)

    /**
     * Checks the chats of [projects], reading a chat again only when its mark changed since
     * [watches] (or it was never read). A chat that couldn't be read keeps its watch as it was.
     */
    suspend fun round(calls: ClaudeAiCalls, projects: List<RemoteProject>, watches: Map<String, ChatWatch>, nowMillis: Long): Round {
        val next = watches.toMutableMap()
        val replies = mutableListOf<ChatReply>()
        val cancel = mutableListOf<String>()
        projects.forEach { project ->
            val previous = watches[project.id]
            val changed = previous == null || project.chatMark == null || previous.mark != project.chatMark
            val messages = if (changed) {
                val answer = calls.get(ClaudeAi.Paths.timeline(project.id, limit = TIMELINE_LIMIT))
                if (answer.ok) messages(answer.body) else return@forEach
            } else {
                null
            }
            val outcome = check(project, previous, messages, nowMillis)
            next[project.id] = outcome.watch
            outcome.reply?.let { replies += it }
            if (outcome.cancel) cancel += project.id
        }
        return Round(next, replies, cancel)
    }

    const val TIMELINE_LIMIT = 20

    /** True while a person's message in some chat is at most [WAIT_MS] old and has no reply yet. */
    fun anyWaiting(watches: Collection<ChatWatch>, nowMillis: Long): Boolean =
        watches.any { w -> w.waitingSince?.let { nowMillis - it <= WAIT_MS } == true }

    /** The project chat on claude.ai, for the alert's tap. */
    fun url(projectId: String): String = "https://claude.ai/code/project/$projectId"

    fun watchesToJson(watches: Map<String, ChatWatch>): String = JSONObject().apply {
        watches.forEach { (id, w) ->
            put(
                id,
                JSONObject()
                    .put("mark", w.mark ?: JSONObject.NULL)
                    .put("lastClaude", w.lastClaudeMillis)
                    .put("alertFor", w.alertFor ?: JSONObject.NULL)
                    .put("unreadSeen", w.unreadSeen)
                    .put("waitingSince", w.waitingSince ?: JSONObject.NULL),
            )
        }
    }.toString()

    fun watchesFromJson(text: String?): Map<String, ChatWatch> = try {
        val o = JSONObject(text ?: return emptyMap())
        o.keys().asSequence().mapNotNull { id ->
            val w = o.optJSONObject(id) ?: return@mapNotNull null
            id to ChatWatch(
                mark = w.text("mark"),
                lastClaudeMillis = w.optLong("lastClaude"),
                alertFor = w.text("alertFor"),
                unreadSeen = w.optBoolean("unreadSeen"),
                waitingSince = if (w.isNull("waitingSince")) null else w.optLong("waitingSince"),
            )
        }.toMap()
    } catch (e: JSONException) {
        emptyMap()
    }

    /** Characters of the reply in the alert. */
    const val MAX_TEXT = 300

    /** Replies older than this don't alert (the phone was off, say). */
    const val MAX_AGE_MS = 2 * 60 * 60 * 1000L

    /** How long after the person's message Clauder checks every minute for Claude's reply. */
    const val WAIT_MS = 30 * 60 * 1000L

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun millis(text: String?): Long? = try {
        text?.let { java.time.Instant.parse(it).toEpochMilli() }
    } catch (e: java.time.format.DateTimeParseException) {
        null
    }
}
