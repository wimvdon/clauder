# Claude Code hooks for Clauder

`settings.json` in this folder is the hook snippet that sends task events from a Claude Code
session to the Clauder Worker. Copy its `hooks` block into `.claude/settings.json` of every
repository that should report, and replace `WORKER_HOST` with the Worker's host name
(for example `clauder.<your-subdomain>.workers.dev`).

## What each hook does

| Hook event | Why we listen |
|---|---|
| `TaskCreated` | A task was added (id, subject, description) |
| `PostToolUse` on `TaskCreate\|TaskUpdate` | Status changes (`pending` → `in_progress` → `completed`) and renames |
| `TaskCompleted` | A task was marked done |
| `Stop`, `Notification` | Claude stopped and is waiting for you |
| `SessionEnd` | The session closed |

`SessionStart` is left out on purpose: Claude Code does not send HTTP hooks for it.

## Settings the cloud environment needs

1. **Network access:** add the Worker's host name to the environment's allowed domains
   (Project settings → Cloud environment → gear icon → Network access).
2. **Environment variable** `CLAUDER_HOOK_TOKEN`: the same value as the Worker secret
   `HOOK_TOKEN`. The hook sends it as `Authorization: Bearer …`.

`CLAUDE_CODE_REMOTE_SESSION_ID` is set by Claude Code itself in cloud sessions. The hook sends it
in the `X-Claude-Remote-Session` header so the Worker can build the session's claude.ai link.
Only variables listed in `allowedEnvVars` are expanded in headers; nothing else from the
environment is sent.

## Privacy

The hook input is sent as-is. Besides task text it includes the working directory, the transcript
path and, for `Stop`, Claude's last message. It goes only to your own Worker.
