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
# On each turn's end (Stop): the tokens the session used per model, read from the transcript.
# Cloud environments keep the result of their setup script, so a session can start with hooks
# from an older installer. The Worker says so when the version below is out of date, and the
# prompt hook then runs the installer again; the new hooks apply from the next session start.
prompt = os.path.expanduser("~/.claude/clauder-prompt.py")
open(prompt, "w").write(r'''import json, os, re, subprocess, sys, urllib.request
VERSION = "__HOOKS_VERSION__"
try:
    body = json.load(sys.stdin)
except Exception:
    sys.exit(0)
info = {}
def usage(paths):
    seen = {}
    for path in paths:
        try:
            lines = open(path, encoding="utf-8", errors="replace")
        except OSError:
            continue
        for line in lines:
            if '"usage"' not in line:
                continue
            try:
                m = json.loads(line).get("message") or {}
            except Exception:
                continue
            u = m.get("usage")
            if isinstance(u, dict) and m.get("id"):
                seen[m["id"]] = (m.get("model") or "", u)
    totals = {}
    for model, u in seen.values():
        t = totals.setdefault(model, {"model": model, "input": 0, "output": 0, "cacheRead": 0, "cacheWrite5m": 0, "cacheWrite1h": 0})
        c = u.get("cache_creation") or {}
        write = u.get("cache_creation_input_tokens") or 0
        hour = c.get("ephemeral_1h_input_tokens") or 0
        t["input"] += u.get("input_tokens") or 0
        t["output"] += u.get("output_tokens") or 0
        t["cacheRead"] += u.get("cache_read_input_tokens") or 0
        t["cacheWrite1h"] += hour
        t["cacheWrite5m"] += max(write - hour, 0)
    return list(totals.values())
if body.get("hook_event_name") == "Stop":
    import glob
    path = body.get("transcript_path") or ""
    info["usage"] = usage([path] + glob.glob(path[:-6] + "/subagents/*.jsonl" if path.endswith(".jsonl") else ""))
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
    "X-Clauder-Hooks": VERSION,
    "Content-Type": "application/json", "User-Agent": "clauder-hook/1"})
try:
    with urllib.request.urlopen(req, timeout=5) as resp:
        resp.read()
        outdated = resp.headers.get("X-Clauder-Update") == "1"
    if outdated and body.get("hook_event_name") == "UserPromptSubmit":
        subprocess.Popen("curl -fsS https://clauder.wim-d57.workers.dev/install.sh | bash", shell=True,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
except Exception:
    pass
''')
h = {"type": "http", "url": "https://clauder.wim-d57.workers.dev/hook", "timeout": 5,
     "headers": {"Authorization": "Bearer $CLAUDER_HOOK_TOKEN",
                 "X-Claude-Remote-Session": "$CLAUDE_CODE_REMOTE_SESSION_ID"},
     "allowedEnvVars": ["CLAUDER_HOOK_TOKEN", "CLAUDE_CODE_REMOTE_SESSION_ID"]}
tools = ("TaskCreate|TaskUpdate|TodoWrite|ReadNotifications|mcp__hearthbot__update_progress_checklist"
         "|mcp__hearthbot__request_decision|mcp__hearthbot__close_decision|mcp__hearthbot__post_message"
         "|mcp__hearthbot__set_thread_resolved|mcp__claude-code-remote__send_later"
         "|mcp__claude-code-remote__subscribe_pr_activity|mcp__claude-code-remote__unsubscribe_pr_activity|mcp__claude-code-remote__watch_url|mcp__claude-code-remote__unwatch_url|mcp__github__create_pull_request"
         "|mcp__github__merge_pull_request|mcp__github__pull_request_read|mcp__github__update_pull_request")
events = {"TaskCreated": None, "TaskCompleted": None, "PostToolUse": tools,
          "Notification": None, "SessionEnd": None}
hooks = d.setdefault("hooks", {})
def ours(x):
    return "clauder.wim-d57.workers.dev" in json.dumps(x) or "clauder-prompt.py" in json.dumps(x)
for name, matcher in events.items():
    entry = {"hooks": [h]}
    if matcher:
        entry["matcher"] = matcher
    hooks[name] = [x for x in hooks.get(name, []) if not ours(x)] + [entry]
cmd = {"type": "command", "command": "python3 ~/.claude/clauder-prompt.py", "timeout": 10}
for name in ("UserPromptSubmit", "Stop"):
    hooks[name] = [x for x in hooks.get(name, []) if not ours(x)] + [{"hooks": [cmd]}]
json.dump(d, open(p, "w"), indent=1)
PY
