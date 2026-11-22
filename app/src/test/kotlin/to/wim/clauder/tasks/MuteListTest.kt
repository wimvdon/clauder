package to.wim.clauder.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

class MuteListTest {
    private fun list(key: String, state: SessionState) =
        TaskList(key, key, null, state, 1, "2026-11-22T10:00:00Z", listOf(TaskItem("1", "t", TaskStatus.Pending)))

    @Test
    fun `mute and unmute a session`() {
        val muted = MuteList.mute(emptySet(), "a")
        assertEquals(setOf("a"), muted)
        assertEquals(emptySet<String>(), MuteList.unmute(muted, "a"))
    }

    @Test
    fun `a muted session stays muted while it runs and is forgotten when it closes`() {
        val muted = setOf("a", "b")
        assertEquals(muted, MuteList.afterPush(muted, list("a", SessionState.Waiting)))
        assertEquals(setOf("b"), MuteList.afterPush(muted, list("a", SessionState.Ended)))
    }
}
