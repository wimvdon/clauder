package to.wim.clauder.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtrasTest {
    private val now = java.time.Instant.parse("2026-11-22T14:00:00Z").toEpochMilli()

    private fun session(
        key: String = "k",
        state: SessionState = SessionState.Running,
        vararg steps: TaskStatus = arrayOf(TaskStatus.Completed, TaskStatus.Pending),
        pr: PullRequest? = null,
        wakeAt: String? = null,
        helpers: Int = 0,
    ) = TaskList(
        key = key, title = "T $key", url = "https://claude.ai/code/session_$key", state = state, seq = 1,
        updatedAt = "2026-11-22T13:59:00Z", tasks = emptyList(),
        steps = steps.mapIndexed { i, s -> TaskItem("plan:$i", "step $i", s) },
        pr = pr, wakeAt = wakeAt, helpers = helpers,
    )

    private val pr = PullRequest(22, "https://github.com/wimvdon/clauder/pull/22", PrState.Open, CiState.Running)

    @Test
    fun `the push carries the PR, the link, a reminder, helpers and the cost`() {
        val list = TaskList.fromPushData(
            mapOf(
                "type" to "tasklist", "key" to "k", "title" to "T", "state" to "running", "seq" to "2", "updatedAt" to "t",
                "tasks" to "[]", "steps" to """[{"i":"plan:1","s":"a","t":"pending"}]""",
                "pr" to """{"n":22,"u":"https://github.com/wimvdon/clauder/pull/22","s":"open","c":"running"}""",
                "link" to "https://claude.ai/artifact/x", "linkTitle" to "Mockup",
                "wakeAt" to "2026-11-22T19:00:00.000Z", "helpers" to "2", "tokens" to "1200000", "cost" to "4.1",
            ),
        )!!
        assertEquals(pr, list.pr)
        assertEquals("https://claude.ai/artifact/x", list.link)
        assertEquals("Mockup", list.linkTitle)
        assertEquals(2, list.helpers)
        assertEquals(1_200_000L, list.tokens)
        assertEquals(4.1, list.cost, 0.001)
        // The saved copy reads back the same.
        assertEquals(list, TaskList.fromJson(list.toJson()))
    }

    @Test
    fun `links that aren't https and PRs that aren't on GitHub are dropped`() {
        val list = TaskList.fromPushData(
            mapOf(
                "type" to "tasklist", "key" to "k", "tasks" to "[]", "steps" to "[]",
                "pr" to """{"n":1,"u":"https://evil.example/pull/1","s":"open","c":""}""",
                "link" to "javascript:alert(1)",
            ),
        )!!
        assertNull(list.pr)
        assertNull(list.link)
    }

    @Test
    fun `a working session says what it waits on`() {
        assertEquals(LiveUpdate.Holding.Ci, LiveUpdate.from(session(pr = pr), now)!!.holding)
        val reminder = LiveUpdate.from(session(wakeAt = "2026-11-22T19:00:00Z"), now)!!
        assertEquals(LiveUpdate.Holding.Reminder, reminder.holding)
        assertEquals(java.time.Instant.parse("2026-11-22T19:00:00Z").toEpochMilli(), reminder.wakeAtMillis)
        assertNull(LiveUpdate.from(session(wakeAt = "2026-11-22T10:00:00Z"), now)!!.holding)
        assertEquals(LiveUpdate.Holding.Helpers, LiveUpdate.from(session(helpers = 3), now)!!.holding)
        // A passed CI is nothing to wait on.
        assertNull(LiveUpdate.from(session(pr = pr.copy(ci = CiState.Passed)), now)!!.holding)
        // Waiting for you says so itself.
        assertNull(LiveUpdate.from(session(state = SessionState.Waiting, pr = pr), now)!!.holding)
    }

    @Test
    fun `the notification offers the PR and the attached page, and finished is silent`() {
        val update = LiveUpdate.from(session(pr = pr).copy(link = "https://claude.ai/artifact/x", linkTitle = "Mockup"), now)!!
        assertEquals("https://github.com/wimvdon/clauder/pull/22", update.prUrl)
        assertEquals(22, update.prNumber)
        assertEquals("https://claude.ai/artifact/x", update.linkUrl)
        assertFalse(update.silent)
        assertTrue(LiveUpdate.from(session("k", SessionState.Done, TaskStatus.Completed), now)!!.silent)
    }

    @Test
    fun `the summary counts working and waiting sessions, waiting first, without muted ones`() {
        val summary = Summary.from(
            listOf(
                session("a"),
                session("b", SessionState.Waiting),
                session("c", SessionState.Idle, TaskStatus.Pending),
                session("d", SessionState.Done, TaskStatus.Completed),
                session("e"),
            ),
            muted = setOf("e"),
            nowMillis = now,
        )
        assertEquals(1, summary.working)
        assertEquals(1, summary.waiting)
        assertEquals(listOf("b", "a"), summary.active.map { it.key })
        assertEquals(2, summary.done)
        assertEquals(4, summary.total)
        assertTrue(Summary.from(listOf(session("c", SessionState.Idle, TaskStatus.Pending)), emptySet(), now).isEmpty)
    }

    @Test
    fun `in summary mode only a session that waits for you gets its own notification`() {
        assertTrue(NotificationPlan.individual(session(), emptySet(), summaryMode = false, nowMillis = now))
        assertFalse(NotificationPlan.individual(session(), emptySet(), summaryMode = true, nowMillis = now))
        assertTrue(NotificationPlan.individual(session(state = SessionState.Waiting), emptySet(), summaryMode = true, nowMillis = now))
        assertFalse(NotificationPlan.individual(session(state = SessionState.Waiting), setOf("k"), summaryMode = true, nowMillis = now))
    }

    @Test
    fun `usage reads short`() {
        assertEquals("1.2M", Usage.tokens(1_234_567))
        assertEquals("45k", Usage.tokens(45_300))
        assertEquals("900", Usage.tokens(900))
        assertEquals("$4.10", Usage.dollars(4.1))
    }

    @Test
    fun `the summary bar has one block per session, coloured by state, with a dot at its progress`() {
        val summary = Summary.from(
            listOf(
                session("a", SessionState.Running, TaskStatus.Completed, TaskStatus.Completed, TaskStatus.InProgress, TaskStatus.Pending),
                session("b", SessionState.Waiting, TaskStatus.Completed, TaskStatus.Pending),
                session("c", SessionState.Running, TaskStatus.Completed, TaskStatus.Pending, pr = pr),
            ),
            muted = emptySet(),
            nowMillis = now,
        )
        val bar = SummaryBar.from(summary, now)
        assertEquals(listOf(SummaryBar.Kind.Waiting, SummaryBar.Kind.Working, SummaryBar.Kind.Holding), bar.blocks)
        assertEquals(listOf(50, 150, 250), bar.points)
        assertEquals(300, bar.max)
    }

    @Test
    fun `a dot at the start or end of a session stays inside its block`() {
        val summary = Summary.from(
            listOf(
                session("a", SessionState.Running, TaskStatus.InProgress, TaskStatus.Pending),
                session("b", SessionState.Running, TaskStatus.Completed, TaskStatus.Completed),
            ),
            muted = emptySet(),
            nowMillis = now,
        )
        assertEquals(listOf(10, 190), SummaryBar.from(summary, now).points)
    }
}
