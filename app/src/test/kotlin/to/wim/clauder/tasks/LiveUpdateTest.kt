package to.wim.clauder.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdateTest {
    private fun list(state: SessionState, vararg statuses: TaskStatus, url: String? = "https://claude.ai/code/session_abc") = TaskList(
        key = "k", title = "clauder", url = url, state = state, seq = 1, updatedAt = "t",
        tasks = statuses.mapIndexed { i, s -> TaskItem("${i + 1}", "task ${i + 1}", s) },
    )

    @Test
    fun `one segment per task, the done ones filled and a point on the active one`() {
        val update = LiveUpdate.from(list(SessionState.Running, TaskStatus.Completed, TaskStatus.Completed, TaskStatus.InProgress, TaskStatus.Pending))!!
        assertEquals(LiveUpdate.Status.Tasks, update.status)
        assertEquals(4, update.segmentCount)
        assertEquals(400, update.max)
        assertEquals(200, update.progress)
        assertEquals(250, update.pointPosition)
        assertEquals(2, update.done)
        assertEquals(4, update.total)
        assertEquals("task 3", update.currentTask)
        assertEquals("task 4", update.nextTask)
        assertTrue(update.ongoing)
        assertTrue(update.requestPromotion)
        assertNull(update.timeoutMs)
    }

    @Test
    fun `done fills the bar and dismisses itself after a few minutes`() {
        val update = LiveUpdate.from(list(SessionState.Done, TaskStatus.Completed, TaskStatus.Completed))!!
        assertEquals(LiveUpdate.Status.Done, update.status)
        assertEquals(update.max, update.progress)
        assertNull(update.pointPosition)
        assertFalse(update.ongoing)
        assertFalse(update.requestPromotion)
        assertEquals(LiveUpdate.DONE_TIMEOUT_MS, update.timeoutMs)
    }

    @Test
    fun `with the summary on, waiting for you is a plain notification that can be swiped away`() {
        val update = LiveUpdate.from(list(SessionState.Waiting, TaskStatus.Pending).copy(project = "Clauder"), summaryMode = true)!!
        assertEquals(LiveUpdate.Status.Waiting, update.status)
        assertFalse(update.ongoing)
        assertFalse(update.requestPromotion)
        assertFalse(update.silent)
        // No progress bar or chip, so it doesn't look like a second Live Update.
        assertTrue(update.plain)
        // Not tucked into the shade's bundle of inactive sessions.
        assertNull(update.bundle)
    }

    @Test
    fun `the summary setting leaves working sessions live`() {
        val update = LiveUpdate.from(list(SessionState.Running, TaskStatus.InProgress), summaryMode = true)!!
        assertFalse(update.plain)
        assertTrue(update.ongoing)
        assertTrue(update.requestPromotion)
    }

    @Test
    fun `without the summary, waiting for you keeps its progress bar`() {
        assertFalse(LiveUpdate.from(list(SessionState.Waiting, TaskStatus.Pending))!!.plain)
    }

    @Test
    fun `waiting for you stays ongoing`() {
        val update = LiveUpdate.from(list(SessionState.Waiting, TaskStatus.Completed, TaskStatus.Pending))!!
        assertEquals(LiveUpdate.Status.Waiting, update.status)
        assertTrue(update.ongoing)
        assertNull(update.pointPosition)
    }

    @Test
    fun `nothing to show without tasks or when the session ended with open tasks`() {
        assertNull(LiveUpdate.from(list(SessionState.Running)))
        assertNull(LiveUpdate.from(list(SessionState.Ended, TaskStatus.Completed, TaskStatus.Pending)))
        assertEquals(LiveUpdate.Status.Done, LiveUpdate.from(list(SessionState.Ended, TaskStatus.Completed))!!.status)
    }

    @Test
    fun `taps open the session, or Claude Code when the link is missing or foreign`() {
        assertEquals("https://claude.ai/code/session_abc", LiveUpdate.from(list(SessionState.Running, TaskStatus.Pending))!!.openUrl)
        assertEquals(LiveUpdate.CODE_URL, LiveUpdate.from(list(SessionState.Running, TaskStatus.Pending, url = null))!!.openUrl)
        assertEquals(LiveUpdate.CODE_URL, LiveUpdate.from(list(SessionState.Running, TaskStatus.Pending, url = "https://evil.example/"))!!.openUrl)
        assertEquals(LiveUpdate.CODE_URL, LiveUpdate.from(list(SessionState.Running, TaskStatus.Pending, url = "https://claude.ai.evil.example/"))!!.openUrl)
    }

    @Test
    fun `a project thread counts its steps, not its tasks`() {
        val steps = listOf(TaskItem("plan:1", "One", TaskStatus.Completed), TaskItem("plan:2", "Two", TaskStatus.InProgress))
        val update = LiveUpdate.from(list(SessionState.Running, TaskStatus.Pending, TaskStatus.Pending, TaskStatus.Pending).copy(steps = steps))!!
        assertTrue(update.steps)
        assertEquals(2, update.total)
        assertEquals(1, update.done)
        assertEquals("Two", update.currentTask)
    }

    @Test
    fun `an idle session stays in the shade without being pinned or promoted`() {
        val update = LiveUpdate.from(list(SessionState.Idle, TaskStatus.Completed, TaskStatus.Pending))!!
        assertEquals(LiveUpdate.Status.Idle, update.status)
        assertFalse(update.ongoing)
        assertFalse(update.requestPromotion)
        assertNull(update.timeoutMs)
    }

    @Test
    fun `the notification names the session's project`() {
        assertEquals("Clauder", LiveUpdate.from(list(SessionState.Running, TaskStatus.Pending).copy(project = "Clauder"))!!.group)
        assertNull(LiveUpdate.from(list(SessionState.Running, TaskStatus.Pending))!!.group)
    }

    @Test
    fun `idle and done sessions are bundled per project, live ones stand alone`() {
        assertEquals("inactive:Clauder", LiveUpdate.from(list(SessionState.Idle, TaskStatus.Pending).copy(project = "Clauder"))!!.bundle)
        assertEquals("inactive:", LiveUpdate.from(list(SessionState.Done, TaskStatus.Completed))!!.bundle)
        assertNull(LiveUpdate.from(list(SessionState.Running, TaskStatus.Pending).copy(project = "Clauder"))!!.bundle)
        assertNull(LiveUpdate.from(list(SessionState.Waiting, TaskStatus.Pending))!!.bundle)
    }

    @Test
    fun `waiting shows what Claude asks, other states don't`() {
        assertEquals("Merge?", LiveUpdate.from(list(SessionState.Waiting, TaskStatus.Pending).copy(need = "Merge?"))!!.need)
        assertNull(LiveUpdate.from(list(SessionState.Running, TaskStatus.Pending).copy(need = "Merge?"))!!.need)
        assertNull(LiveUpdate.from(list(SessionState.Waiting, TaskStatus.Pending))!!.need)
    }

    @Test
    fun `the turn summary leads, unless a waiting session asks something`() {
        val running = list(SessionState.Running, TaskStatus.InProgress).copy(summary = "PR #5 open, CI running")
        assertEquals("PR #5 open, CI running", LiveUpdate.from(running)!!.headline)
        assertEquals("Merged", LiveUpdate.from(list(SessionState.Done, TaskStatus.Completed).copy(summary = "Merged"))!!.headline)
        val waiting = list(SessionState.Waiting, TaskStatus.Pending).copy(summary = "Mockups ready")
        assertEquals("Mockups ready", LiveUpdate.from(waiting)!!.headline)
        assertNull(LiveUpdate.from(waiting.copy(need = "Pick T1, T2 or T3"))!!.headline)
        assertNull(LiveUpdate.from(list(SessionState.Running, TaskStatus.InProgress))!!.headline)
    }
}
