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

    @Test
    fun `a project thread's steps come apart from its tasks and drive the progress`() {
        val steps = """[{"i":"plan:1","s":"Shortcuts merged","t":"completed"},{"i":"plan:2","s":"Build widgets","t":"in_progress"}]"""
        val list = TaskList.fromPushData(push() + ("steps" to steps))!!
        assertTrue(list.hasSteps)
        assertEquals(listOf("Shortcuts merged", "Build widgets"), list.steps.map { it.subject })
        assertEquals(3, list.tasks.size)
        assertEquals(list.steps, list.items)
        assertEquals(1, list.doneCount)
        assertEquals("Build widgets", list.current?.subject)
        assertNull(list.next)
    }

    @Test
    fun `without steps the tasks drive the progress`() {
        val list = TaskList.fromPushData(push())!!
        assertFalse(list.hasSteps)
        assertEquals(list.tasks, list.items)
    }

    @Test
    fun `the saved copy reads back the same`() {
        val list = TaskList(
            "cse_1", "Fix login", "https://claude.ai/code/session_1", SessionState.Waiting, 7, "2026-11-22T10:00:00Z",
            listOf(
                TaskItem("1", "Read the code", TaskStatus.Completed),
                TaskItem("2", "Write a test", TaskStatus.InProgress),
                TaskItem("3", "Open a PR", TaskStatus.Pending),
            ),
        )
        val noUrl = list.copy(
            key = "cse_2",
            url = null,
            state = SessionState.Ended,
            steps = listOf(TaskItem("plan:1", "Plan", TaskStatus.Completed)),
        )
        assertEquals(listOf(list, noUrl), TaskList.listFromJson(TaskList.listToJson(listOf(list, noUrl))))
    }
}
