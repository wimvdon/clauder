package to.wim.clauder.claudeai

import org.json.JSONArray
import org.json.JSONException
import java.net.URLEncoder

/**
 * The private claude.ai endpoints the Claude Android app uses for project threads
 * (see docs/spike-claude-ai-api.md). They are undocumented and can change with any Claude app update.
 * Every call is signed with the claude.ai `sessionKey` cookie, which is a full login.
 */
object ClaudeAi {
    const val ORIGIN = "https://claude.ai"

    /**
     * The Claude app sends the v1/code calls to the site root (https://claude.ai/v1/code/...), not
     * under /api/ like the web app's own calls. Paths below are relative to this.
     */
    const val BASE = "$ORIGIN/"
    const val LOGIN_URL = "$ORIGIN/login"

    /** Sent with every v1/code call, as the Claude app does (its sessions API client). */
    const val ANTHROPIC_VERSION = "2023-06-01"
    const val ANTHROPIC_BETA = "ccr-byoc-2025-07-29"

    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** Finds the `sessionKey` value in a Cookie header such as CookieManager.getCookie returns. */
    fun sessionKeyFrom(cookieHeader: String?): String? = cookie(cookieHeader, "sessionKey")

    /** The organization claude.ai last used (`lastActiveOrg` cookie), when it is a valid UUID. */
    fun organizationFrom(cookieHeader: String?): String? = cookie(cookieHeader, "lastActiveOrg")?.takeIf { UUID.matches(it) }

    /** The first organization uuid in claude.ai's organization list, or null. */
    fun firstOrganization(body: String): String? = try {
        val list = JSONArray(body)
        (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("uuid") }.firstOrNull { UUID.matches(it) }
    } catch (e: JSONException) {
        null
    }

    /** Headers for one call. The organization header is left out while it is still unknown. */
    fun headers(sessionKey: String, organization: String?, accept: String): Map<String, String> = buildMap {
        put("Cookie", "sessionKey=$sessionKey")
        put("Accept", accept)
        put("Anthropic-Version", ANTHROPIC_VERSION)
        put("Anthropic-Beta", ANTHROPIC_BETA)
        organization?.let { put("X-Organization-Uuid", it) }
    }

    private fun cookie(cookieHeader: String?, name: String): String? = cookieHeader.orEmpty()
        .split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name=") }
        ?.removePrefix("$name=")
        ?.takeIf { it.isNotBlank() }

    object Paths {
        /** The account's organizations (the web app's own endpoint, under /api/). */
        const val ORGANIZATIONS = "api/organizations"

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
