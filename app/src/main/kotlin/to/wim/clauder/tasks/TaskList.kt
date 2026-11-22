package to.wim.clauder.tasks

import org.json.JSONArray
import org.json.JSONObject

enum class TaskStatus { Pending, InProgress, Completed }

/** Running: Claude is working. Idle: Claude finished its turn and nothing runs. Waiting: Claude
 * asked you something (a decision or a permission). Done: every task is completed. Ended: the
 * session closed. */
enum class SessionState { Running, Idle, Waiting, Done, Ended }

data class TaskItem(val id: String, val subject: String, val status: TaskStatus)

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
