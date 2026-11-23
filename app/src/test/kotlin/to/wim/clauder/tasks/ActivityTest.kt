package to.wim.clauder.tasks

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityTest {
    private fun list(state: SessionState, doing: Doing? = null, work: WorkReceipt? = null) = TaskList(
        key = "k", title = "clauder", url = null, state = state, seq = 1, updatedAt = "t",
        tasks = listOf(TaskItem("1", "a", if (state == SessionState.Done) TaskStatus.Completed else TaskStatus.InProgress)),
        doing = doing, work = work,
    )

    @Test
    fun `each tool is worded by its kind, and a connector's tool by the connector`() {
        assertEquals(Doing.Kind.Shell, Doing("Bash", "Run the tests").kind)
        assertEquals(Doing.Kind.Edit, Doing("MultiEdit", "TaskList.kt").kind)
        assertEquals(Doing.Kind.Search, Doing("Grep", "parseChecklist").kind)
        assertEquals(Doing.Kind.Helper, Doing("Agent", "Review the diff").kind)
        assertEquals("TaskList.kt", Doing("Edit", "TaskList.kt").subject)
        val connector = Doing("mcp__github__create_pull_request", null)
        assertEquals(Doing.Kind.Other, connector.kind)
        assertEquals("github", connector.subject)
        // Without a detail, a known tool is named rather than worded with an empty subject.
        assertEquals(Doing.Kind.Other, Doing("Bash", null).kind)
        assertEquals("Bash", Doing("Bash", null).subject)
    }

    @Test
    fun `the receipt counts whole minutes from the person's message to the turn's end`() {
        val receipt = WorkReceipt("2026-11-23T09:00:00.000Z", "2026-11-23T09:12:59.000Z", 3, 120, 30)
        assertEquals(12L, receipt.minutes)
        assertNull(WorkReceipt("later", "2026-11-23T09:12:59.000Z", 0, 0, 0).minutes)
        assertTrue(WorkReceipt("later", "x", 0, 0, 0).isEmpty)
    }

    @Test
    fun `the tool shows while working and the receipt once finished`() {
        val doing = Doing("Read", "README.md")
        val work = WorkReceipt("2026-11-23T09:00:00.000Z", "2026-11-23T09:10:00.000Z", 1, 2, 0)
        assertEquals(doing, LiveUpdate.from(list(SessionState.Running, doing, work))!!.doing)
        assertNull(LiveUpdate.from(list(SessionState.Running, doing, work))!!.receipt)
        assertNull(LiveUpdate.from(list(SessionState.Waiting, doing, work))!!.doing)
        assertNull(LiveUpdate.from(list(SessionState.Waiting, doing, work))!!.receipt)
        assertEquals(work, LiveUpdate.from(list(SessionState.Done, doing, work))!!.receipt)
        assertNull(LiveUpdate.from(list(SessionState.Done, doing, work))!!.doing)
    }

    @Test
    fun `the tool and the receipt come from a push and survive the cache`() {
        val pushed = TaskList.fromPushData(
            mapOf(
                "type" to "tasklist", "key" to "cse_1", "state" to "done", "seq" to "5", "tasks" to "[]", "steps" to "[]",
                "doing" to """{"t":"Edit","d":"Push.kt"}""",
                "work" to """{"s":"2026-11-23T09:00:00.000Z","e":"2026-11-23T09:30:00.000Z","f":4,"a":50,"r":7}""",
            ),
        )!!
        assertEquals(Doing("Edit", "Push.kt"), pushed.doing)
        assertEquals(WorkReceipt("2026-11-23T09:00:00.000Z", "2026-11-23T09:30:00.000Z", 4, 50, 7), pushed.work)
        val cached = TaskList.fromJson(JSONObject(pushed.toJson().toString()))!!
        assertEquals(pushed.doing, cached.doing)
        assertEquals(pushed.work, cached.work)
        val empty = TaskList.fromPushData(mapOf("type" to "tasklist", "key" to "cse_1", "doing" to "", "work" to "{bad"))!!
        assertNull(empty.doing)
        assertNull(empty.work)
    }
}
