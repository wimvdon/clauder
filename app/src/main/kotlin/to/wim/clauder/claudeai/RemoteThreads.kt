package to.wim.clauder.claudeai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import to.wim.clauder.tasks.SessionState
import to.wim.clauder.tasks.TaskList

/** A claude.ai project (channel), as the projects call lists it. */
data class RemoteProject(val id: String, val name: String, val lastActivityAt: String?)

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
    const val KEY_PREFIX = "claudeai:"

    /** Projects active in the last [maxAgeMillis] before [nowMillis], newest first. */
    fun projects(body: String, nowMillis: Long, maxAgeMillis: Long = PROJECT_MAX_AGE_MS): List<RemoteProject> = try {
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .filter { it.isNull("archived_at") }
            .mapNotNull { o ->
                val id = o.text("id") ?: return@mapNotNull null
                RemoteProject(id, o.text("name") ?: id, o.text("last_activity_at"))
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
     * - A hook session bound to a thread takes the thread's state, unless a hook reported after
     *   the fetch (then the hooks are newer). This brings in threads resolved by hand.
     * - A thread no hook session knows is added when it waits for the person or is working, so
     *   threads without hooks show up too. It has no steps.
     */
    fun overlay(lists: List<TaskList>, threads: List<RemoteThread>, fetchedAtMillis: Long): List<TaskList> {
        if (threads.isEmpty()) return lists
        val bySession = threads.flatMap { t -> t.sessionIds.map { id(it) to t } }.toMap()
        val matched = mutableSetOf<String>()
        val updated = lists.map { list ->
            val thread = bySession[id(list.key)] ?: return@map list
            matched += thread.id
            val state = thread.state ?: return@map list
            val hookNewer = (millis(list.updatedAt) ?: 0) > fetchedAtMillis
            if (hookNewer || state == list.state) list.withProject(thread) else list.withProject(thread).copy(state = state)
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
