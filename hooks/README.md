# Claude Code hooks for Clauder

Claude Code hooks send each task event of a session to your Clauder Worker
(`https://clauder.wim-d57.workers.dev`), which pushes it to your phone.

## Set up a cloud environment (do this once per environment)

Project threads and cloud sessions start in `/home/user`, one folder above the repository, so
Claude Code never reads a repository's own `.claude/settings.json` there. The hooks therefore go in
the user-level settings file, written by the environment's setup script.

In the Claude app or on claude.ai: open a project's settings → **Cloud environment** → the gear
next to the environment, then:

1. **Setup script:** paste the whole of [`install-user-hooks.sh`](install-user-hooks.sh) at the end,
   before a final `exit 0` if there is one. For an environment without a script, paste it as the
   whole script.
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
| `TaskCompleted` | A task was marked done |
| `UserPromptSubmit` (user-level install only) | Claude starts working again. This is a small script (`~/.claude/clauder-prompt.py`) that also reads the project's and the thread's name from the session transcript and sends them along |
| `Stop` | Claude finished its turn: "Idle", or "Running" while background work goes on |
| `Notification` | Only a permission prompt or a question form counts as "Waiting for you" |
| `SessionEnd` | The session closed |

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
