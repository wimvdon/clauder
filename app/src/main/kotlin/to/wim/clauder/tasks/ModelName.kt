package to.wim.clauder.tasks

/** A model id as Claude Code reports it, in words: "claude-opus-5-5[1m]" is "Opus 5.5". */
object ModelName {
    fun readable(id: String): String {
        val base = id.trim().substringBefore('[').substringAfterLast('/').removePrefix("claude-")
        // Dated ids ("claude-3-5-sonnet-20241022") end in an eight-digit date.
        val parts = base.split('-').filter { it.isNotEmpty() && !(it.length == 8 && it.all(Char::isDigit)) }
        val family = parts.firstOrNull { part -> part.any(Char::isLetter) } ?: return id.trim()
        val version = parts.filter { part -> part.all(Char::isDigit) }
        if (version.isEmpty() || parts.count { part -> part.any(Char::isLetter) } > 1) return id.trim()
        return family.replaceFirstChar(Char::uppercaseChar) + " " + version.joinToString(".")
    }
}
