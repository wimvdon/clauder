package to.wim.clauder.claudeai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import to.wim.clauder.tasks.SessionState
import to.wim.clauder.tasks.TaskList

/**
 * A claude.ai project (channel), as the projects call lists it. [chatMark] changes when a message
 * is added to the project chat (`last_timeline_seq`), [threadsMark] when anything in it changes
 * (`last_activity_at` and `last_message_seq`), and [hasUnread] says whether the account has unread
 * messages there.
 */
data class RemoteProject(
    val id: String,
    val name: String,
    val lastActivityAt: String?,
    val chatMark: String? = null,
    val threadsMark: String? = null,
    val hasUnread: Boolean = false,
)

/**
 * One project thread as claude.ai reports it: what the Claude app's own thread list shows.
 * [sessionIds] are the sessions bound to it, the same ids the hooks send as a session's key.
 */
data class RemoteThread(
    val id: String,
    val project: RemoteProject,
    val title: String?,
    val status: String,
    val waitsOnPrompt: Boolean,
    val anyWorking: Boolean,
    val sessionIds: List<String>,
    val updatedAt: String?,
) {
    /** The state Clauder shows for it. Null for statuses it doesn't know (a new one, say). */
    val state: SessionState?
        get() = when {
            status == "resolved" -> SessionState.Done
            status == "archived" || status == "failed" -> SessionState.Ended
            status == "needs_input" || waitsOnPrompt -> SessionState.Waiting
            status == "working" || anyWorking -> SessionState.Running
            status == "idle" -> SessionState.Idle
            else -> null
        }
}

/** Reads claude.ai's project and thread lists, and lays them over the hook sessions. */
object RemoteThreads {
    /** Keys of threads that only claude.ai knows (no hooks), so they can't clash with session keys. */
    const val KEY_PREFIX = TaskList.CLAUDE_AI_KEY_PREFIX

    /** Projects active in the last [maxAgeMillis] before [nowMillis], newest first. */
    fun projects(body: String, nowMillis: Long, maxAgeMillis: Long = PROJECT_MAX_AGE_MS): List<RemoteProject> = try {
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .filter { it.isNull("archived_at") }
            .mapNotNull { o ->
                val id = o.text("id") ?: return@mapNotNull null
                val activity = o.text("last_activity_at")
                RemoteProject(
                    id = id,
                    name = o.text("name") ?: id,
                    lastActivityAt = activity,
                    chatMark = o.text("last_timeline_seq"),
                    threadsMark = listOfNotNull(activity, o.text("last_message_seq")).joinToString("/").ifEmpty { null },
                    hasUnread = o.optBoolean("has_unread", false),
                )
            }
            .filter { p -> p.lastActivityAt?.let(::millis)?.let { nowMillis - it <= maxAgeMillis } ?: true }
    } catch (e: JSONException) {
        emptyList()
    }

    /** The thread roots in a project's thread list, every status section together. */
    fun threads(body: String, project: RemoteProject): List<RemoteThread> = try {
        val sections = JSONObject(body).optJSONArray("sections") ?: JSONArray()
        (0 until sections.length()).mapNotNull { sections.optJSONObject(it) }.flatMap { section ->
            val data = section.optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { data.optJSONObject(it) }.mapNotNull { root ->
                thread(root, project, section.text("status"))
            }
        }
    } catch (e: JSONException) {
        emptyList()
    }

    private fun thread(root: JSONObject, project: RemoteProject, sectionStatus: String?): RemoteThread? {
        val id = root.text("id") ?: return null
        val bound = root.optJSONArray("bound_sessions") ?: JSONArray()
        val sessions = (0 until bound.length()).mapNotNull { bound.optJSONObject(it) }
        return RemoteThread(
            id = id,
            project = project,
            title = sessions.firstNotNullOfOrNull { it.text("title") } ?: root.text("thread_preview"),
            status = root.text("thread_status") ?: sectionStatus ?: "",
            waitsOnPrompt = root.optBoolean("thread_waits_on_live_prompt", false),
            anyWorking = root.optBoolean("thread_any_session_working", false),
            sessionIds = sessions.mapNotNull { it.text("id") },
            updatedAt = root.text("last_reply_at") ?: root.text("created_at"),
        )
    }

    /**
     * [lists] with claude.ai's view laid over them, as fetched at [fetchedAtMillis]:
     * - A hook session bound to a thread takes the thread's Done or Ended (a thread resolved or
     *   archived by hand sends no hook), unless a hook reported after the fetch. The hooks know
     *   more about the rest (CI waits, questions), so a working or waiting thread keeps the hook's
     *   state; only a hook session that can't tell (Idle) takes claude.ai's.
     * - When several hook sessions are bound to one thread, only the newest is kept, so a thread
     *   never shows twice.
     * - A thread no hook session knows is added when it waits for the person or is working, so
     *   threads without hooks show up too. It has no steps.
     */
    fun overlay(lists: List<TaskList>, threads: List<RemoteThread>, fetchedAtMillis: Long): List<TaskList> {
        if (threads.isEmpty()) return lists
        val bySession = threads.flatMap { t -> t.sessionIds.map { id(it) to t } }.toMap()
        // The newest hook session of each thread; older ones bound to the same thread are dropped.
        val newest = lists.filter { bySession[id(it.key)] != null }
            .groupBy { bySession.getValue(id(it.key)).id }
            .mapValues { (_, sessions) -> sessions.maxBy { millis(it.updatedAt) ?: 0 }.key }
        val matched = newest.keys
        val updated = lists.mapNotNull { list ->
            val thread = bySession[id(list.key)] ?: return@mapNotNull list
            if (newest[thread.id] != list.key) return@mapNotNull null
            val state = thread.state ?: return@mapNotNull list.withProject(thread)
            val hookNewer = (millis(list.updatedAt) ?: 0) > fetchedAtMillis
            val takes = state == SessionState.Done || state == SessionState.Ended || list.state == SessionState.Idle
            if (hookNewer || !takes || state == list.state) list.withProject(thread) else list.withProject(thread).copy(state = state)
        }
        val added = threads
            .filter { it.id !in matched }
            .filter { it.state == SessionState.Waiting || it.state == SessionState.Running }
            .map { t ->
                TaskList(
                    key = KEY_PREFIX + t.id,
                    title = t.title ?: t.project.name,
                    url = t.sessionIds.firstOrNull()?.let { "https://claude.ai/code/$it" },
                    state = t.state ?: SessionState.Idle,
                    seq = 0,
                    updatedAt = t.updatedAt ?: java.time.Instant.ofEpochMilli(fetchedAtMillis).toString(),
                    tasks = emptyList(),
                    project = t.project.name,
                    projectId = t.project.id,
                )
            }
        return (updated + added).sortedByDescending { millis(it.updatedAt) ?: 0 }
    }

    /** [lists] with [snapshot] laid over them, or [lists] alone without one. */
    fun overlay(lists: List<TaskList>, snapshot: RemoteSnapshot?): List<TaskList> =
        snapshot?.let { overlay(lists, it.threads, it.fetchedAtMillis) } ?: lists

    /** What a new read changed for the notifications: each shown session whose state changed (with
     * the copy shown before, if any), and the keys no longer shown at all. */
    data class Changes(val shown: List<Pair<TaskList?, TaskList>>, val gone: List<String>)

    fun changes(before: List<TaskList>, after: List<TaskList>): Changes {
        val old = before.associateBy { it.key }
        val shown = after.mapNotNull { list ->
            val previous = old[list.key]
            if (previous != null && previous.state == list.state) null else previous to list
        }
        val now = after.map { it.key }.toSet()
        return Changes(shown, before.map { it.key }.filter { it !in now })
    }

    /** The snapshot as JSON, so it lasts between app starts (see [snapshotFromJson]). */
    fun snapshotToJson(snapshot: RemoteSnapshot): String = JSONObject()
        .put("fetchedAt", snapshot.fetchedAtMillis)
        .put("organization", snapshot.organization)
        .put("projects", JSONArray(snapshot.projects.map { projectJson(it) }))
        .put(
            "threads",
            JSONArray(
                snapshot.threads.map { t ->
                    JSONObject()
                        .put("id", t.id)
                        .put("project", t.project.id)
                        .put("title", t.title ?: JSONObject.NULL)
                        .put("status", t.status)
                        .put("waits", t.waitsOnPrompt)
                        .put("working", t.anyWorking)
                        .put("sessions", JSONArray(t.sessionIds))
                        .put("updatedAt", t.updatedAt ?: JSONObject.NULL)
                },
            ),
        )
        .toString()

    fun snapshotFromJson(text: String): RemoteSnapshot? = try {
        val o = JSONObject(text)
        val projectsArray = o.optJSONArray("projects") ?: JSONArray()
        val projects = (0 until projectsArray.length()).mapNotNull { projectsArray.optJSONObject(it) }.mapNotNull { p ->
            val id = p.text("id") ?: return@mapNotNull null
            RemoteProject(id, p.text("name") ?: id, p.text("lastActivityAt"), p.text("chatMark"), p.text("threadsMark"), p.optBoolean("hasUnread"))
        }
        val byId = projects.associateBy { it.id }
        val threadsArray = o.optJSONArray("threads") ?: JSONArray()
        val threads = (0 until threadsArray.length()).mapNotNull { threadsArray.optJSONObject(it) }.mapNotNull { t ->
            val project = byId[t.text("project")] ?: return@mapNotNull null
            val sessions = t.optJSONArray("sessions") ?: JSONArray()
            RemoteThread(
                id = t.text("id") ?: return@mapNotNull null,
                project = project,
                title = t.text("title"),
                status = t.optString("status"),
                waitsOnPrompt = t.optBoolean("waits"),
                anyWorking = t.optBoolean("working"),
                sessionIds = (0 until sessions.length()).map { sessions.optString(it) }.filter { it.isNotBlank() },
                updatedAt = t.text("updatedAt"),
            )
        }
        RemoteSnapshot(threads, o.getLong("fetchedAt"), o.optString("organization"), projects)
    } catch (e: JSONException) {
        null
    }

    private fun projectJson(p: RemoteProject): JSONObject = JSONObject()
        .put("id", p.id)
        .put("name", p.name)
        .put("lastActivityAt", p.lastActivityAt ?: JSONObject.NULL)
        .put("chatMark", p.chatMark ?: JSONObject.NULL)
        .put("threadsMark", p.threadsMark ?: JSONObject.NULL)
        .put("hasUnread", p.hasUnread)

    /**
     * The claude.ai thread behind the row [key], from [snapshot]: the thread itself for a row only
     * claude.ai knows, else the thread a hook session with that key is bound to. Null when claude.ai
     * didn't list it.
     */
    fun threadFor(snapshot: RemoteSnapshot?, key: String): RemoteThread? {
        val threads = snapshot?.threads ?: return null
        if (key.startsWith(KEY_PREFIX)) return threads.firstOrNull { it.id == key.removePrefix(KEY_PREFIX) }
        return threads.firstOrNull { t -> t.sessionIds.any { id(it) == id(key) } }
    }

    /** The body that resolves a thread on claude.ai (see [ClaudeAi.Paths.thread]). */
    const val RESOLVE_BODY = "{\"resolved\":true}"

    /** [snapshot] with [threadId] resolved, so the row shows Done before the next read confirms it. */
    fun resolved(snapshot: RemoteSnapshot, threadId: String): RemoteSnapshot = snapshot.copy(
        threads = snapshot.threads.map {
            if (it.id == threadId) it.copy(status = "resolved", waitsOnPrompt = false, anyWorking = false) else it
        },
    )

    /** True for a row only claude.ai knows about. */
    fun isRemoteOnly(list: TaskList): Boolean = list.key.startsWith(KEY_PREFIX)

    private fun TaskList.withProject(thread: RemoteThread): TaskList =
        if (project != null) this else copy(project = thread.project.name, projectId = projectId ?: thread.project.id)

    /** Session ids can carry a kind prefix (session_…, cse_…) for the same session. */
    private fun id(sessionId: String): String = sessionId.substringAfter('_')

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun millis(text: String?): Long? = try {
        text?.let { java.time.Instant.parse(it).toEpochMilli() }
    } catch (e: java.time.format.DateTimeParseException) {
        null
    }

    const val PROJECT_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
}
