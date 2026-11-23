package to.wim.clauder.tasks

import org.json.JSONArray
import org.json.JSONObject

enum class TaskStatus { Pending, InProgress, Completed }

/** Running: Claude is working (or a step is still in progress). Idle: nothing is in progress. Waiting: Claude
 * asked you something (a decision or a permission). Done: every task is completed. Ended: the
 * session closed. */
enum class SessionState { Running, Idle, Waiting, Done, Ended }

data class TaskItem(val id: String, val subject: String, val status: TaskStatus)

enum class PrState { Open, Merged, Closed }

enum class CiState { Running, Passed, Failed }

/** The pull request a session works on, and how its CI is doing (null: not known yet). */
data class PullRequest(val number: Int, val url: String, val state: PrState, val ci: CiState?)

/** One Claude session, as the Worker sends it. A project thread has steps (its progress
 * checklist, the plan the user follows); Claude Code also keeps tasks (its own work list). */
data class TaskList(
    val key: String,
    val title: String,
    val url: String?,
    val state: SessionState,
    val seq: Long,
    val updatedAt: String,
    val tasks: List<TaskItem>,
    val steps: List<TaskItem> = emptyList(),
    /** The claude.ai project of a project thread, and the repository folder Claude works in. */
    val project: String? = null,
    val projectId: String? = null,
    val repo: String? = null,
    /** While waiting: what Claude asks, such as a decision's question or the permission it needs. */
    val need: String? = null,
    /** A one-line summary of Claude's last turn (read by a model on the Worker), until the next prompt. */
    val summary: String? = null,
    val pr: PullRequest? = null,
    /** A page Claude's last reply attached that the phone can open, such as a mockup. */
    val link: String? = null,
    val linkTitle: String? = null,
    /** When a reminder Claude set brings it back (ISO time), if that is still ahead. */
    val wakeAt: String? = null,
    /** Background helpers (subagents, shells) still running when Claude's turn ended. */
    val helpers: Int = 0,
    /** Tokens used so far, and what they would cost on the Claude API in US dollars. */
    val tokens: Long = 0,
    val cost: Double = 0.0,
    /** The session runs hook settings from before they reported every wake source, so the Worker
     * can't always tell whether it waits for you. Those hooks can't update themselves. */
    val oldHooks: Boolean = false,
    /** The tool Claude runs now, until its turn ends. */
    val doing: Doing? = null,
    /** What Claude changed since the person last wrote, until the person writes again. */
    val work: WorkReceipt? = null,
) {
    /** What sessions are grouped by: the project's name, else the repository's. */
    val group: String? get() = project ?: repo

    val hasSteps: Boolean get() = steps.isNotEmpty()

    /** What progress is counted on: the steps when there are any, else the tasks. */
    val items: List<TaskItem> get() = if (hasSteps) steps else tasks

    val doneCount: Int get() = items.count { it.status == TaskStatus.Completed }
    val current: TaskItem? get() = items.firstOrNull { it.status == TaskStatus.InProgress }
    val next: TaskItem?
        get() {
            val currentIndex = items.indexOfFirst { it.status == TaskStatus.InProgress }
            return items.withIndex().firstOrNull { (i, t) -> i > currentIndex && t.status == TaskStatus.Pending }?.value
        }
    val isFinished: Boolean get() = state == SessionState.Done || state == SessionState.Ended

    /** The session closed before its last step or task was done. */
    val endedEarly: Boolean get() = state == SessionState.Ended && doneCount < items.size

    /**
     * The state to show at [nowMillis]. The checklist decides: a step (or task) marked in progress
     * means Claude is working, even between turns (while it waits on CI or a reminder). Only a
     * session that has been silent for [STALE_MS] counts as idle, so an abandoned one moves out of
     * the way.
     */
    fun shown(nowMillis: Long): TaskList {
        val silent = updatedMillis()?.let { nowMillis - it > STALE_MS } ?: false
        val next = when {
            state == SessionState.Idle && current != null && !silent -> SessionState.Running
            state == SessionState.Running && silent -> SessionState.Idle
            else -> state
        }
        return if (next == state) this else copy(state = next)
    }

    private fun updatedMillis(): Long? = parseMillis(updatedAt)

    /** When the reminder fires, if it is still ahead at [nowMillis]. */
    fun wakeAtMillis(nowMillis: Long): Long? = wakeAt?.let(::parseMillis)?.takeIf { it > nowMillis }

    /** The Worker's GET /sessions format, so [fromJson] reads it back (used for the cache). */
    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("title", title)
        .put("url", url ?: "")
        .put("state", state.name.lowercase())
        .put("seq", seq)
        .put("updatedAt", updatedAt)
        .put("tasks", itemsToJson(tasks))
        .put("steps", itemsToJson(steps))
        .put("project", project ?: "")
        .put("projectId", projectId ?: "")
        .put("repo", repo ?: "")
        .put("need", need ?: "")
        .put("summary", summary ?: "")
        .apply {
            pr?.let {
                put(
                    "pr",
                    JSONObject().put("number", it.number).put("url", it.url).put("state", it.state.name.lowercase())
                        .put("ci", it.ci?.name?.lowercase() ?: ""),
                )
            }
            link?.let { put("link", JSONObject().put("url", it).put("title", linkTitle ?: "")) }
            wakeAt?.let { put("wakeAt", it) }
            if (helpers > 0) put("helpers", helpers)
            if (tokens > 0) put("usage", JSONObject().put("tokens", tokens).put("cost", cost))
            put("hooksCurrent", !oldHooks)
            doing?.let { put("doing", JSONObject().put("tool", it.tool).put("detail", it.detail ?: "")) }
            work?.let {
                put(
                    "work",
                    JSONObject().put("since", it.since).put("endedAt", it.endedAt).put("files", it.files)
                        .put("added", it.added).put("removed", it.removed),
                )
            }
        }

    private fun itemsToJson(items: List<TaskItem>) = JSONArray(
        items.map {
            JSONObject().put("id", it.id).put("subject", it.subject).put(
                "status",
                when (it.status) {
                    TaskStatus.Pending -> "pending"
                    TaskStatus.InProgress -> "in_progress"
                    TaskStatus.Completed -> "completed"
                },
            )
        },
    )

    companion object {
        /** How long a session may stay silent before it counts as idle: long enough for a slow CI
         * run or a reminder later in the day. */
        const val STALE_MS = 12 * 60 * 60 * 1000L

        private fun parseMillis(text: String): Long? = try {
            java.time.Instant.parse(text).toEpochMilli()
        } catch (e: java.time.format.DateTimeParseException) {
            null
        }

        private fun prState(value: String?): PrState = when (value) {
            "merged" -> PrState.Merged
            "closed" -> PrState.Closed
            else -> PrState.Open
        }

        private fun ciState(value: String?): CiState? = when (value) {
            "running" -> CiState.Running
            "passed" -> CiState.Passed
            "failed" -> CiState.Failed
            else -> null
        }

        /** Only https pull request links on GitHub. */
        private fun pullRequest(number: Int, url: String, state: String?, ci: String?): PullRequest? {
            if (number <= 0 || !url.startsWith("https://github.com/")) return null
            return PullRequest(number, url, prState(state), ciState(ci))
        }

        private fun doing(tool: String?, detail: String?): Doing? =
            tool?.trim()?.takeIf { it.isNotEmpty() }?.let { Doing(it, detail?.trim()?.takeIf { d -> d.isNotEmpty() }) }

        private fun work(since: String?, endedAt: String?, files: Int, added: Int, removed: Int): WorkReceipt? {
            if (since.isNullOrBlank() || endedAt.isNullOrBlank() || files < 0 || added < 0 || removed < 0) return null
            return WorkReceipt(since, endedAt, files, added, removed).takeIf { !it.isEmpty }
        }

        private fun httpsOrNull(url: String?): String? = url?.trim()?.takeIf { it.startsWith("https://") && ' ' !in it }

        private fun status(value: String?): TaskStatus = when (value) {
            "in_progress" -> TaskStatus.InProgress
            "completed" -> TaskStatus.Completed
            else -> TaskStatus.Pending
        }

        private fun state(value: String?): SessionState = when (value) {
            "idle" -> SessionState.Idle
            "waiting" -> SessionState.Waiting
            "done" -> SessionState.Done
            "ended" -> SessionState.Ended
            else -> SessionState.Running
        }

        /** Parses an FCM data message from the Worker. Returns null for anything else. */
        fun fromPushData(data: Map<String, String>): TaskList? {
            if (data["type"] != "tasklist") return null
            val key = data["key"]?.takeIf { it.isNotBlank() } ?: return null
            fun items(field: String): List<TaskItem> {
                val array = JSONArray(data[field] ?: "[]")
                return (0 until array.length()).map { i ->
                    val t = array.getJSONObject(i)
                    TaskItem(t.getString("i"), t.optString("s", ""), status(t.optString("t")))
                }
            }
            val (tasks, steps) = try {
                items("tasks") to items("steps")
            } catch (e: org.json.JSONException) {
                return null
            }
            return TaskList(
                key = key,
                title = data["title"].orEmpty().ifBlank { "Claude Code" },
                url = data["url"]?.takeIf { it.isNotBlank() },
                state = state(data["state"]),
                seq = data["seq"]?.toLongOrNull() ?: 0,
                updatedAt = data["updatedAt"].orEmpty(),
                tasks = tasks,
                steps = steps,
                project = data["project"]?.takeIf { it.isNotBlank() },
                projectId = data["projectId"]?.takeIf { it.isNotBlank() },
                repo = data["repo"]?.takeIf { it.isNotBlank() },
                need = data["need"]?.takeIf { it.isNotBlank() },
                summary = data["summary"]?.takeIf { it.isNotBlank() },
                pr = data["pr"]?.takeIf { it.isNotBlank() }?.let { text ->
                    try {
                        val o = JSONObject(text)
                        pullRequest(o.optInt("n"), o.optString("u"), o.optString("s"), o.optString("c"))
                    } catch (e: org.json.JSONException) {
                        null
                    }
                },
                link = httpsOrNull(data["link"]),
                linkTitle = data["linkTitle"]?.takeIf { it.isNotBlank() },
                wakeAt = data["wakeAt"]?.takeIf { it.isNotBlank() },
                helpers = data["helpers"]?.toIntOrNull() ?: 0,
                tokens = data["tokens"]?.toLongOrNull() ?: 0,
                cost = data["cost"]?.toDoubleOrNull() ?: 0.0,
                oldHooks = data["hooks"] == "old",
                doing = data["doing"]?.takeIf { it.isNotBlank() }?.let { text ->
                    try {
                        val o = JSONObject(text)
                        doing(o.optString("t"), o.optString("d"))
                    } catch (e: org.json.JSONException) {
                        null
                    }
                },
                work = data["work"]?.takeIf { it.isNotBlank() }?.let { text ->
                    try {
                        val o = JSONObject(text)
                        work(o.optString("s"), o.optString("e"), o.optInt("f", -1), o.optInt("a", -1), o.optInt("r", -1))
                    } catch (e: org.json.JSONException) {
                        null
                    }
                },
            )
        }

        /** Parses one entry of the Worker's GET /sessions answer. */
        fun fromJson(json: JSONObject): TaskList? {
            val key = json.optString("key").takeIf { it.isNotBlank() } ?: return null
            fun items(field: String): List<TaskItem> {
                val array = json.optJSONArray(field) ?: JSONArray()
                return (0 until array.length()).mapNotNull { i ->
                    val t = array.optJSONObject(i) ?: return@mapNotNull null
                    TaskItem(t.optString("id"), t.optString("subject"), status(t.optString("status")))
                }
            }
            return TaskList(
                key = key,
                title = json.optString("title").ifBlank { "Claude Code" },
                url = json.optString("url").takeIf { it.isNotBlank() && it != "null" },
                state = state(json.optString("state")),
                seq = json.optLong("seq"),
                updatedAt = json.optString("updatedAt"),
                tasks = items("tasks"),
                steps = items("steps"),
                project = json.optString("project").takeIf { it.isNotBlank() },
                projectId = json.optString("projectId").takeIf { it.isNotBlank() },
                repo = json.optString("repo").takeIf { it.isNotBlank() },
                need = json.optString("need").takeIf { it.isNotBlank() },
                summary = json.optString("summary").takeIf { it.isNotBlank() },
                pr = json.optJSONObject("pr")?.let {
                    pullRequest(it.optInt("number"), it.optString("url"), it.optString("state"), it.optString("ci"))
                },
                link = httpsOrNull(json.optJSONObject("link")?.optString("url")),
                linkTitle = json.optJSONObject("link")?.optString("title")?.takeIf { it.isNotBlank() },
                wakeAt = json.optString("wakeAt").takeIf { it.isNotBlank() },
                helpers = json.optInt("helpers"),
                tokens = json.optJSONObject("usage")?.optLong("tokens") ?: 0,
                cost = json.optJSONObject("usage")?.optDouble("cost")?.takeIf { !it.isNaN() } ?: 0.0,
                oldHooks = !json.optBoolean("hooksCurrent"),
                doing = json.optJSONObject("doing")?.let { doing(it.optString("tool"), it.optString("detail")) },
                work = json.optJSONObject("work")?.let {
                    work(it.optString("since"), it.optString("endedAt"), it.optInt("files", -1), it.optInt("added", -1), it.optInt("removed", -1))
                },
            )
        }

        fun listToJson(lists: List<TaskList>): String = JSONArray(lists.map { it.toJson() }).toString()

        fun listFromJson(text: String): List<TaskList> {
            val array = JSONArray(text)
            return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::fromJson) }
        }
    }
}

/** Remembers the newest seq per session, so a late, older push never overwrites a newer one. */
class SeqGate {
    private val newest = HashMap<String, Long>()

    @Synchronized
    fun accept(list: TaskList): Boolean {
        val last = newest[list.key]
        if (last != null && list.seq <= last) return false
        newest[list.key] = list.seq
        return true
    }
}
