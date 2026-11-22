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
  reminder) is a new prompt and makes the session Running again. **Idle** means nothing known is
  armed but the Worker can't tell: Claude Code loads hook settings once, at session start, so a
  session started before the installer last changed may set up a wake the Worker never hears of.
  The hooks say which settings the session loaded (`X-Clauder-Hooks-Loaded`, in the command's
  argument and the HTTP hooks' headers); only a session on the current version can be Waiting for
  you this way.
  **Reply check:** a turn can end both waiting on CI (or a reminder, a watched URL, helpers) and
  asking the user something, such as "Pick one: T1, T2 or T3". When a turn ends Running or Idle,
  the Worker sends the end of the turn's last reply (the last `post_message` text, else Stop's
  `last_assistant_message`) to Cloudflare Workers AI (`worker/src/ask.ts`, binding `AI`). If the
  model answers that the reply waits on the user, the session becomes Waiting for you with the
  question as `need`. The answer only counts for the turn it read (the list's `seq` must not have
  moved on); if the model fails, nothing changes. The reply text is kept in the Durable Object only
  until the turn ends and never goes to the phone or `/sessions`.
  The phone also shows a Running session that has been silent for 12 hours as Idle
  (`TaskList.shown`).
  A resolved thread stays Done until a person's message, a decision tap or a coordinator message
  wakes it: the prompt hook sends which kind of envelope the platform wrapped around the prompt
  (`clauder.source`: `wake:mention`, `wake:decision-chosen`, `relay`, `notification`, `other`),
  never the prompt's text, and CI events and other background wakes don't reopen it.
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
  Its status bar chip reads "3 · 1?" (active sessions · waiting for you). A Live Update can't be
  expanded, so its one collapsed line carries what matters: for a session, what Claude is on or
  asks plus what it waits on; for the summary, the sessions' projects (else repository, else thread title) in the
  blocks' order, each once with "×2" when it has several sessions, with the steps done as sub text. A notification offers buttons for the attached page
  and the pull request (opened through `OpenLinkActivity`: claude.ai in the Claude app, other https
  links in their own app). Done is always silent.
- **Quick Settings tile** (`tile/ClauderTileService`): shows the same summary line when Quick
  Settings opens, and opens Clauder when tapped. Settings offers to add it on Android 13 and later.
- **Projects:** the user-level hooks run `~/.claude/clauder-prompt.py` on each prompt. It reads
  the claude.ai project and thread names from the session's transcript and sends them with the
  prompt event (without the prompt's text). The Tasks screen groups sessions under the
  project's name, else the repository's (`SessionBoard.board`). Only working and waiting sessions
  are grouped: every group starts open and folds shut by tapping its header, and they show as full
  cards with their steps and a segmented progress bar (`SegmentBar`, one segment per step). All
  idle and finished sessions, from every project, go into one "Inactive" list at the bottom that
  starts folded and opens them as one-line rows (project or repository, title, state, count)
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
- **Widget (Glance):** `widget/TasksWidget` shows the working and waiting sessions as tonal cards
  under a header with the same summary line as the tile; idle and finished sessions are left out
  (only "No sessions right now" when nothing is active); a session that waits for you uses the primary container. Its progress bar has one
  segment per step (at most ten, a widget limit; `SegmentBar.WIDGET_MAX`), with the empty ones in
  the surface colour so they stay visible in dark mode. It uses the system widget corner radii. It reads `SettingsStore` flows inside its content, so a widget on screen follows every
  change; `widget/Widgets` redraws it after a push, a fetch, or a change of theme, connection or
  mutes (`ClauderApp`). Taps on it and on notifications go through `links/OpenLinkActivity`
  (not exported), which opens only https claude.ai links (`ClaudeApp.safeUrl`).
- **Mute:** a session's notification has a Mute button (`tasks/MuteReceiver`). A muted session
  stays on the Tasks screen and in the widget, with Unmute on its card, but gets no notification;
  it is forgotten once it ends (`tasks/MuteList`).
- **Mark resolved:** a session that waits for you has a Mark resolved button
  (`tasks/ResolveReceiver`). Resolving a thread by hand in the Claude app sends no hook event, so
  the button tells the Worker (`POST /sessions/resolve`, `resolveByUser` in `worker/src/tasklist.ts`):
  the thread is Done until the person writes in it again, exactly as after `set_thread_resolved`.
- **Waiting alert:** "Waiting for you" uses its own channel (`waiting`, default importance), so it
  can alert while progress stays quiet. Settings opens that channel's system settings.
- **Session cache:** `push/Push.record` merges every push and fetch into the newest 20 sessions
  (`SessionBoard.keep`) and saves them, because a push can start the app from cold.
- **Screens:** Tasks (the sessions from the Worker's `GET /sessions`, kept up to date by pushes,
  grouped per project,
  `tasks/SessionBoard`) and Settings (connection, notifications, the setup block for cloud
  environments, appearance).
- **Stored data:** theme, Dynamic color and the Worker address in `backed_up_settings`; the
  Worker token, the registered FCM token, the session cache and muted sessions
  in `device_state` (not backed up).
  See `settings/SettingsStore.kt`.
- **Worker client:** `push/WorkerClient.kt` (plain `HttpURLConnection`, no extra library).

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
