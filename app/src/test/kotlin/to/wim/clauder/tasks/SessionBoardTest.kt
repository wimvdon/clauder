package to.wim.clauder.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionBoardTest {
    private fun list(key: String, seq: Long, updatedAt: String, tasks: Int = 1) = TaskList(
        key, key, null, SessionState.Running, seq, updatedAt, List(tasks) { TaskItem("$it", "t", TaskStatus.Pending) },
    )

    @Test
    fun `newest session first, a newer copy replaces an older one`() {
        var board = SessionBoard.merge(emptyList(), list("a", 1, "2026-11-22T10:00:00Z"))
        board = SessionBoard.merge(board, list("b", 1, "2026-11-22T10:05:00Z"))
        assertEquals(listOf("b", "a"), board.map { it.key })
        board = SessionBoard.merge(board, list("a", 2, "2026-11-22T10:10:00Z"))
        assertEquals(listOf("a", "b"), board.map { it.key })
        assertEquals(2L, board.first().seq)
    }

    @Test
    fun `an older copy from the Worker doesn't overwrite a newer push`() {
        val board = SessionBoard.merge(emptyList(), list("a", 5, "2026-11-22T10:10:00Z"))
        val merged = SessionBoard.mergeAll(board, listOf(list("a", 4, "2026-11-22T10:00:00Z"), list("c", 1, "2026-11-22T09:00:00Z")))
        assertEquals(listOf("a" to 5L, "c" to 1L), merged.map { it.key to it.seq })
    }

    @Test
    fun `sessions without tasks and the test list are left out`() {
        val board = SessionBoard.mergeAll(emptyList(), listOf(list("a", 1, "t", tasks = 0), list("demo", 1, "t")))
        assertEquals(emptyList<TaskList>(), board)
    }
}
