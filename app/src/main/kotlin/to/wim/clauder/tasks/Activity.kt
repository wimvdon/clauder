package to.wim.clauder.tasks

/** The tool Claude runs now, as the hook script reports it: the tool's name and a short detail
 * (a file's name, the description Claude gave a shell command, a search pattern). */
data class Doing(val tool: String, val detail: String?) {
    enum class Kind { Shell, Edit, Read, Search, Fetch, WebSearch, Helper, Blocked, Compacting, PushFailed, Other }

    /** How the phone words it: "Editing TaskList.kt", "Searching for parseChecklist". */
    val kind: Kind
        get() = when (tool) {
            BLOCKED -> Kind.Blocked
            COMPACTING -> Kind.Compacting
            PUSH_FAILED -> Kind.PushFailed
            else -> toolKind()
        }

    private fun toolKind(): Kind = if (detail == null) {
        Kind.Other
    } else {
        when (tool) {
            "Bash" -> Kind.Shell
            "Edit", "MultiEdit", "Write", "NotebookEdit" -> Kind.Edit
            "Read" -> Kind.Read
            "Grep", "Glob" -> Kind.Search
            "WebFetch" -> Kind.Fetch
            "WebSearch" -> Kind.WebSearch
            "Agent", "Task" -> Kind.Helper
            else -> Kind.Other
        }
    }

    /** What goes in the sentence: the detail, or for [Kind.Other] the tool's readable name. A
     * connector's tool (mcp__github__create_pull_request) is named after its connector. */
    val subject: String
        get() = if (kind != Kind.Other) detail.orEmpty() else readableTool(tool)

    companion object {
        /** Not tools: the Worker's names for auto mode refusing a tool call (the detail is the
         * policy it hit), compaction, and a git push that failed. */
        const val BLOCKED = "clauder:blocked"
        const val COMPACTING = "clauder:compacting"
        const val PUSH_FAILED = "clauder:push-failed"

        private fun readableTool(tool: String): String {
            val parts = tool.split("__")
            return if (parts.size >= 3 && parts[0] == "mcp") parts[1].replace('_', ' ').replace('-', ' ') else tool
        }
    }
}

/** What Claude changed since the person last wrote: files edited and lines added and removed,
 * from the person's message ([since]) to the end of the last turn ([endedAt]). */
data class WorkReceipt(val since: String, val endedAt: String, val files: Int, val added: Int, val removed: Int) {
    /** Whole minutes from the person's message to the end of the turn, or null when a time can't
     * be read. */
    val minutes: Long?
        get() {
            val start = parse(since) ?: return null
            val end = parse(endedAt) ?: return null
            return if (end >= start) (end - start) / 60_000 else null
        }

    /** True when there is nothing to say: no time and no files. */
    val isEmpty: Boolean get() = minutes == null && files == 0

    companion object {
        private fun parse(text: String): Long? = try {
            java.time.Instant.parse(text).toEpochMilli()
        } catch (e: java.time.format.DateTimeParseException) {
            null
        }
    }
}
