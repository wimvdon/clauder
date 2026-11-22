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

    companion object {
        /** [lists] as shown at [nowMillis], leaving out muted sessions. */
        fun from(lists: List<TaskList>, muted: Set<String>, nowMillis: Long): Summary {
            val shown = lists.filter { it.key !in muted && it.items.isNotEmpty() }.map { it.shown(nowMillis) }
            val waiting = shown.filter { it.state == SessionState.Waiting }
            val working = shown.filter { it.state == SessionState.Running }
            return Summary(working.size, waiting.size, waiting + working)
        }
    }
}

/** Which notifications to show: one per session, or one summary plus the sessions that need you. */
object NotificationPlan {
    /** True when [list] gets a notification of its own. In summary mode only a session waiting for
     * you does, so it can alert; the rest is in the summary. */
    fun individual(list: TaskList, muted: Set<String>, summaryMode: Boolean, nowMillis: Long): Boolean {
        if (list.key in muted) return false
        return !summaryMode || list.shown(nowMillis).state == SessionState.Waiting
    }
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
