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
        |# On each prompt: the project and thread names from the transcript, sent with the prompt event.
        |prompt = os.path.expanduser("~/.claude/clauder-prompt.py")
        |open(prompt, "w").write(r'''import json, os, re, sys, urllib.request
        |try:
        |    body = json.load(sys.stdin)
        |except Exception:
        |    sys.exit(0)
        |info = {}
        |try:
        |    with open(body.get("transcript_path") or "", encoding="utf-8", errors="replace") as f:
        |        text = f.read(4000000).replace(chr(92) + chr(34), chr(34))
        |    m = re.search(r'Project: "([^"\n]{1,120})" \(id: `(chan_[A-Za-z0-9]+)`\)', text)
        |    if m:
        |        info["project"], info["projectId"] = m.group(1), m.group(2)
        |    m = re.search(r'Thread title: "([^"\n]{1,200})"', text)
        |    if m:
        |        info["thread"] = m.group(1)
        |except OSError:
        |    pass
        |body.pop("prompt", None)  # the prompt text stays in the session
        |body["clauder"] = info
        |req = urllib.request.Request("https://$workerHost/hook", data=json.dumps(body).encode(), method="POST", headers={
        |    "Authorization": "Bearer " + os.environ.get("CLAUDER_HOOK_TOKEN", ""),
        |    "X-Claude-Remote-Session": os.environ.get("CLAUDE_CODE_REMOTE_SESSION_ID", ""),
        |    "Content-Type": "application/json", "User-Agent": "clauder-hook/1"})
        |try:
        |    urllib.request.urlopen(req, timeout=5).read()
        |except Exception:
        |    pass
        |''')
        |h = {"type": "http", "url": "https://$workerHost/hook", "timeout": 5,
        |     "headers": {"Authorization": "Bearer ${'$'}CLAUDER_HOOK_TOKEN",
        |                 "X-Claude-Remote-Session": "${'$'}CLAUDE_CODE_REMOTE_SESSION_ID"},
        |     "allowedEnvVars": ["CLAUDER_HOOK_TOKEN", "CLAUDE_CODE_REMOTE_SESSION_ID"]}
        |tools = "TaskCreate|TaskUpdate|TodoWrite|mcp__hearthbot__update_progress_checklist|mcp__hearthbot__request_decision|mcp__hearthbot__close_decision"
        |events = {"TaskCreated": None, "TaskCompleted": None, "PostToolUse": tools,
        |          "Stop": None, "Notification": None, "SessionEnd": None}
        |hooks = d.setdefault("hooks", {})
        |def ours(x):
        |    return "$workerHost" in json.dumps(x) or "clauder-prompt.py" in json.dumps(x)
        |for name, matcher in events.items():
        |    entry = {"hooks": [h]}
        |    if matcher:
        |        entry["matcher"] = matcher
        |    hooks[name] = [x for x in hooks.get(name, []) if not ours(x)] + [entry]
        |cmd = {"type": "command", "command": "python3 ~/.claude/clauder-prompt.py", "timeout": 10}
        |hooks["UserPromptSubmit"] = [x for x in hooks.get("UserPromptSubmit", []) if not ours(x)] + [{"hooks": [cmd]}]
        |json.dump(d, open(p, "w"), indent=1)
        |PY
        |""".trimMargin()
}
