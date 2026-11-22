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

    /** One project (or repository) on the Tasks screen, with its sessions that are working or waiting. */
    data class Section(val group: String?, val active: List<TaskList>)

    /** The Tasks screen's content: projects with something working or waiting, and one list of
     * every idle or finished session, whatever its project. */
    data class Board(val sections: List<Section>, val inactive: List<TaskList>)

    /** [lists] with each session's state as shown at [nowMillis] (see [TaskList.shown]): active
     * sessions grouped by project (sessions without a project last), and all inactive sessions in
     * one list, newest first. A project with only inactive sessions gets no section. */
    fun board(lists: List<TaskList>, nowMillis: Long): Board {
        val (inactive, active) = lists.map { it.shown(nowMillis) }.partition { isInactive(it) }
        return Board(groups(active).map { (group, members) -> Section(group, members) }, inactive)
    }

    /** One line of the Tasks screen. */
    sealed interface Row {
        /** A project's header; tapping it folds the project. */
        data class Header(val group: String?, val count: Int, val open: Boolean) : Row

        /** A session that is working or waiting for you, shown in full. */
        data class Active(val list: TaskList) : Row

        /** "Inactive" with a count, below every project; tapping it shows or hides the list. */
        data class InactiveHeader(val count: Int, val open: Boolean) : Row

        /** An idle or finished session, as one line with its project or repository above the title. */
        data class Inactive(val list: TaskList) : Row
    }

    /** The Tasks screen, top to bottom. Projects start open ([foldedGroups] holds the ones the user
     * closed, by group name, "" for sessions without a project); the inactive list starts folded
     * and shows only while [inactiveOpen]. */
    fun rows(board: Board, foldedGroups: Set<String>, inactiveOpen: Boolean): List<Row> = buildList {
        board.sections.forEach { section ->
            val open = section.group.orEmpty() !in foldedGroups
            // A lone list of sessions without a project needs no header to fold it.
            if (section.group != null || board.sections.size > 1) add(Row.Header(section.group, section.active.size, open))
            if (open) section.active.forEach { add(Row.Active(it)) }
        }
        if (board.inactive.isNotEmpty()) {
            add(Row.InactiveHeader(board.inactive.size, inactiveOpen))
            if (inactiveOpen) board.inactive.forEach { add(Row.Inactive(it)) }
        }
    }

    /** Idle (nothing for you, nothing running) or finished. */
    fun isInactive(list: TaskList): Boolean = list.isFinished || list.state == SessionState.Idle

    /** Sessions without steps or tasks (a session that only stopped or ended) are left out. */
    fun sort(lists: List<TaskList>): List<TaskList> =
        lists.filter { it.items.isNotEmpty() && it.key != "demo" }.sortedByDescending { it.updatedAt }
}
