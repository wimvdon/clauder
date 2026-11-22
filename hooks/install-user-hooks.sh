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
h = {"type": "http", "url": "https://clauder.wim-d57.workers.dev/hook", "timeout": 5,
     "headers": {"Authorization": "Bearer $CLAUDER_HOOK_TOKEN",
                 "X-Claude-Remote-Session": "$CLAUDE_CODE_REMOTE_SESSION_ID"},
     "allowedEnvVars": ["CLAUDER_HOOK_TOKEN", "CLAUDE_CODE_REMOTE_SESSION_ID"]}
events = {"TaskCreated": None, "TaskCompleted": None, "PostToolUse": "TaskCreate|TaskUpdate|TodoWrite|mcp__hearthbot__update_progress_checklist",
          "Stop": None, "Notification": None, "SessionEnd": None}
hooks = d.setdefault("hooks", {})
for name, matcher in events.items():
    entry = {"hooks": [h]}
    if matcher:
        entry["matcher"] = matcher
    keep = [x for x in hooks.get(name, []) if "clauder.wim-d57.workers.dev" not in json.dumps(x)]
    hooks[name] = keep + [entry]
json.dump(d, open(p, "w"), indent=1)
PY
