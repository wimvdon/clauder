package to.wim.clauder.tasks

import org.json.JSONArray
import org.json.JSONObject

enum class TaskStatus { Pending, InProgress, Completed }

/** Running: Claude is working. Waiting: Claude stopped and waits for you. Done: every task is
 * completed. Ended: the session closed. */
enum class SessionState { Running, Waiting, Done, Ended }

data class TaskItem(val id: String, val subject: String, val status: TaskStatus)

/** One Claude session's task list, as the Worker sends it. */
data class TaskList(
    val key: String,
    val title: String,
    val url: String?,
    val state: SessionState,
    val seq: Long,
    val updatedAt: String,
    val tasks: List<TaskItem>,
) {
    val doneCount: Int get() = tasks.count { it.status == TaskStatus.Completed }
    val current: TaskItem? get() = tasks.firstOrNull { it.status == TaskStatus.InProgress }
    val next: TaskItem?
        get() {
            val currentIndex = tasks.indexOfFirst { it.status == TaskStatus.InProgress }
            return tasks.withIndex().firstOrNull { (i, t) -> i > currentIndex && t.status == TaskStatus.Pending }?.value
        }
    val isFinished: Boolean get() = state == SessionState.Done || state == SessionState.Ended

    companion object {
        private fun status(value: String?): TaskStatus = when (value) {
            "in_progress" -> TaskStatus.InProgress
            "completed" -> TaskStatus.Completed
            else -> TaskStatus.Pending
        }

        private fun state(value: String?): SessionState = when (value) {
            "waiting" -> SessionState.Waiting
            "done" -> SessionState.Done
            "ended" -> SessionState.Ended
            else -> SessionState.Running
        }

        /** Parses an FCM data message from the Worker. Returns null for anything else. */
        fun fromPushData(data: Map<String, String>): TaskList? {
            if (data["type"] != "tasklist") return null
            val key = data["key"]?.takeIf { it.isNotBlank() } ?: return null
            val tasks = try {
                val array = JSONArray(data["tasks"] ?: "[]")
                (0 until array.length()).map { i ->
                    val t = array.getJSONObject(i)
                    TaskItem(t.getString("i"), t.optString("s", ""), status(t.optString("t")))
                }
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
            )
        }

        /** Parses one entry of the Worker's GET /sessions answer. */
        fun fromJson(json: JSONObject): TaskList? {
            val key = json.optString("key").takeIf { it.isNotBlank() } ?: return null
            val array = json.optJSONArray("tasks") ?: JSONArray()
            val tasks = (0 until array.length()).mapNotNull { i ->
                val t = array.optJSONObject(i) ?: return@mapNotNull null
                TaskItem(t.optString("id"), t.optString("subject"), status(t.optString("status")))
            }
            return TaskList(
                key = key,
                title = json.optString("title").ifBlank { "Claude Code" },
                url = json.optString("url").takeIf { it.isNotBlank() && it != "null" },
                state = state(json.optString("state")),
                seq = json.optLong("seq"),
                updatedAt = json.optString("updatedAt"),
                tasks = tasks,
            )
        }

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
