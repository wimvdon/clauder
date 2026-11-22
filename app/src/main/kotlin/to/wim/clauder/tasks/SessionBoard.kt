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

    /** One project (or repository) on the Tasks screen: what is working or waiting, and what is idle or finished. */
    data class Section(val group: String?, val active: List<TaskList>, val inactive: List<TaskList>)

    /** [groups] split into active and inactive sessions, with each session's state as shown at
     * [nowMillis] (see [TaskList.shown]). Projects with something active come first; sessions
     * without a project stay last. */
    fun sections(lists: List<TaskList>, nowMillis: Long): List<Section> {
        val all = groups(lists.map { it.shown(nowMillis) }).map { (group, members) ->
            val (inactive, active) = members.partition { isInactive(it) }
            Section(group, active, inactive)
        }
        val (named, rest) = all.partition { it.group != null }
        return named.sortedBy { it.active.isEmpty() } + rest
    }

    /** One line of the Tasks screen. */
    sealed interface Row {
        /** A project's header; tapping it folds the whole project. */
        data class Header(val group: String?, val count: Int, val open: Boolean) : Row

        /** A session that is working or waiting for you, shown in full. */
        data class Active(val list: TaskList) : Row

        /** "3 inactive" under a project's active sessions; tapping it shows or hides them. */
        data class InactiveToggle(val group: String?, val count: Int, val open: Boolean) : Row

        /** An idle or finished session, as one line. */
        data class Inactive(val list: TaskList) : Row
    }

    /** The Tasks screen, top to bottom. Projects start open and their inactive sessions start
     * folded: [foldedGroups] holds the projects the user closed and [openInactive] the projects whose
     * inactive sessions the user opened (by group name, "" for sessions without a project). */
    fun rows(sections: List<Section>, foldedGroups: Set<String>, openInactive: Set<String>): List<Row> = sections.flatMap { section ->
        val id = section.group.orEmpty()
        val open = id !in foldedGroups
        buildList {
            // A lone list of sessions without a project needs no header to fold it.
            if (section.group != null || sections.size > 1) {
                add(Row.Header(section.group, section.active.size + section.inactive.size, open))
            }
            if (!open) return@buildList
            section.active.forEach { add(Row.Active(it)) }
            if (section.inactive.isNotEmpty()) {
                val showInactive = id in openInactive
                add(Row.InactiveToggle(section.group, section.inactive.size, showInactive))
                if (showInactive) section.inactive.forEach { add(Row.Inactive(it)) }
            }
        }
    }

    /** Idle (nothing for you, nothing running) or finished. */
    fun isInactive(list: TaskList): Boolean = list.isFinished || list.state == SessionState.Idle

    /** Sessions without steps or tasks (a session that only stopped or ended) are left out. */
    fun sort(lists: List<TaskList>): List<TaskList> =
        lists.filter { it.items.isNotEmpty() && it.key != "demo" }.sortedByDescending { it.updatedAt }
}
