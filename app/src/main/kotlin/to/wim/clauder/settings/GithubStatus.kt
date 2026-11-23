package to.wim.clauder.settings

import org.json.JSONObject

/** The Worker's GET /github: whether the GitHub App is set up, where to choose its repositories,
 * and when GitHub last sent the Worker something (null: never). */
data class GithubStatus(val connected: Boolean, val installUrl: String?, val lastEventAtMillis: Long?) {
    companion object {
        fun fromJson(json: JSONObject): GithubStatus {
            val install = json.optString("installUrl").takeIf { !json.isNull("installUrl") && it.startsWith("https://github.com/") }
            val last = json.optString("lastEventAt").takeIf { !json.isNull("lastEventAt") && it.isNotBlank() }?.let(::parseMillis)
            return GithubStatus(json.optBoolean("connected"), install, last)
        }

        /** The one-time link from POST /github/connect, only when it is this Worker's https page. */
        fun connectUrl(json: JSONObject, workerUrl: String): String? =
            json.optString("url").takeIf { it.startsWith("${workerUrl.trimEnd('/')}/github/start?") }

        private fun parseMillis(text: String): Long? = try {
            java.time.Instant.parse(text).toEpochMilli()
        } catch (e: java.time.format.DateTimeParseException) {
            null
        }
    }
}
