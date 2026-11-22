package to.wim.clauder.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

class ChipTest {
    private fun list(key: String, state: SessionState, vararg statuses: TaskStatus) = TaskList(
        key = key, title = key, url = "https://claude.ai/code/session_$key", state = state, seq = 1, updatedAt = "t",
        tasks = statuses.mapIndexed { i, s -> TaskItem("${i + 1}", "task ${i + 1}", s) },
    )

    @Test
    fun `the summary chip names how many wait for you when any do`() {
        val summary = Summary(working = 2, waiting = 1, active = listOf(list("a", SessionState.Waiting), list("b", SessionState.Running), list("c", SessionState.Running)))
        assertEquals(Chip.Waiting(1), Chip.forSummary(summary))
    }

    @Test
    fun `otherwise the summary chip says how many are working`() {
        val summary = Summary(working = 1, waiting = 0, active = listOf(list("a", SessionState.Running)))
        assertEquals(Chip.Working(1), Chip.forSummary(summary))
    }

    @Test
    fun `a session's chip shows its steps while working`() {
        val update = LiveUpdate.from(list("a", SessionState.Running, TaskStatus.Completed, TaskStatus.InProgress, TaskStatus.Pending))!!
        assertEquals(Chip.Steps(1, 3), Chip.forSession(update))
    }

    @Test
    fun `a session's chip says it waits for you, or that it is done`() {
        val waiting = LiveUpdate.from(list("a", SessionState.Waiting, TaskStatus.Completed, TaskStatus.Pending))!!
        assertEquals(Chip.WaitsForYou, Chip.forSession(waiting))
        val done = LiveUpdate.from(list("a", SessionState.Done, TaskStatus.Completed))!!
        assertEquals(Chip.Done, Chip.forSession(done))
    }
}
