package to.wim.clauder.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** API errors, the model and the non-tool "now" lines the Worker sends since hooks level L4. */
class HookEventsTest {
    private fun push(extra: Map<String, String>) = TaskList.fromPushData(
        mapOf(
            "type" to "tasklist", "key" to "cse_1", "title" to "clauder", "state" to "waiting", "seq" to "5",
            "updatedAt" to "2026-11-23T10:00:00.000Z", "tasks" to """[{"i":"1","s":"Build","t":"in_progress"}]""",
        ) + extra,
    )!!

    @Test
    fun `an API error is read from the push and marks the session stopped by an error`() {
        val list = push(mapOf("error" to "rate_limit", "need" to "API Error: Rate limit reached"))
        assertEquals("rate_limit", list.error)
        assertTrue(list.stoppedByError)
        val update = LiveUpdate.from(list, nowMillis = 0)!!
        assertTrue(update.error)
        assertEquals("API Error: Rate limit reached", update.need)
        assertFalse(push(emptyMap()).stoppedByError)
        assertFalse(LiveUpdate.from(push(emptyMap()), nowMillis = 0)!!.error)
    }

    @Test
    fun `an error only counts while the session waits`() {
        assertFalse(push(mapOf("error" to "rate_limit", "state" to "running")).stoppedByError)
    }

    @Test
    fun `model, effort and mode survive the cache`() {
        val list = push(mapOf("model" to "claude-opus-5-5[1m]", "effort" to "high", "mode" to "plan", "error" to "server_error"))
        val back = TaskList.listFromJson(TaskList.listToJson(listOf(list))).single()
        assertEquals("claude-opus-5-5[1m]", back.model)
        assertEquals("high", back.effort)
        assertEquals("plan", back.mode)
        assertEquals("server_error", back.error)
        val plain = TaskList.listFromJson(TaskList.listToJson(listOf(push(emptyMap())))).single()
        assertNull(plain.model)
        assertNull(plain.error)
    }

    @Test
    fun `model ids read as words`() {
        assertEquals("Opus 5.5", ModelName.readable("claude-opus-5-5[1m]"))
        assertEquals("Sonnet 5.5", ModelName.readable("claude-sonnet-5-5"))
        assertEquals("Fable 5.2", ModelName.readable("claude-fable-5-2"))
        assertEquals("Sonnet 3.5", ModelName.readable("claude-3-5-sonnet-20241022"))
        assertEquals("Haiku 4", ModelName.readable("claude-haiku-4-20250101"))
        // Anything else is shown as it is.
        assertEquals("gpt-x-mini", ModelName.readable("gpt-x-mini"))
        assertEquals("opus", ModelName.readable("opus"))
    }

    @Test
    fun `the Worker's non-tool lines have their own kinds`() {
        assertEquals(Doing.Kind.Blocked, Doing(Doing.BLOCKED, "Irreversible Local Destruction").kind)
        assertEquals("Irreversible Local Destruction", Doing(Doing.BLOCKED, "Irreversible Local Destruction").subject)
        assertEquals(Doing.Kind.Blocked, Doing(Doing.BLOCKED, null).kind)
        assertEquals(Doing.Kind.Compacting, Doing(Doing.COMPACTING, null).kind)
        assertEquals(Doing.Kind.PushFailed, Doing(Doing.PUSH_FAILED, null).kind)
        assertEquals(Doing.Kind.Shell, Doing("Bash", "Run tests").kind)
    }
}
