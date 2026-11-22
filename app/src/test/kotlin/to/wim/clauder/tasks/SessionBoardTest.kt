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

    @Test
    fun `split puts finished sessions apart`() {
        val running = list("a", 1, "2026-11-22T10:00:00Z")
        val done = list("b", 1, "2026-11-22T11:00:00Z").copy(state = SessionState.Done)
        val ended = list("c", 1, "2026-11-22T09:00:00Z").copy(state = SessionState.Ended)
        val (active, finished) = SessionBoard.split(SessionBoard.sort(listOf(running, done, ended)))
        assertEquals(listOf("a"), active.map { it.key })
        assertEquals(listOf("b", "c"), finished.map { it.key })
    }

    @Test
    fun `keep holds only the newest sessions`() {
        val lists = (1..25).map { list("s$it", 1, "2026-11-22T10:%02d:00Z".format(it)) }
        val kept = SessionBoard.keep(lists)
        assertEquals(SessionBoard.MAX_KEPT, kept.size)
        assertEquals("s25", kept.first().key)
        assertEquals("s6", kept.last().key)
    }

    @Test
    fun `groups keep the order sessions come in, with ungrouped ones last`() {
        val a = list("a", 1, "3").copy(project = "Clauder")
        val b = list("b", 1, "2").copy(repo = "noter")
        val c = list("c", 1, "1")
        val d = list("d", 1, "0").copy(project = "Clauder", repo = "clauder")
        assertEquals(
            listOf("Clauder" to listOf(a, d), "noter" to listOf(b), null to listOf(c)),
            SessionBoard.groups(listOf(a, b, c, d)),
        )
        assertEquals(listOf("Clauder" to listOf(a)), SessionBoard.groups(listOf(a)))
    }

    @Test
    fun `sections keep idle and finished sessions in their project, apart from the active ones`() {
        val running = list("a", 1, "5").copy(project = "Noter")
        val idle = list("b", 1, "4").copy(project = "Clauder", state = SessionState.Idle)
        val done = list("c", 1, "3").copy(project = "Noter", state = SessionState.Done)
        val waiting = list("d", 1, "2").copy(project = "Clauder", state = SessionState.Waiting)
        val loose = list("e", 1, "1").copy(state = SessionState.Ended)
        val onlyIdle = list("f", 1, "6").copy(repo = "rss", state = SessionState.Idle)
        assertEquals(
            listOf(
                SessionBoard.Section("Noter", listOf(running), listOf(done)),
                SessionBoard.Section("Clauder", listOf(waiting), listOf(idle)),
                SessionBoard.Section("rss", emptyList(), listOf(onlyIdle)),
                SessionBoard.Section(null, emptyList(), listOf(loose)),
            ),
            SessionBoard.sections(listOf(onlyIdle, running, idle, done, waiting, loose)),
        )
    }
}
