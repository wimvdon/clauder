package to.wim.clauder.tasks

/** What the notification shows, worked out without Android types so it can be unit tested. */
data class LiveUpdate(
    val title: String,
    val status: Status,
    /** True when [done] and [total] count a project thread's steps rather than tasks. */
    val steps: Boolean,
    val done: Int,
    val total: Int,
    /** Expanded view: the task Claude is on, then the next one. */
    val currentTask: String?,
    val nextTask: String?,
    /** One segment per task, each [SEGMENT_LENGTH] long. */
    val segmentCount: Int,
    val progress: Int,
    /** Where the active-task point sits, or null when no task is in progress. */
    val pointPosition: Int?,
    val ongoing: Boolean,
    val requestPromotion: Boolean,
    /** Dismiss after this many milliseconds, or null to stay. */
    val timeoutMs: Long?,
    val openUrl: String,
) {
    enum class Status { Tasks, Waiting, Done }

    val max: Int get() = segmentCount * SEGMENT_LENGTH

    companion object {
        const val SEGMENT_LENGTH = 100
        const val DONE_TIMEOUT_MS = 3 * 60 * 1000L
        const val CODE_URL = "https://claude.ai/code"

        /** Null when there is nothing to show: no tasks yet, or the session closed with open
         * tasks (then the notification goes away). */
        fun from(list: TaskList): LiveUpdate? {
            val items = list.items
            if (items.isEmpty()) return null
            if (list.state == SessionState.Ended && list.doneCount < items.size) return null
            val total = items.size
            val done = list.doneCount
            val currentIndex = items.indexOfFirst { it.status == TaskStatus.InProgress }
            val status = when (list.state) {
                SessionState.Done, SessionState.Ended -> Status.Done
                SessionState.Waiting -> Status.Waiting
                SessionState.Running -> Status.Tasks
            }
            val finished = status == Status.Done
            return LiveUpdate(
                title = list.title,
                status = status,
                steps = list.hasSteps,
                done = done,
                total = total,
                currentTask = list.current?.subject,
                nextTask = list.next?.subject,
                segmentCount = maxOf(total, 1),
                progress = (if (finished) maxOf(total, 1) else done) * SEGMENT_LENGTH,
                pointPosition = if (currentIndex >= 0 && !finished) currentIndex * SEGMENT_LENGTH + SEGMENT_LENGTH / 2 else null,
                ongoing = !finished,
                requestPromotion = !finished,
                timeoutMs = if (finished) DONE_TIMEOUT_MS else null,
                openUrl = list.url?.takeIf { isClaudeUrl(it) } ?: CODE_URL,
            )
        }

        private fun isClaudeUrl(url: String): Boolean = url.startsWith("https://claude.ai/")
    }
}
