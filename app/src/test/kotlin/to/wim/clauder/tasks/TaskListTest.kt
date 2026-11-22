package to.wim.clauder.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskListTest {
    private fun push(seq: Long = 3, state: String = "running", tasks: String = TASKS) = mapOf(
        "type" to "tasklist", "key" to "cse_abc", "title" to "clauder",
        "url" to "https://claude.ai/code/session_abc", "state" to state, "seq" to "$seq",
        "updatedAt" to "2026-11-22T10:00:00.000Z", "tasks" to tasks,
    )

    @Test
    fun `parses the Worker's push`() {
        val list = TaskList.fromPushData(push())!!
        assertEquals("cse_abc", list.key)
        assertEquals("clauder", list.title)
        assertEquals("https://claude.ai/code/session_abc", list.url)
        assertEquals(SessionState.Running, list.state)
        assertEquals(3L, list.seq)
        assertEquals(listOf(TaskStatus.Completed, TaskStatus.InProgress, TaskStatus.Pending), list.tasks.map { it.status })
        assertEquals(1, list.doneCount)
        assertEquals("Write tests", list.current?.subject)
        assertEquals("Open the PR", list.next?.subject)
    }

    @Test
    fun `ignores other messages and broken task lists`() {
        assertNull(TaskList.fromPushData(mapOf("type" to "other", "key" to "k")))
        assertNull(TaskList.fromPushData(push() - "key"))
        assertNull(TaskList.fromPushData(push(tasks = "not json")))
    }

    @Test
    fun `an empty url and title fall back`() {
        val list = TaskList.fromPushData(push() + mapOf("url" to "", "title" to ""))!!
        assertNull(list.url)
        assertEquals("Claude Code", list.title)
    }

    @Test
    fun `parses the sessions list`() {
        val json = """[{"key":"cse_1","title":"a","url":null,"state":"waiting","seq":7,"updatedAt":"t",
            "tasks":[{"id":"1","subject":"x","status":"completed"}]}]"""
        val list = TaskList.listFromJson(json).single()
        assertEquals(SessionState.Waiting, list.state)
        assertNull(list.url)
        assertEquals(7L, list.seq)
        assertEquals(TaskItem("1", "x", TaskStatus.Completed), list.tasks.single())
    }

    @Test
    fun `next is the first pending task after the current one`() {
        val list = TaskList.fromPushData(push(tasks = """[{"i":"1","s":"a","t":"pending"},{"i":"2","s":"b","t":"in_progress"},{"i":"3","s":"c","t":"pending"}]"""))!!
        assertEquals("c", list.next?.subject)
    }

    @Test
    fun `the seq gate drops older and repeated pushes per session`() {
        val gate = SeqGate()
        assertTrue(gate.accept(TaskList.fromPushData(push(seq = 3))!!))
        assertFalse(gate.accept(TaskList.fromPushData(push(seq = 3))!!))
        assertFalse(gate.accept(TaskList.fromPushData(push(seq = 2))!!))
        assertTrue(gate.accept(TaskList.fromPushData(push(seq = 4))!!))
        assertTrue(gate.accept(TaskList.fromPushData(push(seq = 1) + ("key" to "other"))!!))
    }

    companion object {
        const val TASKS = """[{"i":"1","s":"Read the code","t":"completed"},{"i":"2","s":"Write tests","t":"in_progress"},{"i":"3","s":"Open the PR","t":"pending"}]"""
    }
}
