package to.wim.clauder.tasks

/** The sessions the Tasks screen shows: the Worker's list, kept up to date by pushes. */
object SessionBoard {
    /** Adds or replaces [incoming] unless the board already has a newer copy. Newest first. */
    fun merge(current: List<TaskList>, incoming: TaskList): List<TaskList> {
        val existing = current.firstOrNull { it.key == incoming.key }
        if (existing != null && existing.seq >= incoming.seq) return current
        return sort(current.filterNot { it.key == incoming.key } + incoming)
    }

    /** Merges a full list from the Worker with what pushes already brought in. */
    fun mergeAll(current: List<TaskList>, fetched: List<TaskList>): List<TaskList> =
        fetched.fold(current) { board, list -> merge(board, list) }

    /** Active sessions first, then the ones that finished (all done, or closed). */
    fun split(lists: List<TaskList>): Pair<List<TaskList>, List<TaskList>> = lists.partition { !it.isFinished }

    /** What the phone keeps between app starts: the newest [max] sessions. */
    fun keep(lists: List<TaskList>, max: Int = MAX_KEPT): List<TaskList> = sort(lists).take(max)

    const val MAX_KEPT = 20

    /** Sessions per project (or repository), in the order the first of each appears; sessions
     * without either come last, under a null name. */
    fun groups(lists: List<TaskList>): List<Pair<String?, List<TaskList>>> {
        val named = lists.filter { it.group != null }.groupBy { it.group }.toList()
        val rest = lists.filter { it.group == null }
        return if (rest.isEmpty()) named else named + (null to rest)
    }

    /** The three parts of the Tasks screen, top to bottom. */
    enum class Status { NeedsInput, Running, Inactive }

    /** One project (or repository) inside a status section, with its sessions in that status. */
    data class Section(val group: String?, val sessions: List<TaskList>)

    /** The Tasks screen's content: sessions that wait for you, sessions that are working (CI waits
     * included), and idle or finished ones, each grouped by project. */
    data class Board(val needsInput: List<Section>, val running: List<Section>, val inactive: List<Section>) {
        fun sections(status: Status): List<Section> = when (status) {
            Status.NeedsInput -> needsInput
            Status.Running -> running
            Status.Inactive -> inactive
        }
    }

    /** Which section [list] belongs in, by its shown state. */
    fun status(list: TaskList): Status = when {
        isInactive(list) -> Status.Inactive
        list.state == SessionState.Waiting -> Status.NeedsInput
        else -> Status.Running
    }

    /** [lists] with each session's state as shown at [nowMillis] (see [TaskList.shown]), split by
     * [status] and grouped by project within each (sessions without a project last). */
    fun board(lists: List<TaskList>, nowMillis: Long): Board {
        val byStatus = lists.map { it.shown(nowMillis) }.groupBy { status(it) }
        fun sections(status: Status) = groups(byStatus[status].orEmpty()).map { (group, members) -> Section(group, members) }
        return Board(sections(Status.NeedsInput), sections(Status.Running), sections(Status.Inactive))
    }

    /** One line of the Tasks screen or the widget. */
    sealed interface Row {
        /** A status section's title with its count. Only the Inactive one folds, by tapping it. */
        data class StatusHeader(val status: Status, val count: Int, val open: Boolean) : Row

        /** A project's name inside a status section: a plain label, it doesn't fold. */
        data class ProjectHeader(val status: Status, val group: String?) : Row

        /** A session inside a status section. */
        data class Session(val status: Status, val list: TaskList) : Row
    }

    /** The Tasks screen, top to bottom: Needs input, Running, Inactive, leaving out empty sections.
     * Inactive starts folded and shows its sessions only while [inactiveOpen]; the widget passes
     * [withInactive] false to leave it out. A section whose only sessions have no project needs
     * no project header. */
    fun rows(board: Board, inactiveOpen: Boolean, withInactive: Boolean = true): List<Row> = buildList {
        Status.entries.forEach { status ->
            if (status == Status.Inactive && !withInactive) return@forEach
            val sections = board.sections(status)
            if (sections.isEmpty()) return@forEach
            val open = status != Status.Inactive || inactiveOpen
            add(Row.StatusHeader(status, sections.sumOf { it.sessions.size }, open))
            if (!open) return@forEach
            sections.forEach { section ->
                if (section.group != null || sections.size > 1) add(Row.ProjectHeader(status, section.group))
                section.sessions.forEach { add(Row.Session(status, it)) }
            }
        }
    }

    /** A stable key per row, for lazy lists. */
    fun key(row: Row): String = when (row) {
        is Row.StatusHeader -> "status:${row.status}"
        is Row.ProjectHeader -> "project:${row.status}:${row.group.orEmpty()}"
        is Row.Session -> row.list.key
    }

    /** Idle (nothing for you, nothing running) or finished. */
    fun isInactive(list: TaskList): Boolean = list.isFinished || list.state == SessionState.Idle

    /** Sessions without steps or tasks (a session that only stopped or ended) are left out, except
     * threads only claude.ai knows, which never have any. */
    fun sort(lists: List<TaskList>): List<TaskList> =
        lists.filter { (it.items.isNotEmpty() || it.fromClaudeAi) && it.key != "demo" }.sortedByDescending { it.updatedAt }
}
