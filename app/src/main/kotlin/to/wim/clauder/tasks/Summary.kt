package to.wim.clauder.tasks

/** All sessions in one line, for the summary notification and the quick settings tile. */
data class Summary(
    val working: Int,
    val waiting: Int,
    /** Working and waiting sessions, the ones waiting for you first. */
    val active: List<TaskList>,
) {
    val isEmpty: Boolean get() = active.isEmpty()

    /** Done steps over all steps of the active sessions, for the status bar chip. */
    val done: Int get() = active.sumOf { it.doneCount }
    val total: Int get() = active.sumOf { it.items.size }

    /**
     * The active sessions' projects (else repository, else the thread's title) in the bar's order,
     * each named once with "×n" when it has several sessions, cut short so several fit on one line.
     */
    val names: List<String>
        get() = active.groupBy { it.group ?: it.title }.map { (name, lists) ->
            if (lists.size > 1) "${shortTitle(name)} ×${lists.size}" else shortTitle(name)
        }

    private fun shortTitle(title: String): String {
        val words = title.trim().split(Regex("\\s+"))
        return if (title.length <= MAX_NAME || words.size <= 1) title.take(MAX_NAME + 4)
        else words.fold("") { acc, w -> if (acc.isEmpty()) w else if (acc.length + 1 + w.length <= MAX_NAME) "$acc $w" else acc } + "…"
    }

    companion object {
        /** Characters per name in the summary line. */
        const val MAX_NAME = 18

        /** [lists] as shown at [nowMillis], leaving out muted sessions. */
        fun from(lists: List<TaskList>, muted: Set<String>, nowMillis: Long): Summary {
            val shown = lists.filter { it.key !in muted && it.items.isNotEmpty() }.map { it.shown(nowMillis) }
            val waiting = shown.filter { it.state == SessionState.Waiting }
            val working = shown.filter { it.state == SessionState.Running }
            return Summary(working.size, waiting.size, waiting + working)
        }
    }
}

/**
 * The summary notification's progress bar: one block per active session, in [Summary.active]'s
 * order (waiting first), coloured by what it is doing, with a dot at how far that session is.
 */
data class SummaryBar(val blocks: List<Kind>, val points: List<Int>) {
    enum class Kind { Waiting, Working, Holding }

    val max: Int get() = blocks.size * BLOCK

    companion object {
        const val BLOCK = 100

        fun from(summary: Summary, nowMillis: Long): SummaryBar {
            val blocks = summary.active.map { list ->
                when {
                    list.state == SessionState.Waiting -> Kind.Waiting
                    LiveUpdate.holding(list, list.wakeAtMillis(nowMillis)) != null -> Kind.Holding
                    else -> Kind.Working
                }
            }
            // Kept off the block's edges, so a dot at 0 or at the end still reads as inside it.
            val points = summary.active.mapIndexed { i, list ->
                val part = if (list.items.isEmpty()) 0 else list.doneCount * BLOCK / list.items.size
                i * BLOCK + part.coerceIn(BLOCK / 10, BLOCK - BLOCK / 10)
            }
            return SummaryBar(blocks, points)
        }
    }
}

/** The user's notification settings: [summary] is "One notification for all sessions",
 * [waitingAlerts] is "Alert when Claude waits" (off when the Claude app's own notifications do it). */
data class NotifyPrefs(val summary: Boolean = false, val waitingAlerts: Boolean = true)

/** Which notifications to show: one per session, or one summary plus the sessions that need you. */
object NotificationPlan {
    /** True when [list] gets a notification of its own. In summary mode only a session waiting for
     * you does, so it can alert, and only while those alerts are on; the rest is in the summary. */
    fun individual(list: TaskList, muted: Set<String>, prefs: NotifyPrefs, nowMillis: Long): Boolean {
        if (list.key in muted) return false
        return !prefs.summary || (prefs.waitingAlerts && list.shown(nowMillis).state == SessionState.Waiting)
    }

    /**
     * True when the push that brought [next] should make a sound: [next] waits for you and it
     * didn't before, or it now asks something else. A thread that keeps waiting while Claude goes
     * on (a checklist update, the summary that follows the turn's end) updates silently, and an
     * alert the person swiped away stays away. [previous] is the copy the phone had before.
     */
    fun alertsAgain(previous: TaskList?, next: TaskList, nowMillis: Long): Boolean {
        if (next.shown(nowMillis).state != SessionState.Waiting) return false
        if (previous == null || previous.shown(nowMillis).state != SessionState.Waiting) return true
        return previous.need != next.need
    }

    /** True when a session's notification goes on the "Waiting for you" channel and makes a sound;
     * otherwise it is a quiet progress notification that still says "Waiting for you". */
    fun alerts(status: LiveUpdate.Status, prefs: NotifyPrefs): Boolean = prefs.waitingAlerts && status == LiveUpdate.Status.Waiting
}

/** Short numbers for tokens and cost, such as "1.2M tokens · ≈ $4.10". */
object Usage {
    fun tokens(count: Long): String = when {
        count >= 1_000_000 -> "%.1fM".format(java.util.Locale.ROOT, count / 1_000_000.0)
        count >= 1_000 -> "%dk".format(java.util.Locale.ROOT, count / 1_000)
        else -> "$count"
    }

    fun dollars(amount: Double): String = "$%.2f".format(java.util.Locale.ROOT, amount)
}
