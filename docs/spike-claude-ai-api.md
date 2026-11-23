# Spike: claude.ai's private API for project threads

_2026-11-23, from Claude for Android 1.261120.19 (decompiled with jadx). Not a public API: it can change with any Claude update._

The Claude Android app reads every project thread's state, including threads without hooks and
threads resolved by hand, from private claude.ai endpoints. Clauder's Settings → claude.ai → Test
calls them and reports what comes back (`claudeai/Probe.kt`).

## Sign-in

- Base URL `https://claude.ai/` (the `v1/code/...` calls sit at the site root, not under `/api/`;
  `/api/` gave 404 on every call in the first live test, 0.3.2). Signed with the claude.ai `sessionKey` cookie (the same
  login as the website; the app also knows `sessionKeyV2`, `sessionKeyV3`, `lastActiveOrg`). The
  app gets it from a claude.ai sign-in page in a WebView and reads it with `CookieManager`.
- Every call also carries the headers the app's sessions client adds: `X-Organization-Uuid`
  (the account's organization), `Anthropic-Version: 2023-06-01` and
  `Anthropic-Beta: ccr-byoc-2025-07-29`. Clauder takes the organization from the `lastActiveOrg`
  cookie the sign-in page sets, or else the first one in `GET api/organizations` (the web app's
  own endpoint, which does sit under `/api/`).
- The cookie is a full account login. Signing out of claude.ai ends it.
- From a server (this container) every call gets Cloudflare's bot check (HTTP 403,
  `cf-mitigated: challenge`). We don't try to get past it, so the calls run on the phone.

## Endpoints

| Call | Returns |
| --- | --- |
| `GET v1/code/channels` (`cursor`, `limit`) | `data[]` projects (`id`, `name`, `has_working_session`, `needs_attention_thread_count`, `last_activity_at`, `has_unread`, …), `next_cursor` |
| `GET v1/code/channels/{id}/threads` (`status`, `cursor`, `limit`) | `sections[]`: `status`, `count`, `data[]` thread roots, `next_cursor` |
| `GET v1/code/channels/{id}/timeline` (`limit`, `cursor`, `thread_roots_only`, `bound_only`, `include_thread_progress_rows`, …) | the project chat: `data[]` messages, `next_cursor`, `draft`, `linked_sessions` |
| `GET v1/code/channels/{id}/messages/stream` (SSE; `scope` = `channel`, `timeline` or `thread`; `thread_id`, `from_id`, `from=tail`, `thread_progress_rows=1`) | live events, below |
| `GET v1/code/sessions/watch` (SSE; `resume_token`, `session_ids`, `tags`, `include_trigger_sessions`) | session records (`SessionV2`) and deletes (`id`) |
| `GET v1/code/sessions` (`cursor`, `limit`, `statuses`, `tags`) | `data[]` sessions, `next_cursor`, `resume_token` |
| `GET v1/code/notifications/summary` | `unseen_count`, `newest_cursor` |
| `GET v1/code/notifications` (`cursor`, `limit`) | `data[]` (`kind`, `notif_type`, `channel_id`, `thread_id`, `session_id`, `read`, `seen`, `preview`, `digest_counts` = `waiting`, `finished`) |

**Message / thread root** (`ChannelMessageWire`): `id`, `thread_root_id`, `in_timeline`,
`author_account_id`, `author_agent_id`, `author_session_id`, `body`, `server_notice`,
`created_at`, `edited_at`, `reply_count`, `last_reply_at`, `bound_sessions[]` (`id`, `status`,
`title`, `machine_state`, `device_route`, `purpose`, `created_at`), `has_unread`,
`thread_status`, `thread_waits_on_live_prompt`, `thread_preview`, `resolved_at`, `display_name`,
`attached_outputs`, `card_state`, and more.

**Stream events** (`scope=channel`): `channel_message`, `channel_message_updated`,
`channel_message_reactions`, `thread_starting`, `thread_session_bound`, `thread_resolved`
(`thread_root_id`, `resolved_at`), `thread_deleted`, `session_activity` (`session_id`, `working`,
`ended` = `done`/`failed`, `reason`, `thread_root_id`, `turn_started_at`), `session_requires_action`
(`pending_actions`), `session_recent_tools`, `session_rate_limit`, `session_partial_reply`,
`session_model_started`, `member_presence`.

**Session** (`SessionV2`): `status` (`pending`, `requires_action`, `running`, `idle`, `archived`),
`status_bucket` (`blocked`, `review_ready`, `working`, `completed`, `failed`), `worker_status`,
`connection_status`, `post_turn_summary` (`status_category`, `needs_action`, `recent_action`,
`description`), `title`, `last_event_at`, `unread`.

## States

`thread_status` is one of `working`, `needs_input`, `idle`, `resolved`, `failed`, `archived`.

| Clauder | claude.ai |
| --- | --- |
| Working | `working`, or `session_activity.working` |
| Waiting for you | `needs_input`, `thread_waits_on_live_prompt`, or `session_requires_action` |
| Done | `resolved` / `thread_resolved` (also a resolve by hand) |
| Failed | `failed`, or `session_activity.ended = failed` |
| Inactive | `idle` |
| Gone | `thread_deleted`, `archived` |

A project chat reply from Claude: a timeline message with `author_session_id` or
`author_agent_id`, newer than the person's last message (`author_account_id`).

## Still to confirm live (the Test report)

- Whether claude.ai accepts the cookie and headers from Clauder's own client (the 0.3.2 test used
  the wrong base, `/api/`, and no headers: 404 everywhere).
- How `status` is written on the threads call; the `sessions/watch` event names.
- Whether `bound_sessions[].id` is the session id the hooks send (the key to merge both sources).
- How long a `sessionKey` lives; whether Google sign-in works in the WebView.
