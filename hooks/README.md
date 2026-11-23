# Claude Code hooks for Clauder

Claude Code hooks send each task event of a session to your Clauder Worker
(`https://clauder.wim-d57.workers.dev`), which pushes it to your phone.

## Set up a cloud environment (do this once per environment)

Project threads and cloud sessions start in `/home/user`, one folder above the repository, so
Claude Code never reads a repository's own `.claude/settings.json` there. The hooks therefore go in
the user-level settings file, written by the environment's setup script.

In the Claude app or on claude.ai: open a project's settings → **Cloud environment** → the gear
next to the environment, then:

1. **Setup script:** add this line at the end, before a final `exit 0` if there is one (for an
   environment without a script, it can be the whole script):
   `curl -fsS https://clauder.wim-d57.workers.dev/install.sh | bash || echo "WARN: Clauder hooks not installed"`
   The Worker serves [`install-user-hooks.sh`](install-user-hooks.sh) at `/install.sh`, pointed
   at itself, so the line stays the same when the hooks change. After changing the script, run
   `node scripts/sync-install.mjs` in `worker/` (a test checks the two match).
2. **Environment variables:** add `CLAUDER_HOOK_TOKEN` with the same value as the GitHub secret of
   the same name (the Worker's password).
3. **Network access:** add `clauder.wim-d57.workers.dev` under allowed domains.

The script runs when a fresh container is built, so the hooks start working in sessions and threads
started after the change. Your current environments: Android, Default, Electron,
Twenty CRM.

## Local Claude Code on your computer

A session started inside a repository reads that repository's `.claude/settings.json`. This
repository has one (a copy of [`settings.json`](settings.json) with the Worker's host filled in).
For other repositories, either copy that file in, or run `install-user-hooks.sh` once on your
computer to install the hooks for all of them. Set `CLAUDER_HOOK_TOKEN` in your shell.

## What each hook does

| Hook event | Why we listen |
|---|---|
| `TaskCreated` | A task was added (id, subject, description) |
| `PostToolUse` on `TaskCreate\|TaskUpdate` | Status changes (`pending` → `in_progress` → `completed`) and renames |
| `PostToolUse` on `TodoWrite` and the checklist tool | To-dos, and a project thread's checklist (its steps) |
| `PostToolUse` on `request_decision`, `close_decision` | A project thread asks you to choose: "Waiting for you" |
| `PostToolUse` on `post_message` | A project thread replies: the first link it attaches. Its text is not read |
| `PostToolUse` on the GitHub PR tools, `subscribe_pr_activity`, `ReadNotifications` | The pull request Claude works on and its CI: waiting on CI counts as working |
| `PostToolUse` on `send_later`, `watch_url`, `unwatch_url`, `unsubscribe_pr_activity`, `set_thread_resolved` | A reminder Claude set (working until then), and a resolved thread (done) |
| `PostToolUse` on `Bash` with `"if": "Bash(git push*)"`, and `mcp__github__push_files` | Claude pushed to its pull request, so CI runs again: working, not waiting for you. Only `git push` commands are sent |
| `TaskCompleted` | A task was marked done |
| `UserPromptSubmit` (user-level install only) | Claude starts working again. This is a small script (`~/.claude/clauder-prompt.py`) that also reads the project's and the thread's name from the session transcript and sends them along |
| `PreToolUse` (user-level install: the same script) | What Claude runs now, shown while it works. At most every 10 seconds per session, the script sends the tool's name and one short detail: the description Claude gave a shell command (else the program's name), a file's name, a search pattern, a web host or query, a helper's description. Nothing else of the tool's input. It sends from a background process, so the tool doesn't wait |
| `Stop` (user-level install: the same script as the prompt hook) | Claude finished its turn: "Running" while something it set up will wake it (a reminder, a cron, CI on a subscribed PR, a watched URL, helpers), else "Waiting for you". The script adds the tokens used per model, and the number of files Claude edited and the lines it added and removed since your last message, read from the edit results in the transcript (files in `/tmp` and `~/.claude` don't count) |
| `Notification` | `permission_prompt`, `elicitation_dialog` and `elicitation_url_dialog` (a connector asks you to open a page) count as "Waiting for you". `quota_auto_resume_stale` or `_disabled` (Claude Code stopped waiting for a usage limit) shows "Stopped by an error"; `quota_auto_resume_fired` means it goes on |
| `StopFailure` | An API error (rate limit, overloaded server, billing, …) ended the turn instead of `Stop`: "Stopped by an error", with the error text, instead of staying "Working" |
| `PermissionDenied` (the same script) | Auto mode refused a tool call: the "now" line shows the policy it hit. The script leaves the tool's input out |
| `PostToolUseFailure` on `Bash` with `"if": "Bash(git push*)"` | A `git push` failed: the "now" line says so, and CI doesn't count as started again |
| `SubagentStart` and `SubagentStop` (the same script), matcher `.+` | Helpers that run now, counted while they work. `.+` leaves out Claude Code's own internal agents; the script leaves a helper's reply out |
| `PreCompact` and `PostCompact` (the same script) | "Compacting the conversation" on the "now" line. The script leaves the summary and instructions out |
| `SessionStart` (the same script, in the background) and `PostModelSwitch` | The model the session runs, shown on the session card. `SessionStart` takes command hooks only |
| `SessionEnd` | The session closed (a project thread that stops between wakes only pauses) |

When the Worker can't be reached, the script writes `~/.claude/clauder-state/offline`. For the next
minute every hook in that container makes one 2-second try instead of two 4-second ones, and the
tool hook doesn't send at all, so a Worker that is down doesn't slow Claude down. The installer
keeps a copy of `~/.claude/settings.json` as it was before Clauder first changed it
(`settings.json.clauder-backup`).

Claude Code loads these settings once, at session start. The hook command and the HTTP hooks'
headers carry the hooks' level (`X-Clauder-Hooks-Loaded`, such as `L4`), so the Worker knows which
hooks a session really runs. The level goes up only when the events change
(`worker/src/hooks-level.txt`). A session on older settings can't report every wake source, so after its turn
it shows Idle instead of Waiting for you. `hooks/settings.json` doesn't carry the version, so
sessions that use it also show Idle.

`SessionStart` is left out on purpose: Claude Code does not send HTTP hooks for it.

`CLAUDE_CODE_REMOTE_SESSION_ID` is set by Claude Code itself in cloud sessions. The hook sends it in
the `X-Claude-Remote-Session` header so the Worker can build the session's claude.ai link. Only
variables listed in `allowedEnvVars` are expanded in headers; nothing else from the environment is
sent. A session without `CLAUDER_HOOK_TOKEN` is refused by the Worker and carries on as normal.

## Privacy

The hook input is sent as-is. Besides task text it includes the working directory, the transcript
path and, for `Stop`, Claude's last message. The prompt script adds the project's and thread's
names and ids and leaves out the prompt's text. The repository-level `settings.json` has no
`UserPromptSubmit` hook, so prompts are never sent. It goes only to your own Worker.

## Keeping the hooks current

Cloud environments keep the result of their setup script, so a session can start with hooks from
an older installer. The prompt hook sends its installer version in `X-Clauder-Hooks`; when the
Worker answers `X-Clauder-Update: 1`, the hook runs `/install.sh` again in the background. Claude
Code reads hooks when a session starts, so the new ones apply from the next start (in a project
thread, the next wake).
