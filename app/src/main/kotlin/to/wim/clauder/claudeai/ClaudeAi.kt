package to.wim.clauder.claudeai

import java.net.URLEncoder

/**
 * The private claude.ai endpoints the Claude Android app uses for project threads
 * (see docs/spike-claude-ai-api.md). They are undocumented and can change with any Claude app update.
 * Every call is signed with the claude.ai `sessionKey` cookie, which is a full login.
 */
object ClaudeAi {
    const val ORIGIN = "https://claude.ai"
    const val API = "$ORIGIN/api/"
    const val LOGIN_URL = "$ORIGIN/login"

    /** Finds the `sessionKey` value in a Cookie header such as CookieManager.getCookie returns. */
    fun sessionKeyFrom(cookieHeader: String?): String? = cookieHeader.orEmpty()
        .split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("sessionKey=") }
        ?.removePrefix("sessionKey=")
        ?.takeIf { it.isNotBlank() }

    object Paths {
        fun channels(limit: Int) = "v1/code/channels?limit=$limit"
        fun threads(channelId: String, limit: Int) = "v1/code/channels/${encode(channelId)}/threads?limit=$limit"
        /** The project chat: top-level messages, newest first. */
        fun timeline(channelId: String, limit: Int) = "v1/code/channels/${encode(channelId)}/timeline?limit=$limit"
        const val NOTIFICATIONS_SUMMARY = "v1/code/notifications/summary"

        /** Live events for every thread in a project (server-sent events). */
        fun channelStream(channelId: String) = "v1/code/channels/${encode(channelId)}/messages/stream?scope=channel"

        /** Live changes to the account's sessions (server-sent events). */
        const val SESSIONS_WATCH = "v1/code/sessions/watch"

        private fun encode(id: String) = URLEncoder.encode(id, "UTF-8")
    }
}
