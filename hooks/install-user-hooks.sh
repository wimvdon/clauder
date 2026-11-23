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
# One script for the prompt, tool and turn-end hooks:
# - Each prompt: the project and thread names from the transcript. A message from the person also
#   starts counting the work (see Stop).
# - Each tool (PreToolUse): the tool's name and a short detail, at most every 10 seconds per
#   session, sent from a background process so the tool doesn't wait. Nothing else of the tool's
#   input is sent.
# - Each turn's end (Stop): the tokens the session used per model, and the files Claude edited and
#   the lines it added and removed since the person's last message, read from the transcript.
# When the Worker can't be reached, every hook in this container keeps to one short try (and the
# tool hook to none) for a minute, so a Worker that is down doesn't slow Claude down.
# Cloud environments keep the result of their setup script, so a session can start with hooks
# from an older installer. The Worker says so when the version below is out of date, and the
# prompt hook then runs the installer again; the new hooks apply from the next session start.
prompt = os.path.expanduser("~/.claude/clauder-prompt.py")
open(prompt, "w").write(r'''import html, json, os, re, subprocess, sys, time, urllib.error, urllib.request
VERSION = "__HOOKS_VERSION__"
try:
    body = json.load(sys.stdin)
except Exception:
    sys.exit(0)
if not isinstance(body, dict):
    sys.exit(0)
event = body.get("hook_event_name")
state = os.path.expanduser("~/.claude/clauder-state")
offline = os.path.join(state, "offline")
def age(path):
    try:
        return time.time() - os.path.getmtime(path)
    except OSError:
        return None
def write(path, text):
    try:
        os.makedirs(state, exist_ok=True)
        with open(path, "w") as f:
            f.write(text)
    except OSError:
        pass
def read(path):
    try:
        with open(path) as f:
            return f.read().strip()
    except OSError:
        return ""
def short(value, n=80):
    value = " ".join(str(value or "").split())
    return value if len(value) <= n else value[:n - 1].rstrip() + "…"
down_for = age(offline)
was_offline = down_for is not None and down_for < 60
key = re.sub(r"[^A-Za-z0-9_-]", "", os.environ.get("CLAUDE_CODE_REMOTE_SESSION_ID") or str(body.get("session_id") or ""))[:100] or "session"
since_file = os.path.join(state, key + ".since")
info = {}
if event == "PreToolUse":
    tool = str(body.get("tool_name") or "")
    ti = body.get("tool_input") if isinstance(body.get("tool_input"), dict) else {}
    last = os.path.join(state, key + ".tool")
    quiet = tool in ("TodoWrite", "TaskCreate", "TaskUpdate", "TaskGet", "TaskList", "ToolSearch") or tool.startswith("mcp__hearthbot__")
    recent = age(last)
    if not tool or quiet or was_offline or (recent is not None and recent < 10):
        sys.exit(0)
    detail = ""
    if tool == "Bash":
        detail = ti.get("description") or ""
        if not detail:
            parts = [x for x in re.split(r"\s*(?:&&|\|\||;|\|)\s*", str(ti.get("command") or "").strip()) if x and not x.startswith("cd ")]
            words = parts[0].split() if parts else []
            detail = os.path.basename(words[0]) if words else ""
    elif tool in ("Edit", "MultiEdit", "Write", "Read", "NotebookEdit"):
        detail = os.path.basename(str(ti.get("file_path") or ti.get("notebook_path") or ""))
    elif tool in ("Grep", "Glob"):
        detail = ti.get("pattern") or ""
    elif tool == "WebFetch":
        m = re.match(r"https?://([^/:?#]+)", str(ti.get("url") or ""))
        detail = m.group(1) if m else ""
    elif tool == "WebSearch":
        detail = ti.get("query") or ""
    elif tool in ("Agent", "Task"):
        detail = ti.get("description") or ""
    write(last, str(int(time.time())))
    info["tool"] = {"name": tool[:80], "detail": short(detail)} if detail else {"name": tool[:80]}
    body = {k: body.get(k) for k in ("hook_event_name", "session_id", "cwd")}
    # The tool runs at once; a child process sends the event.
    try:
        if os.fork() != 0:
            os._exit(0)
        os.setsid()
        null = os.open(os.devnull, os.O_RDWR)
        for fd in (0, 1, 2):
            os.dup2(null, fd)
    except Exception:
        pass
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
# The edits Claude made since [since]: each Edit, MultiEdit and Write result in the transcript
# carries the change as a patch, and a new file its content. Scratch files (/tmp) and Claude's own
# settings don't count.
def work(paths, since):
    files, added, removed = set(), 0, 0
    own = os.path.expanduser("~/.claude/")
    for path in paths:
        try:
            lines = open(path, encoding="utf-8", errors="replace")
        except OSError:
            continue
        for line in lines:
            if '"structuredPatch"' not in line:
                continue
            try:
                e = json.loads(line)
            except Exception:
                continue
            r = e.get("toolUseResult") if isinstance(e, dict) else None
            if not isinstance(r, dict) or not isinstance(r.get("filePath"), str) or not isinstance(r.get("structuredPatch"), list):
                continue
            if str(e.get("timestamp") or "") < since:
                continue
            fp = r["filePath"]
            if fp.startswith("/tmp/") or fp.startswith(own):
                continue
            plus = minus = 0
            for hunk in r["structuredPatch"]:
                for x in (hunk.get("lines") if isinstance(hunk, dict) else None) or []:
                    if isinstance(x, str) and x.startswith("+"):
                        plus += 1
                    elif isinstance(x, str) and x.startswith("-"):
                        minus += 1
            if r.get("type") == "create" and not r["structuredPatch"]:
                plus = len(str(r.get("content") or "").splitlines())
            if plus or minus or r.get("type") == "create":
                files.add(fp)
                added += plus
                removed += minus
    return {"since": since, "files": len(files), "added": added, "removed": removed}
if event == "Stop":
    import glob
    path = body.get("transcript_path") or ""
    paths = [path] + glob.glob(path[:-6] + "/subagents/*.jsonl" if path.endswith(".jsonl") else "")
    info["usage"] = usage(paths)
    since = read(since_file)
    if since:
        info["work"] = work(paths, since)
if event != "PreToolUse":
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
# What woke the session, from the envelope the platform wraps around each prompt: a person's
# message or a decision tap ("wake:mention"), a message from the coordinator ("relay"), or
# background events such as CI results ("notification"). Only the envelope's tag is sent.
prompt = body.get("prompt")
if isinstance(prompt, str):
    head = re.sub(r'^(\s*@"[^"]*")+', "", prompt).lstrip()
    m = re.match(r'<wake reason="([a-z-]{1,40})"', head)
    if m:
        info["source"] = "wake:" + m.group(1)
    elif head.startswith("<task-notification>"):
        info["source"] = "notification"
    elif "<coordinator-relay>" in head[:3000]:
        info["source"] = "relay"
    else:
        info["source"] = "other"
    # The person (or the coordinator for them) asked for something: count the work from here.
    if info["source"] in ("wake:spawn", "wake:mention", "wake:decision-chosen", "relay", "other"):
        write(since_file, time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime()) + ".000Z")
    # The person's own message (not a coordinator note or a background event), for the one-line
    # "Working on" the Worker has a small model write. In a project thread it is the triggering
    # message the person wrote; elsewhere the prompt is what the person typed. The Worker drops it
    # once read and never logs it.
    ask = ""
    if info["source"] in ("wake:mention", "wake:spawn"):
        for m in re.finditer(r'<message\b([^>]*)>([\s\S]*?)</message>', head):
            if 'trigger="true"' in m.group(1) and 'from="human"' in m.group(1):
                ask = html.unescape(m.group(2))
    elif info["source"] == "other" and not head.startswith("<"):
        ask = head
    ask = ask.strip()[:1000]
    if ask:
        info["ask"] = ask
body.pop("prompt", None)  # the rest of the prompt stays in the session
body["clauder"] = info
req = urllib.request.Request("https://clauder.wim-d57.workers.dev/hook", data=json.dumps(body).encode(), method="POST", headers={
    "Authorization": "Bearer " + os.environ.get("CLAUDER_HOOK_TOKEN", ""),
    "X-Claude-Remote-Session": os.environ.get("CLAUDE_CODE_REMOTE_SESSION_ID", ""),
    "X-Clauder-Hooks": VERSION,
    # The version of the hook settings this session loaded at its start (the command passes it).
    "X-Clauder-Hooks-Loaded": sys.argv[1] if len(sys.argv) > 1 else "",
    "Content-Type": "application/json", "User-Agent": "clauder-hook/1"})
# A second try when the Worker can't be reached, because a lost Stop would leave the thread
# Working. Not while the Worker was just unreachable, or for the tool hook.
short_try = was_offline or event == "PreToolUse"
outdated = False
reached = False
for attempt in range(1 if short_try else 2):
    try:
        with urllib.request.urlopen(req, timeout=2 if short_try else 4) as resp:
            resp.read()
            outdated = resp.headers.get("X-Clauder-Update") == "1"
        reached = True
        break
    except urllib.error.HTTPError:
        reached = True
        break
    except Exception:
        pass
if reached:
    if was_offline or os.path.exists(offline):
        try:
            os.remove(offline)
        except OSError:
            pass
else:
    write(offline, str(int(time.time())))
if outdated and event == "UserPromptSubmit":
    try:
        subprocess.Popen("curl -fsS https://clauder.wim-d57.workers.dev/install.sh | bash", shell=True,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    except Exception:
        pass
''')
h = {"type": "http", "url": "https://clauder.wim-d57.workers.dev/hook", "timeout": 5,
     "headers": {"Authorization": "Bearer $CLAUDER_HOOK_TOKEN",
                 "X-Claude-Remote-Session": "$CLAUDE_CODE_REMOTE_SESSION_ID",
                 "X-Clauder-Hooks-Loaded": "__HOOKS_LEVEL__"},
     "allowedEnvVars": ["CLAUDER_HOOK_TOKEN", "CLAUDE_CODE_REMOTE_SESSION_ID"]}
tools = ("TaskCreate|TaskUpdate|TodoWrite|ReadNotifications|mcp__hearthbot__update_progress_checklist"
         "|mcp__hearthbot__request_decision|mcp__hearthbot__close_decision|mcp__hearthbot__post_message"
         "|mcp__hearthbot__set_thread_resolved|mcp__claude-code-remote__send_later"
         "|mcp__claude-code-remote__subscribe_pr_activity|mcp__claude-code-remote__unsubscribe_pr_activity|mcp__claude-code-remote__watch_url|mcp__claude-code-remote__unwatch_url|mcp__github__create_pull_request"
         "|mcp__github__merge_pull_request|mcp__github__pull_request_read|mcp__github__update_pull_request|mcp__github__push_files")
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
# A git push to a pull request's branch starts its CI again. Only git push commands are sent.
hooks["PostToolUse"].append({"matcher": "Bash", "hooks": [dict(h, **{"if": "Bash(git push*)"})]})
# Settings load once, at session start: the level in the command and in the headers above says
# which hooks the session really runs, even after the script file was replaced. The level goes up
# only when the events the hooks send change, so other edits don't make live sessions look old.
cmd = {"type": "command", "command": "python3 ~/.claude/clauder-prompt.py __HOOKS_LEVEL__", "timeout": 12}
for name in ("UserPromptSubmit", "PreToolUse", "Stop"):
    hooks[name] = [x for x in hooks.get(name, []) if not ours(x)] + [{"hooks": [cmd]}]
# A copy of the settings as they were before Clauder first changed them, and a write that never
# leaves a half-written file for a session that starts at that moment.
backup = p + ".clauder-backup"
if os.path.exists(p) and not os.path.exists(backup):
    import shutil
    shutil.copyfile(p, backup)
with open(p + ".clauder-new", "w") as f:
    json.dump(d, f, indent=1)
os.replace(p + ".clauder-new", p)
PY
