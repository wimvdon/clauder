package to.wim.clauder.claudeai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.wim.clauder.tasks.SessionState
import to.wim.clauder.tasks.TaskItem
import to.wim.clauder.tasks.TaskList
import to.wim.clauder.tasks.TaskStatus

class RemoteThreadsTest {
    private val now = java.time.Instant.parse("2026-11-23T12:00:00Z").toEpochMilli()
    private val project = RemoteProject("chan_1", "Clauder", "2026-11-23T11:00:00Z")

    private val projects = """{"data":[
        {"id":"chan_1","name":"Clauder","last_activity_at":"2026-11-23T11:00:00Z","archived_at":null},
        {"id":"chan_2","name":"Old","last_activity_at":"2026-10-01T11:00:00Z","archived_at":null},
        {"id":"chan_3","name":"Gone","last_activity_at":"2026-11-23T11:00:00Z","archived_at":"2026-11-23T10:00:00Z"}
    ],"next_cursor":null}"""

    private fun root(id: String, status: String, session: String?, extra: String = "") = """{"id":"$id","thread_status":"$status",
        "thread_waits_on_live_prompt":false,"thread_any_session_working":false,"created_at":"2026-11-23T10:00:00.123456Z",
        "bound_sessions":[${session?.let { """{"id":"$it","status":"idle","status_bucket":"completed","title":"Title $id"}""" } ?: ""}]$extra}"""

    private val threads = """{"sections":[
        {"status":"needs_input","count":1,"data":[${root("t1", "needs_input", "session_A")}]},
        {"status":"resolved","count":1,"data":[${root("t2", "resolved", "session_B")}]},
        {"status":"working","count":1,"data":[${root("t3", "working", null, ",\"thread_preview\":\"Preview\"")}]},
        {"status":"idle","count":1,"data":[${root("t4", "idle", "session_D")}]}
    ],"items":[],"rows_by_id":{},"truncated":false}"""

    private fun hook(key: String, state: SessionState, updatedAt: String = "2026-11-23T11:30:00Z") = TaskList(
        key = key, title = "Hook $key", url = null, state = state, seq = 1, updatedAt = updatedAt,
        tasks = listOf(TaskItem("1", "a", TaskStatus.Pending)),
    )

    @Test
    fun `recent projects that aren't archived are read`() {
        assertEquals(listOf("chan_1"), RemoteThreads.projects(projects, now).map { it.id })
        assertEquals(emptyList<RemoteProject>(), RemoteThreads.projects("not json", now))
    }

    @Test
    fun `thread roots from every section, with their sessions and state`() {
        val list = RemoteThreads.threads(threads, project)
        assertEquals(listOf("t1", "t2", "t3", "t4"), list.map { it.id })
        assertEquals(listOf(SessionState.Waiting, SessionState.Done, SessionState.Running, SessionState.Idle), list.map { it.state })
        assertEquals(listOf("session_A"), list[0].sessionIds)
        assertEquals("Title t1", list[0].title)
        assertEquals("Preview", list[2].title)
        assertEquals("2026-11-23T10:00:00.123456Z", list[0].updatedAt)
    }

    @Test
    fun `a waiting flag or a working session wins over idle`() {
        val waiting = RemoteThread("x", project, null, "idle", waitsOnPrompt = true, anyWorking = false, sessionIds = emptyList(), updatedAt = null)
        assertEquals(SessionState.Waiting, waiting.state)
        assertEquals(SessionState.Running, waiting.copy(waitsOnPrompt = false, anyWorking = true).state)
        assertNull(waiting.copy(status = "new_kind", waitsOnPrompt = false).state)
        assertEquals(SessionState.Ended, waiting.copy(status = "archived").state)
    }

    @Test
    fun `hook sessions take the thread's state, a thread resolved by hand included`() {
        val fetchedAt = java.time.Instant.parse("2026-11-23T11:45:00Z").toEpochMilli()
        val lists = listOf(hook("session_B", SessionState.Waiting), hook("cse_A", SessionState.Idle), hook("other", SessionState.Running))
        val out = RemoteThreads.overlay(lists, RemoteThreads.threads(threads, project), fetchedAt).associateBy { it.key }
        assertEquals(SessionState.Done, out.getValue("session_B").state)
        assertEquals(SessionState.Waiting, out.getValue("cse_A").state)
        assertEquals("Clauder", out.getValue("cse_A").project)
        assertEquals(SessionState.Running, out.getValue("other").state)
    }

    @Test
    fun `a hook that reported after the fetch keeps its own state`() {
        val fetchedAt = java.time.Instant.parse("2026-11-23T11:00:00Z").toEpochMilli()
        val out = RemoteThreads.overlay(listOf(hook("session_B", SessionState.Running)), RemoteThreads.threads(threads, project), fetchedAt)
        assertEquals(SessionState.Running, out.first { it.key == "session_B" }.state)
    }

    @Test
    fun `threads without hooks are added only while they wait or work`() {
        val out = RemoteThreads.overlay(emptyList(), RemoteThreads.threads(threads, project), now)
        assertEquals(setOf("claudeai:t1", "claudeai:t3"), out.map { it.key }.toSet())
        val t1 = out.first { it.key == "claudeai:t1" }
        assertEquals(SessionState.Waiting, t1.state)
        assertEquals("https://claude.ai/code/session_A", t1.url)
        assertEquals("Clauder", t1.project)
        assertTrue(t1.items.isEmpty())
        assertTrue(RemoteThreads.isRemoteOnly(t1))
        assertNull(out.first { it.key == "claudeai:t3" }.url)
        assertFalse(RemoteThreads.isRemoteOnly(hook("session_B", SessionState.Idle)))
    }

    @Test
    fun `sync tries the kept organization, then the list, and keeps the one that answers`() = runBlocking {
        val asked = mutableListOf<String>()
        val calls = object : ClaudeAiCalls {
            override var organization: String? = null
            override suspend fun get(path: String): CallResult {
                asked += "$organization $path"
                return when {
                    path == "api/organizations" -> CallResult(200, """[{"uuid":"$OLD"},{"uuid":"$GOOD"}]""")
                    path.startsWith("v1/code/channels?") -> if (organization == GOOD) CallResult(200, projects) else CallResult(400, "{}")
                    path.startsWith("v1/code/channels/chan_1/threads") -> CallResult(200, threads)
                    else -> CallResult(404, "{}")
                }
            }
            override suspend fun stream(path: String, millis: Long) = CallResult(404, "")
        }
        val snapshot = ClaudeAiSync.read(calls, OLD, now)!!
        assertEquals(GOOD, snapshot.organization)
        assertEquals(4, snapshot.threads.size)
        assertEquals(now, snapshot.fetchedAtMillis)
        assertEquals(
            listOf(
                "$OLD v1/code/channels?scope=joined&limit=20",
                "$OLD api/organizations",
                "$GOOD v1/code/channels?scope=joined&limit=20",
                "$GOOD v1/code/channels/chan_1/threads?limit=20",
            ),
            asked,
        )
    }

    @Test
    fun `no organization answering means no snapshot`() = runBlocking {
        val calls = object : ClaudeAiCalls {
            override var organization: String? = null
            override suspend fun get(path: String) = CallResult(401, "{}")
            override suspend fun stream(path: String, millis: Long) = CallResult(401, "")
        }
        assertNull(ClaudeAiSync.read(calls, null, now))
    }

    private companion object {
        const val OLD = "11111111-2222-4333-8444-555555555555"
        const val GOOD = "0b2c6a1e-3f4d-4e5a-9b8c-7d6e5f4a3b2c"
    }
}
