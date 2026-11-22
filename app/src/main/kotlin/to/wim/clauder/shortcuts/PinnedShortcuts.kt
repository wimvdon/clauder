package to.wim.clauder.shortcuts

/** Edits to the list of pinned shortcuts. Plain Kotlin, so it is unit tested. */
object PinnedShortcuts {
    const val MAX_NAME = 40

    sealed interface Draft {
        data class Valid(val link: ClaudeLink, val name: String) : Draft
        data object BadLink : Draft
        data object MissingName : Draft
    }

    /** Checks what the user typed in the Add dialog. */
    fun draft(linkText: String, nameText: String): Draft {
        val link = ClaudeLink.find(linkText) ?: return Draft.BadLink
        val name = cleanName(nameText) ?: return Draft.MissingName
        return Draft.Valid(link, name)
    }

    fun cleanName(text: String): String? = text.replace(Regex("\\s+"), " ").trim().take(MAX_NAME).trim().ifEmpty { null }

    fun add(list: List<PinnedShortcut>, link: ClaudeLink, name: String, id: String): List<PinnedShortcut> =
        list.filterNot { it.id == id } + PinnedShortcut(id, name, link.url, link.kind)

    fun rename(list: List<PinnedShortcut>, id: String, name: String): List<PinnedShortcut> =
        list.map { if (it.id == id) it.copy(name = name) else it }

    fun remove(list: List<PinnedShortcut>, id: String): List<PinnedShortcut> = list.filterNot { it.id == id }

    /** The shortcuts still on the home screen. When the launcher can't say, everything is kept. */
    fun visible(list: List<PinnedShortcut>, pinnedIds: Set<String>?): List<PinnedShortcut> =
        if (pinnedIds == null) list else list.filter { it.id in pinnedIds }

    /** Shortcut ids are "pin_" and a number that is unique on this phone. */
    fun newId(nowMillis: Long): String = "pin_$nowMillis"
}
