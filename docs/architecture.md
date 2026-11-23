# Architecture

## This app
Clauder shows the task list of every running Claude Code session on the phone.

- **Data flow:** Claude Code hooks POST each task event to the Worker (`worker/`). The Worker keeps
  each session's list and pushes every change to the registered phones as an FCM data message.
  `push/ClauderMessagingService` parses it (`tasks/TaskList.fromPushData`), drops pushes older
  than the last one per session (`SeqGate`) and shows it (`tasks/TaskNotifier`).
- **Steps and tasks:** a project thread keeps a progress checklist (`update_progress_checklist`:
  a heading, then ✓/✱/○ lines). Those are its **steps**, the plan the user follows, and the
  heading is the session's title. Claude Code's own work list (TaskCreate/TaskUpdate, and the
  older TodoWrite) are its **tasks**. The Worker keeps both apart; progress, the notification and
  the widget count the steps when there are any, else the tasks (`TaskList.items`). The Tasks
  screen shows the steps and folds the tasks away under them.
- **States:** States come from raw hook data (event names, `notification_type`, tool inputs
  and results) first. Reply text is read only in the one case hook data can't settle (see "Reply
  check" below), and never with keyword rules. A turn that ends can only go on when something wakes
  the session: something Claude set up itself, or the user. So after Stop
  (`worker/src/tasklist.ts`):
  - **Running** while a wake source is armed: a `send_later` reminder still ahead, an in-session
    cron (Stop's `session_crons`), a PR Claude subscribed to (`subscribe_pr_activity`, until
    `unsubscribe_pr_activity`) whose CI still runs, a `watch_url` webhook (until `unwatch_url`), or
    helpers and shells still running (Stop's `background_tasks`).
  - **Done** when the thread was resolved (`set_thread_resolved`); during a turn also when every
    step is completed.
  - **Waiting for you** in every other case, because only the user can wake it. That includes an
    open decision card or a `permission_prompt` / `elicitation_dialog` notification, which carry
    `need` (the card's question or the notification's message).
  A project thread's session stops between wakes (`SessionEnd` reason "other") and pauses:
  watches, crons and helpers end with the session, while a reminder and a PR subscription live on
  the server, so without those the thread waits on the user. Every wake (a message, a CI event, a
  reminder) is a new prompt and makes the session Running again, except that an open decision card
  or a question from the reply check stays Waiting for you through wakes the person didn't cause
  (CI events, reminders). A question from the reply check ends with a person's message, a decision
  tap or a coordinator message. An open decision card ends only with a decision tap or
  `close_decision` (Claude closes it when the person answers in words), so a side question from the
  person doesn't hide it. A background wake injected into a running turn also keeps the reply Claude
  sent before it, so the check at the turn's end still reads it. **Idle** means nothing known is
  armed but the Worker can't tell: Claude Code loads hook settings once, at session start, so a
  session started with older settings may set up a wake the Worker never hears of. The hooks say
  which settings the session loaded (`X-Clauder-Hooks-Loaded`, in the command's argument and the
  HTTP hooks' headers): a level (`L4`, from `worker/src/hooks-level.txt`) that goes up only when the
  events the hooks send change, so other installer edits don't make live sessions look old
  (`worker/src/hooks.ts`; the hash `5ba5b56f378b` from before levels counts as level 1). Sessions
  on settings without the header are pushed with `hooks: "old"`, and the phone shows them with a
  message to paste into the thread that has Claude run the installer once.
  A thread whose checklist was already complete when the person wrote again stays Running until
  the checklist changes (`staleFinish`), instead of Done. The one-line summary stays through
  background wakes (CI, reminders) and is cleared only by the person's message.
  **CI after a push:** a PostToolUse hook with `"if": "Bash(git push*)"` (only `git push` commands
  reach the Worker) and `mcp__github__push_files` set the followed open PR's CI back to running.
  Any `check_suite.*`, `check_run.*` or `workflow_run.*` event with a failed conclusion marks it
  failed.
  **CI from GitHub webhooks** (`worker/src/github.ts`): when GitHub is connected, GitHub sends
  `workflow_run`, `check_suite`, `check_run`, `pull_request` and `pull_request_review` webhooks to
  `POST /github`, signed with the webhook secret (`X-Hub-Signature-256`, checked before anything
  else). The Hub keeps a track per PR (`github_prs`): its head commit and one check group per
  GitHub Actions workflow (`wf:<workflow id>`, a re-run's higher attempt replaces the run before
  it) or per other app's check suite (`cs:<suite id>`; Actions' own check events are left to the
  workflow, so CI doesn't pass while a later job is still to come). CI is failed when a group
  failed, running while one runs, passed when all finished. A push (`synchronize`) or a run for a
  newer commit starts the groups over; runs for older commits are ignored. Each change goes to the
  sessions whose followed PR has that URL (`Session.applyPr`), and a session that starts to follow
  a PR takes what the Hub already knows of it. Only the PR's CI, state and review change, never the
  session's state: a PR subscription wakes Claude, and that turn's hooks decide. **Runs Claude
  starts itself** (a release with `actions_run_trigger`) belong to no PR: the PreToolUse hook always
  sends that tool call with `owner/repo workflow` (or `run:<id>` for a re-run), the Hub keeps it for
  10 minutes (`github_dispatches`), and the first `workflow_dispatch` run of that workflow that
  starts within 5 minutes is tied to the session (`github_runs`, 2 days). Hooks too old to send the
  detail match on the session's folder name and the time. The session gets the run's name, link and
  state (`run`), the app shows it and sends a one-off alert on the "Runs Claude started" channel when
  it passes or fails. **Connecting:**
  the app asks `POST /github/connect` for a one-time link (30 minutes). The link's page hands
  GitHub an app manifest (GitHub's manifest flow): the person taps Create GitHub App, GitHub sends
  them back to `/github/callback` with a code, the Worker trades it for the app's webhook secret
  (kept in the Hub; the private key is dropped) and sends them on to install the app on their
  repositories. `GET /github` says whether it is connected and when the last webhook came.
  In the app, Settings → GitHub (shown once the Worker is connected) reads `GET /github`
  (`settings/GithubStatus`), and **Connect GitHub** asks for the one-time link and opens it in the
  browser; once connected, **Choose repositories** opens the app's install page. The latest review
  verdict comes with the PR (`r` in the push: `approved` or `changes_requested`) and shows as
  "Approved" or "Changes requested" after the PR line while the PR is open.
  **Reply check:** a turn can end both waiting on CI (or a reminder, a watched URL, helpers) and
  asking the user something, such as "Pick one: T1, T2 or T3". When a turn ends Running or Idle,
  the Worker sends the end of the turn's last reply (the last `post_message` text, else Stop's
  `last_assistant_message`) to Cloudflare Workers AI (`worker/src/ask.ts`, binding `AI`). If the
  model answers that the reply waits on the user and names the question, the session becomes
  Waiting for you with the question as `need`; a "waiting" answer without a question doesn't
  count. The answer only counts for the turn it read (the reply it read must still be
  the one waiting, until the next prompt; other events in between don't drop it); if the model
  fails, nothing changes. The same answer says whether the reply reports the work as finished
  with nothing left for the user (such as "PR merged, 0.2.11 is released"). If so, and the turn
  ended Waiting for you with nothing armed, no open card or question and current hooks, the
  session is marked resolved and shows Done, the same as Mark resolved. That happens only in
  Clauder: the Worker can't resolve the thread in the Claude app. The person's next message
  reopens it. The reply text is kept in the Durable Object only
  until the turn ends and never goes to the phone or `/sessions`.
  **AI budget:** Workers AI's free allocation is 10,000 neurons a day, reset at 00:00 UTC. The
  Hub Durable Object counts each call's neurons per UTC day (`worker/src/budget.ts`, from the
  `usage` token counts the model returns, else an estimate of four characters a token);
  `GET /ai` shows today's count. A read that can't change the state (an open card, permission
  or question, a resolved or ended thread; `needsStateRead`) asks only for the summary line, of
  the smaller `@cf/meta/llama-3.1-8b-instruct-fp8-fast`; every other read goes to the 70B model.
  From 7,000 neurons only reads that can change the state still run; from 9,700, or once Workers
  AI refuses a call for the allocation (error 3036), nothing runs until the next UTC day. A turn
  left unread then carries `aiPausedOn` (its UTC day), and the session card says the summary is
  back at midnight UTC in local time.
  **Working on:** for the person's own message (`wake:mention` or `wake:spawn` in a project
  thread, the typed prompt elsewhere), the prompt hook sends its text as `clauder.ask` (at most
  1,000 characters). The Worker keeps it as `askRequest` (never logged, never sent to the phone)
  and has the 8B model write a "Working on" line, shown as the summary until the turn's own
  summary replaces it, and for a session outside a project a title (`titleFromAsk`) instead of the
  folder name; a checklist heading still wins. These reads only run below 7,000 neurons, and a
  read that arrives after the turn ended, or after a newer prompt, is dropped.
  The phone also shows a Running session that has been silent for 12 hours as Idle
  (`TaskList.shown`).
  A resolved thread stays Done until a person's message, a decision tap or a coordinator message
  wakes it: the prompt hook sends which kind of envelope the platform wrapped around the prompt
  (`clauder.source`: `wake:mention`, `wake:decision-chosen`, `relay`, `notification`, `other`),
  and, apart from the person's own message for the "Working on" line (below), never the prompt's text; and CI events and other background wakes don't reopen it.
  A session that ended with open steps reads "Ended early".
- **What a session waits on, and what it made** (`worker/src/extras.ts`): the hooks also match
  the GitHub tools (create, read, merge and update a pull request), `subscribe_pr_activity`,
  `ReadNotifications` (CI events delivered to the thread: only an event's kind and its data count:
  `check_suite.completed` means no suite is running or failed, and `pull_request.closed` carries
  its `outcome`), `send_later`, `set_thread_resolved`
  and the reply's `attached_outputs`. The Worker keeps the session's pull request with its CI
  state, the first https link or Artifact the last reply attached, when a reminder fires, and
  marks a resolved thread Done. These are the wake sources the States above count. The Stop hook is a command
  (`~/.claude/clauder-prompt.py`, the same script as the prompt hook) that totals the tokens per
  model from the transcript (and its subagents); the Worker prices them with the API price table
  in `extras.ts` (an estimate: subscriptions don't bill per token).
- **Errors, helpers, compaction and the model** (hooks level L4, `worker/test/events.test.ts`):
  `StopFailure` ends a turn on an API error instead of `Stop`, so the Worker sets `waiting` with
  `needKind: "error"`, `error` (its type) and `need` (the error text); the phone labels it "Stopped
  by an error". A usage limit Claude Code won't wait out (`quota_auto_resume_stale`/`_disabled`)
  does the same with `error: "usage_limit"`; `quota_auto_resume_fired` makes it `running` again.
  Any later prompt or work clears it. `elicitation_url_dialog` waits like a question form.
  `SubagentStart`/`SubagentStop` keep the running helper ids (`agents`, not pushed) and count them
  in `helpers`. `PermissionDenied`, `PreCompact` and a failed `git push` (`PostToolUseFailure`) set
  `doing` to `clauder:blocked` (detail: the policy), `clauder:compacting` or `clauder:push-failed`,
  which the phone words on the "now" line (`Doing.Kind`). `SessionStart` (async command hook) and
  `PostModelSwitch` give `model`; every event with them gives `effort` and `mode`
  (`permission_mode`). The card shows "Opus 5.5 · high effort", plus "Plan mode" (`ModelName`).
- **What Claude does now, and what it changed** (`worker/src/activity.ts`, `tasks/Activity.kt`):
  a PreToolUse command hook (the same script) sends, at most every 10 seconds per session and from a
  background process, the tool's name and one short detail (the description Claude gave a shell
  command, else the program; a file's name; a pattern; a host or query). The Worker keeps it as
  `doing` until the turn ends, and the phone shows it as the Live Update's second part while a
  session works ("Now: step · Editing TaskList.kt"). A person's message (the same sources that
  reopen a thread, plus `wake:spawn` and `other`) makes the script note the time on the session's
  machine (`~/.claude/clauder-state/<session>.since`); at each Stop it counts the files and the
  added and removed lines in the transcript's edit results since then (`structuredPatch` of Edit,
  MultiEdit and Write, a new file's content; not `/tmp` or `~/.claude`). The Worker keeps it as
  `work` until the person writes again, and the phone shows it on a finished session and the
  session card: "12 min · 3 files · +120 −30". Shell commands that change files aren't counted.
- **Setup check:** `GET /status` says when the last hook event reached the Worker (from the debug
  log) and whether its session runs current hook settings. Settings → Cloud environments shows it
  (`settings/SetupCheck.kt`).
- **When the Worker is down:** the script notes it (`~/.claude/clauder-state/offline`), and for a
  minute every hook in that container makes one 2-second try instead of two 4-second ones, and the
  tool hook sends nothing. The installer backs up `~/.claude/settings.json` once
  (`settings.json.clauder-backup`) and writes it atomically.
- **Notifications:** one per session by default. With "One notification for all sessions"
  (`Settings.summaryNotification`, backed up) the phone posts one ongoing summary
  (`Summary`, `TaskNotifier.showSummary`) and only the sessions that wait for you get their own,
  alerting, notification (`NotificationPlan`). The summary is then the only Live Update: those
  "Waiting for you" notifications are plain ones, not ongoing or promoted, so they can be swiped
  away (`LiveUpdate.from(summaryMode = true)`). They also have no progress bar and no status bar
  chip (`LiveUpdate.plain`), so on Android 16 they don't look like a second Live Update.
  "Alert when Claude waits" (`Settings.waitingAlerts`, backed up, on by default) can be turned off
  for people who rely on the Claude app's own notifications: a session that waits for you then
  goes on the quiet progress channel, still saying "Waiting for you" (or, in summary mode, shows
  only in the summary) (`NotifyPrefs`, `NotificationPlan.alerts`). On Android 16 the summary's progress bar has one
  block per session (`SummaryBar`): waiting for you, working, or holding on CI, a reminder or
  helpers, each in its own colour (`res/values*/colors.xml`), with a dot at how far that session is.
  Its status bar chip (`Chip`) reads "1 waiting" when any session waits for you, else "2 working";
  a session's own Live Update chip reads "3/5", "Waiting" or "Done". A Wear OS watch (Wear OS 7
  and later) shows a bridged Live Update in its tray as the app name plus this chip, so the chip
  says what is going on instead of a bare count. A Live Update can't be
  expanded, so its one collapsed line carries what matters: for a session, what Claude is on or
  asks plus what it waits on; for the summary, the sessions' projects (else repository, else thread title) in the
  blocks' order, each once with "×2" when it has several sessions (or, with one session, what that
  session is doing), with the steps done as sub text. A notification offers buttons for the attached page
  and the pull request (opened through `OpenLinkActivity`: claude.ai in the Claude app, other https
  links in their own app). Done is always silent.
- **Quick Settings tile** (`tile/ClauderTileService`): shows the same summary line when Quick
  Settings opens, and opens Clauder when tapped. Settings offers to add it on Android 13 and later.
- **Projects:** the user-level hooks run `~/.claude/clauder-prompt.py` on each prompt. It reads
  the claude.ai project and thread names from the session's transcript and sends them with the
  prompt event (of the prompt itself only the person's own message, see "Working on"). The Tasks screen has three sections, in this order:
  Needs input (waiting for you), Running (working, CI waits included) and Inactive (idle or
  finished) (`SessionBoard.board`, `SessionBoard.status`). Inside each, sessions are grouped under
  the project's name, else the repository's, as a plain header that doesn't fold. Needs input and
  Running show full cards with their steps and a segmented progress bar (`SegmentBar`, one segment
  per step). Only Inactive folds: it starts folded and opens to one-line rows (title, state, count)
  (`SessionBoard.rows`). In the shade, idle and finished sessions are bundled per project with a summary
  notification (`LiveUpdate.bundle`, `TaskNotifier.refreshBundles`); working and waiting sessions
  stay on their own so they keep their Live Update. The widget shows the name too. claude.ai doesn't give the project's icon or colour to the session.
- **Notification:** one per session (id from the session key). `tasks/LiveUpdate` works out what it
  shows: one progress segment per step (or task), done ones filled, a point on the active one, the
  current and next one, "Waiting for you" when Claude stopped, and "Done" that dismisses itself
  after 3 minutes. Android 16 shows it as a Live Update (`ProgressStyle`, promoted ongoing);
  older versions get a standard progress notification. A tap opens the session on claude.ai.
- **No shortcuts:** Clauder had app shortcuts, pinned links and a Shortcuts widget, removed on
  22 Nov 2026 (see `docs/decisions.md`). On start, `ClauderApp.retireShortcuts` greys out links an
  earlier version pinned (only the launcher can remove them) and forgets their saved list.
- **Widget (Glance):** `widget/TasksWidget` shows the Needs input and Running sections of the
  Tasks screen, grouped by project the same way (`SessionBoard.rows` with `withInactive = false`),
  as tonal cards under a header with the same summary line as the tile; idle and finished sessions are left out
  (only "No sessions right now" when nothing is active); a session that waits for you uses the primary container. Its progress bar has one
  segment per step (at most ten, a widget limit; `SegmentBar.WIDGET_MAX`), with the empty ones in
  the surface colour so they stay visible in dark mode. It uses the system widget corner radii. It reads `SettingsStore` flows inside its content, so a widget on screen follows every
  change; `widget/Widgets` redraws it after a push, a fetch, or a change of theme, connection or
  mutes (`ClauderApp`). Taps on it and on notifications go through `links/OpenLinkActivity`
  (not exported), which opens only https claude.ai links (`ClaudeApp.safeUrl`).
- **Mute:** a session's notification has a Mute button (`tasks/MuteReceiver`). A muted session
  stays on the Tasks screen and in the widget, with Unmute on its card, but gets no notification;
  it is forgotten once it ends (`tasks/MuteList`).
- **Mark resolved:** a session that waits for you has a Mark resolved button on its notification
  (`tasks/ResolveReceiver`) and on its card on the Tasks screen. Resolving a thread by hand in the Claude app sends no hook event, so
  the button tells the Worker (`POST /sessions/resolve`, `resolveByUser` in `worker/src/tasklist.ts`):
  the thread is Done until the person writes in it again, exactly as after `set_thread_resolved`.
- **Waiting alert:** "Waiting for you" uses its own channel (`waiting`, default importance), so it
  can alert while progress stays quiet. Settings opens that channel's system settings. It sounds
  once per wait (`NotificationPlan.alertsAgain`): later pushes while the thread keeps waiting with
  the same question update it silently, and a plain alert the person swiped away stays away.
- **Push delivery:** FCM messages keep for a day with a collapse key per session, so a phone that
  was offline gets each session's newest state. `seq` is never lower than the time in
  milliseconds (`nextSeq`), so a session the Worker forgot after a quiet day still counts as newer
  on the phone. If a push fails, the alarm keeps it due, so Cloudflare's retry sends it instead of
  forgetting the session.
- **Session cache:** `push/Push.record` merges every push and fetch into the newest 20 sessions
  (`SessionBoard.keep`) and saves them, because a push can start the app from cold.
- **Screens:** Tasks (the sessions from the Worker's `GET /sessions`, kept up to date by pushes,
  grouped per project,
  `tasks/SessionBoard`) and Settings (connection, notifications, the setup block for cloud
  environments, appearance), with the claude.ai sign-in page as a child of Settings.
- **Stored data:** theme, Dynamic color and the Worker address in `backed_up_settings`; the
  Worker token, the registered FCM token, the session cache and muted sessions
  in `device_state` (not backed up); the encrypted claude.ai sign-in in `claude_ai_login` (not
  backed up). See `settings/SettingsStore.kt` and `claudeai/ClaudeAiLogin.kt`.
- **Worker client:** `push/WorkerClient.kt` (plain `HttpURLConnection`, no extra library).
- **claude.ai:** Settings → claude.ai opens claude.ai's own sign-in page in a WebView
  (`ui/claudeai/ClaudeAiLoginScreen`). As soon as claude.ai sets its `sessionKey` cookie, the app
  keeps an encrypted copy (`claudeai/ClaudeAiLogin`: AES-GCM with a key in the Android Keystore, in
  the DataStore `claude_ai_login`, not backed up) and clears the WebView's cookies and site data.
  **Test** calls the private endpoints the Claude app uses (`claudeai/ClaudeAi`, `claudeai/ClaudeAiClient`)
  and shows a copyable report of HTTP codes, field names, counts and status values only
  (`claudeai/Probe`). While signed in, `claudeai/ClaudeAiSync` reads the thread lists of projects
  active in the last 7 days (at most once a minute: when the Tasks screen opens or refreshes, and
  after each push) into memory, and `claudeai/RemoteThreads.overlay` lays them over the Worker's
  sessions on the Tasks screen only: a hook session bound to a thread (`bound_sessions[].id` = the
  session key) takes the thread's state unless a hook reported after the read, and a thread no hook
  knows is added while it waits or works (key `claudeai:<thread id>`, no steps). The widget, Live
  Updates and alerts still come from the Worker alone. The calls run on the phone because claude.ai answers server traffic with a Cloudflare
  bot check; the sign-in never goes to the Worker. Endpoints and shapes: `docs/spike-claude-ai-api.md`.

## Structure
```
app/src/main/kotlin/<package>/
  MainActivity.kt          Entry point. Only sets up the theme and the first screen.
  ui/theme/Theme.kt        Material 3 theme (light/dark, dynamic colour).
  ui/<feature>/            One folder per screen/feature:
    <Feature>Screen.kt       Composables: display state, forward clicks. No logic.
    <Feature>….kt            Plain Kotlin logic (no Android types) → unit tested.
app/src/main/res/
  values/strings.xml       All user-visible text (English, the default).
  values-xx/strings.xml    Dutch, French, German, Spanish and Italian. Every string must exist in every language.
app/src/test/kotlin/       JVM unit tests (mirrors the main package structure).
scripts/                   Release helpers (Python/Java) with their own tests.
.github/workflows/         ci.yml (every PR), release.yml (signed releases).
```

## Conventions
- **Single `app` module.** Split into modules only if the app grows large; record it in `decisions.md`.
- **Logic in plain Kotlin classes** with no `Context`, `View` or Compose types, so it can be
  tested on the JVM. Example: `tasks/LiveUpdate.kt`.
- **UI state**: small screens use `rememberSaveable`. When a screen gets real logic or data,
  add a `ViewModel` (`androidx.lifecycle:lifecycle-viewmodel-compose`) that exposes a
  `StateFlow<UiState>` and calls into the plain-Kotlin logic. Record it in `decisions.md`.
- **Navigation**: the sessions screen, with Settings as a child page behind the gear icon in its
  top app bar (`ui/ClauderRoot.kt`, a saved flag and `BackHandler`). With more screens, use
  Navigation Compose.
- **Persistence**: Jetpack DataStore (version already in the catalog). Wrap it behind a small
  repository interface so logic tests can use a fake. Decide per value whether it is backed up
  (see "Backup" below).
- **No network, analytics, crash reporting or ads** unless the owner agreed (see `CLAUDE.md`),
  in which case update `PRIVACY.md`.
- **Strings** in resources only; plurals via `<plurals>`.
- **Versions** of libraries only in `gradle/libs.versions.toml`.
- **App identity** (`applicationId`/`namespace`) only in `app/build.gradle.kts` (`appId`).

## Backup
Android backs up app data to the user's Google account (if they turned backup on) and copies it
to a new phone during set-up. The app decides which files take part, in
`app/src/main/res/xml/data_extraction_rules.xml` (`cloud-backup` = Google account,
`device-transfer` = new phone). The rule is an **allow list**: only listed files are copied, so a
new file is never backed up by accident.

- **Backed up:** settings the user chose and would want back after a reset or on a new phone
  (theme, layout, toggles, names). Keep them in one DataStore named `backed_up_settings`
  (`preferencesDataStore(name = "backed_up_settings")`), which the rules already list.
- **Not backed up:** sign-in tokens, passwords and API keys (security, and keys encrypted with the
  Android Keystore can't be read on another phone anyway); state that only means something on this
  phone (a running job, notification IDs, "switched on right now"); settings that only work after
  a permission or set-up on this phone; copies of data an online service can download again; caches
  (Android never backs up `cacheDir` and `noBackupFilesDir`). Keep these in another DataStore file.
- Android rules work per **file**, not per key. Moving a value from one file to another in an app
  that is already released needs a `DataMigration` (see Snoozer's `MoveKeysMigration`).
- `BackupRulesTest` checks both sections list exactly the settings file. Change the test together
  with the rules, and update `PRIVACY.md` when what is backed up changes.
- **minSdk below 31:** Android 8-11 ignore `data_extraction_rules.xml` and read
  `android:fullBackupContent` instead. Add `res/xml/backup_rules.xml` with the same `include` lines,
  point `fullBackupContent` at it in the manifest, and let `BackupRulesTest` check it too.
- To test on a phone: change a setting, then open Settings, search for "Backup" and tap **Back up now**. Uninstall the app and install the same APK again: the
  setting comes back. Restore only works for an APK signed with the same key.
