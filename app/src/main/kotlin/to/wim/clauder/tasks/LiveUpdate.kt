package to.wim.clauder.tasks

/** What the notification shows, worked out without Android types so it can be unit tested. */
data class LiveUpdate(
    val title: String,
    /** The project (or repository) the session belongs to. */
    val group: String?,
    val status: Status,
    /** While waiting: what Claude asks, shown in place of "Waiting for you" when known. */
    val need: String?,
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
    enum class Status { Tasks, Idle, Waiting, Done }

    val max: Int get() = segmentCount * SEGMENT_LENGTH

    /** Idle and finished sessions are bundled per project in the shade, so the bundle can be
     * folded away; live ones stay on their own to keep their progress bar. Null: not bundled. */
    val bundle: String? get() = if (ongoing) null else BUNDLE_PREFIX + group.orEmpty()

    companion object {
        const val SEGMENT_LENGTH = 100
        const val DONE_TIMEOUT_MS = 3 * 60 * 1000L
        const val CODE_URL = "https://claude.ai/code"
        const val BUNDLE_PREFIX = "inactive:"

        /** Null when there is nothing to show: no tasks yet, or the session closed with open
         * tasks (then the notification goes away). */
        fun from(session: TaskList, nowMillis: Long = System.currentTimeMillis()): LiveUpdate? {
            val list = session.shown(nowMillis)
            val items = list.items
            if (items.isEmpty()) return null
            if (list.endedEarly) return null
            val total = items.size
            val done = list.doneCount
            val currentIndex = items.indexOfFirst { it.status == TaskStatus.InProgress }
            val status = when (list.state) {
                SessionState.Done, SessionState.Ended -> Status.Done
                SessionState.Waiting -> Status.Waiting
                SessionState.Idle -> Status.Idle
                SessionState.Running -> Status.Tasks
            }
            val finished = status == Status.Done
            // An idle session stays in the shade but isn't pinned as ongoing or promoted.
            val live = !finished && status != Status.Idle
            return LiveUpdate(
                title = list.title,
                group = list.group,
                status = status,
                need = list.need?.takeIf { status == Status.Waiting },
                steps = list.hasSteps,
                done = done,
                total = total,
                currentTask = list.current?.subject,
                nextTask = list.next?.subject,
                segmentCount = maxOf(total, 1),
                progress = (if (finished) maxOf(total, 1) else done) * SEGMENT_LENGTH,
                pointPosition = if (currentIndex >= 0 && !finished) currentIndex * SEGMENT_LENGTH + SEGMENT_LENGTH / 2 else null,
                ongoing = live,
                requestPromotion = live,
                timeoutMs = if (finished) DONE_TIMEOUT_MS else null,
                openUrl = list.url?.takeIf { isClaudeUrl(it) } ?: CODE_URL,
            )
        }

        private fun isClaudeUrl(url: String): Boolean = url.startsWith("https://claude.ai/")
    }
}
