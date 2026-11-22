# Spike: what Claude Code task hooks send (milestone 1)

Captured on 22 Nov 2026 with Claude Code 2.1.330, by running `claude -p` with the hooks from
`hooks/settings.json` pointed at a local logger, in a cloud container. The run created three tasks,
moved each to in progress and then to completed, and renamed one.

## 1. Task text, id, status and title

| Event | Fields we get |
|---|---|
| `TaskCreated` | `task_id` (`"1"`, `"2"`, … per session), `task_subject`, `task_description` |
| `PostToolUse` + `TaskCreate` | `tool_input.subject/description`, `tool_response.task.id` |
| `PostToolUse` + `TaskUpdate` | `tool_input.taskId` plus only the changed fields; `tool_response.statusChange.from/to`, `updatedFields` |
| `TaskCompleted` | `task_id`, `task_subject` (the current subject, after renames), `task_description` |
| `Stop` | `last_assistant_message`, `stop_hook_active`, `background_tasks` |
| `SessionEnd` | `reason` |

Every event carries `session_id`, `transcript_path`, `cwd` and `hook_event_name`.

- **Task text: yes.** Subject and description arrive on create and on complete.
- **Status: yes**, through `PostToolUse` on `TaskUpdate` (`statusChange`). `in_progress` only
  shows up there; there is no separate hook for it.
- **List title: no.** Claude Code's task tools have no list title at all. We fall back to the
  repository or branch name (from `cwd`), as the briefing planned.
- `TaskUpdate` sends only the task id and the changed fields, so the Worker has to keep the list
  state per session (the Durable Object in milestone 2).
- Deleting a task arrives as a `TaskUpdate` with status `deleted` (to confirm on the live Worker).

Example (`PostToolUse` on `TaskUpdate`, trimmed):

```json
{
  "session_id": "3e6c272e-…",
  "hook_event_name": "PostToolUse",
  "tool_name": "TaskUpdate",
  "tool_input": { "taskId": "1", "status": "in_progress" },
  "tool_response": {
    "success": true, "taskId": "1", "updatedFields": ["status"],
    "statusChange": { "from": "pending", "to": "in_progress" }
  }
}
```

## 4. Link back to the session

`session_id` in the hook input is Claude Code's local id, not the cloud session. But cloud sessions
set `CLAUDE_CODE_REMOTE_SESSION_ID` (`cse_<id>`), and an HTTP hook can send it in a header via
`allowedEnvVars`. This worked in the spike: every request carried the header. The session's page is
`https://claude.ai/code/session_<id>` (same `<id>`); the Worker builds that URL.

## 2. Hooks in cloud sessions and project threads

A real project thread with `.claude/settings.json` on `main` sent **nothing**: threads start in
`/home/user`, one folder above the repository, so Claude Code never loads the repository's project
settings there. Hooks in the user-level `~/.claude/settings.json` do fire from any folder (checked
with a nested session started in `/home/user`). So the hooks are installed per cloud environment by
its setup script (`hooks/install-user-hooks.sh`). Re-tested in a fresh project thread on the
"Android" environment: every task event arrived within a second, each carrying the thread's
`cse_…` id. The coordinator's own task list did not report: it doesn't run with these hooks, so
the coordinator's task list is not covered for now.

## 3. Network access

The cloud environment's proxy blocks `*.workers.dev` by default
(`403 … no rule or network setting allows this host`). After adding
`clauder.wim-d57.workers.dev` to the environment's allowed hosts, the Worker answers from inside a
session.
