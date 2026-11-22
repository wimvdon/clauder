package to.wim.clauder.settings

import java.net.URI

enum class ThemeMode { System, Light, Dark }

/** Everything the app stores. Theme and Worker address are backed up; the token, the FCM token
 * and the pinned shortcuts stay on this phone. */
data class Settings(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false,
    val workerUrl: String = "",
    val token: String = "",
    val registeredFcmToken: String = "",
) {
    val isConnected: Boolean get() = workerUrl.isNotBlank() && token.isNotBlank()
}

object WorkerUrl {
    /** Accepts "clauder.example.workers.dev" or a full https URL; returns "https://host" or null. */
    fun normalize(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty() || trimmed.contains(' ')) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = try {
            URI(withScheme)
        } catch (e: java.net.URISyntaxException) {
            return null
        }
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme != "https" || !host.contains('.') || uri.userInfo != null) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "https://$host$port"
    }

    fun host(url: String): String = try {
        URI(url).host.orEmpty()
    } catch (e: java.net.URISyntaxException) {
        ""
    }
}

/** The setup-script block that installs the task hooks in a cloud environment. Mirrors
 * hooks/install-user-hooks.sh in the repository, with this phone's Worker filled in. */
object HookSetup {
    fun script(workerHost: String): String = """
        |# Clauder: user-level hooks that send task events to the Worker, for every repo
        |mkdir -p ~/.claude
        |python3 - <<'PY' || echo "WARN: Clauder hooks not installed"
        |import json, os
        |p = os.path.expanduser("~/.claude/settings.json")
        |try:
        |    d = json.load(open(p))
        |except Exception:
        |    d = {}
        |h = {"type": "http", "url": "https://$workerHost/hook", "timeout": 5,
        |     "headers": {"Authorization": "Bearer ${'$'}CLAUDER_HOOK_TOKEN",
        |                 "X-Claude-Remote-Session": "${'$'}CLAUDE_CODE_REMOTE_SESSION_ID"},
        |     "allowedEnvVars": ["CLAUDER_HOOK_TOKEN", "CLAUDE_CODE_REMOTE_SESSION_ID"]}
        |events = {"TaskCreated": None, "TaskCompleted": None, "PostToolUse": "TaskCreate|TaskUpdate|TodoWrite|mcp__hearthbot__update_progress_checklist",
        |          "Stop": None, "Notification": None, "SessionEnd": None}
        |hooks = d.setdefault("hooks", {})
        |for name, matcher in events.items():
        |    entry = {"hooks": [h]}
        |    if matcher:
        |        entry["matcher"] = matcher
        |    keep = [x for x in hooks.get(name, []) if "$workerHost" not in json.dumps(x)]
        |    hooks[name] = keep + [entry]
        |json.dump(d, open(p, "w"), indent=1)
        |PY
        |""".trimMargin()
}
