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

    /** Sessions without steps or tasks (a session that only stopped or ended) are left out. */
    fun sort(lists: List<TaskList>): List<TaskList> =
        lists.filter { it.items.isNotEmpty() && it.key != "demo" }.sortedByDescending { it.updatedAt }
}
