package to.wim.clauder.settings

import org.json.JSONObject

/** The Worker's GET /status: when the last hook event arrived (ISO time, null when none has), and
 * whether the session that sent it ran the current hook script and settings (null: it didn't say). */
data class HookStatus(val lastEventAt: String?, val scriptCurrent: Boolean?, val settingsCurrent: Boolean?) {
    companion object {
        fun fromJson(json: JSONObject): HookStatus {
            val last = json.optJSONObject("lastEvent") ?: return HookStatus(null, null, null)
            fun flag(name: String): Boolean? = if (last.isNull(name) || !last.has(name)) null else last.optBoolean(name)
            return HookStatus(last.optString("at").takeIf { it.isNotBlank() }, flag("scriptCurrent"), flag("settingsCurrent"))
        }
    }
}

/** What the setup check in Settings says about the hooks. */
sealed interface SetupCheck {
    /** No hook event has reached the Worker yet (or none in its log). */
    data object NoEvents : SetupCheck

    /** Events arrive, and the last came from current hooks. [atMillis] is when it arrived. */
    data class Working(val atMillis: Long) : SetupCheck

    /** Events arrive, but the last one came from a session started with older hook settings. */
    data class OldHooks(val atMillis: Long) : SetupCheck

    companion object {
        fun from(status: HookStatus): SetupCheck {
            val at = status.lastEventAt?.let(::parseMillis) ?: return NoEvents
            // Only the loaded settings matter: an old script updates itself at the next prompt.
            return if (status.settingsCurrent == false) OldHooks(at) else Working(at)
        }

        private fun parseMillis(text: String): Long? = try {
            java.time.Instant.parse(text).toEpochMilli()
        } catch (e: java.time.format.DateTimeParseException) {
            null
        }
    }
}
