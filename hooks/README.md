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
| `PostToolUse` on `send_later`, `set_thread_resolved` | A reminder Claude set (working until then), and a resolved thread (done) |
| `TaskCompleted` | A task was marked done |
| `UserPromptSubmit` (user-level install only) | Claude starts working again. This is a small script (`~/.claude/clauder-prompt.py`) that also reads the project's and the thread's name from the session transcript and sends them along |
| `Stop` (user-level install: the same script as the prompt hook) | Claude finished its turn: "Idle", or "Running" while background work, CI or a reminder goes on. The script adds the tokens used per model, read from the transcript |
| `Notification` | Only `notification_type` `permission_prompt` or `elicitation_dialog` counts as "Waiting for you" |
| `SessionEnd` | The session closed (a project thread that stops between wakes only pauses) |

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
