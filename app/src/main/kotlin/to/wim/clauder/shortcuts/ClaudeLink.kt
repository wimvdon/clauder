package to.wim.clauder.shortcuts

import java.net.URI

/** A claude.ai link that can become a home screen shortcut. */
data class ClaudeLink(val url: String, val kind: Kind) {
    enum class Kind { Chat, Project, Artifact, CodeSession, Other }

    companion object {
        /** Finds the first claude.ai link in shared or pasted text. Only https and the claude.ai
         * host count, so a shortcut can never open another site. */
        fun find(text: String): ClaudeLink? {
            val candidates = Regex("""https?://\S+""").findAll(text).map { it.value.trimEnd('.', ',', ')', '>', '"', '\'') }
            val raw = candidates.firstOrNull() ?: text.trim().takeIf { !it.contains(' ') && it.startsWith("claude.ai/") }?.let { "https://$it" }
            return raw?.let(::parse)
        }

        fun parse(raw: String): ClaudeLink? {
            val uri = try {
                URI(raw.trim())
            } catch (e: java.net.URISyntaxException) {
                return null
            }
            if (uri.scheme != "https" || uri.host?.lowercase() != "claude.ai" || uri.port != -1 || uri.userInfo != null) return null
            val path = uri.rawPath.orEmpty()
            val kind = when {
                path.startsWith("/chat/") -> Kind.Chat
                path.startsWith("/project/") -> Kind.Project
                path.startsWith("/code/project/") -> Kind.Project
                path.startsWith("/artifact/") || path.startsWith("/public/artifacts/") -> Kind.Artifact
                path.startsWith("/code/session_") -> Kind.CodeSession
                else -> Kind.Other
            }
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            return ClaudeLink("https://claude.ai${path.ifEmpty { "/" }}$query", kind)
        }
    }
}

/** A shortcut the user pinned to the home screen. */
data class PinnedShortcut(val id: String, val name: String, val url: String, val kind: ClaudeLink.Kind)

/** The home screen shortcuts Android shows when you long-press the app icon. */
object StaticShortcuts {
    const val NEW_CHAT = "https://claude.ai/new"
    const val CODE = "https://claude.ai/code"
    const val PROJECTS = "https://claude.ai/code/projects/browse"
    const val ARTIFACTS = "https://claude.ai/artifacts"
}
