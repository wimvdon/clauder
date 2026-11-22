package to.wim.clauder.tasks

/**
 * What a Live Update's status bar chip says. On a Wear OS watch the chip is all the notification
 * tray shows of a Live Update, next to the app name, so it says what the sessions are doing
 * instead of only a number.
 */
sealed interface Chip {
    /** "1 waiting": sessions that wait for you, which matter most. */
    data class Waiting(val count: Int) : Chip

    /** "2 working". */
    data class Working(val count: Int) : Chip

    /** One session that waits for you: "Waiting". */
    data object WaitsForYou : Chip

    /** One working session: its steps, "3/5". */
    data class Steps(val done: Int, val total: Int) : Chip

    data object Done : Chip

    companion object {
        fun forSummary(summary: Summary): Chip =
            if (summary.waiting > 0) Waiting(summary.waiting) else Working(summary.active.size)

        fun forSession(update: LiveUpdate): Chip = when (update.status) {
            LiveUpdate.Status.Done -> Done
            LiveUpdate.Status.Waiting -> WaitsForYou
            else -> Steps(update.done, update.total)
        }
    }
}
