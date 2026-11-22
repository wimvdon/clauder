#!/bin/bash
# Installs the Clauder task hooks in ~/.claude/settings.json (user level), so every
# Claude Code session reports its task list, whatever folder it starts in.
# Paste this whole file at the end of a cloud environment's setup script (before any
# final "exit 0"). Safe to run more than once: it replaces its own entries only.
mkdir -p ~/.claude
python3 - <<'PY' || echo "WARN: Clauder hooks not installed"
import json, os
p = os.path.expanduser("~/.claude/settings.json")
try:
    d = json.load(open(p))
except Exception:
    d = {}
# On each prompt: the project and thread names from the transcript, sent with the prompt event.
prompt = os.path.expanduser("~/.claude/clauder-prompt.py")
open(prompt, "w").write(r'''import json, os, re, sys, urllib.request
try:
    body = json.load(sys.stdin)
except Exception:
    sys.exit(0)
info = {}
try:
    with open(body.get("transcript_path") or "", encoding="utf-8", errors="replace") as f:
        text = f.read(4000000).replace(chr(92) + chr(34), chr(34))
    m = re.search(r'Project: "([^"\n]{1,120})" \(id: `(chan_[A-Za-z0-9]+)`\)', text)
    if m:
        info["project"], info["projectId"] = m.group(1), m.group(2)
    m = re.search(r'Thread title: "([^"\n]{1,200})"', text)
    if m:
        info["thread"] = m.group(1)
except OSError:
    pass
body.pop("prompt", None)  # the prompt text stays in the session
body["clauder"] = info
req = urllib.request.Request("https://clauder.wim-d57.workers.dev/hook", data=json.dumps(body).encode(), method="POST", headers={
    "Authorization": "Bearer " + os.environ.get("CLAUDER_HOOK_TOKEN", ""),
    "X-Claude-Remote-Session": os.environ.get("CLAUDE_CODE_REMOTE_SESSION_ID", ""),
    "Content-Type": "application/json", "User-Agent": "clauder-hook/1"})
try:
    urllib.request.urlopen(req, timeout=5).read()
except Exception:
    pass
''')
h = {"type": "http", "url": "https://clauder.wim-d57.workers.dev/hook", "timeout": 5,
     "headers": {"Authorization": "Bearer $CLAUDER_HOOK_TOKEN",
                 "X-Claude-Remote-Session": "$CLAUDE_CODE_REMOTE_SESSION_ID"},
     "allowedEnvVars": ["CLAUDER_HOOK_TOKEN", "CLAUDE_CODE_REMOTE_SESSION_ID"]}
tools = "TaskCreate|TaskUpdate|TodoWrite|mcp__hearthbot__update_progress_checklist|mcp__hearthbot__request_decision|mcp__hearthbot__close_decision|mcp__hearthbot__post_message"
events = {"TaskCreated": None, "TaskCompleted": None, "PostToolUse": tools,
          "Stop": None, "Notification": None, "SessionEnd": None}
hooks = d.setdefault("hooks", {})
def ours(x):
    return "clauder.wim-d57.workers.dev" in json.dumps(x) or "clauder-prompt.py" in json.dumps(x)
for name, matcher in events.items():
    entry = {"hooks": [h]}
    if matcher:
        entry["matcher"] = matcher
    hooks[name] = [x for x in hooks.get(name, []) if not ours(x)] + [entry]
cmd = {"type": "command", "command": "python3 ~/.claude/clauder-prompt.py", "timeout": 10}
hooks["UserPromptSubmit"] = [x for x in hooks.get("UserPromptSubmit", []) if not ours(x)] + [{"hooks": [cmd]}]
json.dump(d, open(p, "w"), indent=1)
PY
