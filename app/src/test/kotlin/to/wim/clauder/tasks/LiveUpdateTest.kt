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
}
