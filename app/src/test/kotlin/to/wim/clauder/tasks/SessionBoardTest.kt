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
    fun `the board splits sessions into needs input, running and inactive, each grouped by project`() {
        val running = list("a", 1, "5").copy(project = "Noter")
        val idle = list("b", 1, "4").copy(project = "Clauder", state = SessionState.Idle)
        val done = list("c", 1, "3").copy(project = "Noter", state = SessionState.Done)
        val waiting = list("d", 1, "2").copy(project = "Clauder", state = SessionState.Waiting)
        val loose = list("e", 1, "1").copy(state = SessionState.Ended)
        val onlyIdle = list("f", 1, "6").copy(repo = "rss", state = SessionState.Idle)
        val alsoRunning = list("g", 1, "0").copy(project = "Clauder")
        assertEquals(
            SessionBoard.Board(
                needsInput = listOf(SessionBoard.Section("Clauder", listOf(waiting))),
                running = listOf(
                    SessionBoard.Section("Noter", listOf(running)),
                    SessionBoard.Section("Clauder", listOf(alsoRunning)),
                ),
                inactive = listOf(
                    SessionBoard.Section("rss", listOf(onlyIdle)),
                    SessionBoard.Section("Clauder", listOf(idle)),
                    SessionBoard.Section("Noter", listOf(done)),
                    SessionBoard.Section(null, listOf(loose)),
                ),
            ),
            SessionBoard.board(listOf(onlyIdle, running, idle, done, waiting, loose, alsoRunning), nowMillis = 0),
        )
    }

    @Test
    fun `a session between turns with a step in progress stays with the active ones`() {
        val now = java.time.Instant.parse("2026-11-22T14:00:00Z").toEpochMilli()
        val working = TaskList(
            "a", "Fix crash", null, SessionState.Idle, 1, "2026-11-22T13:50:00Z",
            tasks = emptyList(),
            steps = listOf(TaskItem("1", "Fix", TaskStatus.Completed), TaskItem("2", "Merge", TaskStatus.InProgress)),
            project = "Clauder",
        )
        val board = SessionBoard.board(listOf(working), now)
        assertEquals(listOf(working.copy(state = SessionState.Running)), board.running.single().sessions)
        assertEquals(emptyList<SessionBoard.Section>(), board.inactive)
    }

    @Test
    fun `sections come in order with plain project headers, and only inactive folds`() {
        val running = list("a", 1, "5").copy(project = "Noter")
        val waiting = list("d", 1, "4").copy(project = "Clauder", state = SessionState.Waiting)
        val done = list("c", 1, "3").copy(project = "Noter", state = SessionState.Done)
        val idle = list("b", 1, "2").copy(project = "rss", state = SessionState.Idle)
        val board = SessionBoard.board(listOf(running, waiting, done, idle), nowMillis = 0)
        val needs = SessionBoard.Status.NeedsInput
        val run = SessionBoard.Status.Running
        val off = SessionBoard.Status.Inactive
        assertEquals(
            listOf(
                SessionBoard.Row.StatusHeader(needs, 1, open = true),
                SessionBoard.Row.ProjectHeader(needs, "Clauder"),
                SessionBoard.Row.Session(needs, waiting),
                SessionBoard.Row.StatusHeader(run, 1, open = true),
                SessionBoard.Row.ProjectHeader(run, "Noter"),
                SessionBoard.Row.Session(run, running),
                SessionBoard.Row.StatusHeader(off, 2, open = false),
            ),
            SessionBoard.rows(board, inactiveOpen = false),
        )
        assertEquals(
            listOf(
                SessionBoard.Row.StatusHeader(off, 2, open = true),
                SessionBoard.Row.ProjectHeader(off, "Noter"),
                SessionBoard.Row.Session(off, done),
                SessionBoard.Row.ProjectHeader(off, "rss"),
                SessionBoard.Row.Session(off, idle),
            ),
            SessionBoard.rows(board, inactiveOpen = true).drop(6),
        )
    }

    @Test
    fun `the widget leaves out inactive sessions`() {
        val running = list("a", 1, "5").copy(project = "Noter")
        val done = list("c", 1, "3").copy(project = "Noter", state = SessionState.Done)
        val board = SessionBoard.board(listOf(running, done), nowMillis = 0)
        assertEquals(
            listOf(
                SessionBoard.Row.StatusHeader(SessionBoard.Status.Running, 1, open = true),
                SessionBoard.Row.ProjectHeader(SessionBoard.Status.Running, "Noter"),
                SessionBoard.Row.Session(SessionBoard.Status.Running, running),
            ),
            SessionBoard.rows(board, inactiveOpen = true, withInactive = false),
        )
        assertEquals(emptyList<SessionBoard.Row>(), SessionBoard.rows(SessionBoard.board(listOf(done), 0), false, withInactive = false))
    }

    @Test
    fun `a section with only sessions without a project has no project header, and keys are unique`() {
        val loose = list("a", 1, "5")
        val done = list("e", 1, "1").copy(project = "Noter", state = SessionState.Ended)
        val waiting = list("w", 1, "2").copy(project = "Noter", state = SessionState.Waiting)
        val rows = SessionBoard.rows(SessionBoard.board(listOf(loose, done, waiting), 0), inactiveOpen = true)
        assertEquals(
            listOf(
                SessionBoard.Row.StatusHeader(SessionBoard.Status.Running, 1, open = true),
                SessionBoard.Row.Session(SessionBoard.Status.Running, loose),
            ),
            rows.subList(3, 5),
        )
        assertEquals(rows.size, rows.map { SessionBoard.key(it) }.toSet().size)
    }
}
