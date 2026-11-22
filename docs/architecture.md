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
- **States:** Running (Claude works), Idle (nothing is in progress and nothing runs in the
  background), Waiting for you (a project thread showed a decision card, its last reply of the turn
  asked the user something, its next step waits on approval, or Claude Code asked for a
  permission), Done, Ended. The checklist decides: Stop ends every turn, also while Claude waits on
  CI or a reminder, so after Stop a step (or task) still marked in progress keeps the session
  Running, and only a list with nothing in progress becomes Idle (`worker/src/tasklist.ts`). The
  phone applies the same rule to what it shows (`TaskList.shown`), and turns a session that has been
  silent for 12 hours into Idle, so an abandoned one moves out of the way. A session that ended
  with open steps reads "Ended early". A project thread's session that stops between wakes
  (`SessionEnd` reason "other") pauses instead: a question stays open, anything else is Idle.
  While waiting, the list carries `need`: the decision card's question, or the permission
  prompt's message. The notification, the Tasks screen and the widget show it. A permission
  prompt is answered within the turn, so Stop turns it into Idle; an open decision card stays
  waiting until it is closed or Wim writes.
  Plain questions: the hooks also match the reply tool (`mcp__hearthbot__post_message`). The
  Worker keeps only what the turn's last reply asks (`worker/src/ask.ts`: the last question put
  to the user, such as "Which do you like?", or a request for approval such as "I'll open the PR
  once you approve"), and on Stop that makes the session Waiting with the question as `need`.
  Questions that aren't for the user (headings, quotes, code, "Why did it fail? The token
  expired.") don't count, and neither does "let me know if…". Sessions without the reply tool use
  Stop's `last_assistant_message`. A pending step such as "After approval: build it" also means
  Waiting once nothing is in progress. Waiting wins over Done, so "All merged. Shall I cut a
  release?" still asks.
- **What a session waits on, and what it made** (`worker/src/extras.ts`): the hooks also match
  the GitHub tools (create, read, merge and update a pull request), `subscribe_pr_activity`,
  `ReadNotifications` (CI events delivered to the thread), `send_later`, `set_thread_resolved`
  and the reply's `attached_outputs`. The Worker keeps the session's pull request with its CI
  state, the first https link or Artifact the last reply attached, when a reminder fires, and
  marks a resolved thread Done. After Stop, a PR whose CI still runs, a reminder still ahead or
  background helpers keep the session Running instead of Idle. The Stop hook is a command
  (`~/.claude/clauder-prompt.py`, the same script as the prompt hook) that totals the tokens per
  model from the transcript (and its subagents); the Worker prices them with the API price table
  in `extras.ts` (an estimate: subscriptions don't bill per token).
- **Notifications:** one per session by default. With "One notification for all sessions"
  (`Settings.summaryNotification`, backed up) the phone posts one ongoing summary
  (`Summary`, `TaskNotifier.showSummary`) and only the sessions that wait for you get their own,
  alerting, notification (`NotificationPlan`). A notification offers buttons for the attached page
  and the pull request (opened through `OpenLinkActivity`: claude.ai in the Claude app, other https
  links in their own app). Done is always silent.
- **Quick Settings tile** (`tile/ClauderTileService`): shows the same summary line when Quick
  Settings opens, and opens Clauder when tapped. Settings offers to add it on Android 13 and later.
- **Projects:** the user-level hooks run `~/.claude/clauder-prompt.py` on each prompt. It reads
  the claude.ai project and thread names from the session's transcript and sends them with the
  prompt event (without the prompt's text). The Tasks screen groups sessions under the
  project's name, else the repository's (`SessionBoard.sections`). Every group starts open and
  folds shut by tapping its header. Working and waiting sessions are full cards with their steps;
  idle and finished ones follow as one-line rows (title, state, count), always in view. In the shade, idle and finished sessions are bundled per project with a summary
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
- **Widget (Glance):** `widget/TasksWidget` shows the active sessions (or the last finished
  one) as tonal cards under a header with the same summary line as the tile; a session that waits
  for you uses the primary container. It uses the system widget corner radii. It reads `SettingsStore` flows inside its content, so a widget on screen follows every
  change; `widget/Widgets` redraws it after a push, a fetch, or a change of theme, connection or
  mutes (`ClauderApp`). Taps on it and on notifications go through `links/OpenLinkActivity`
  (not exported), which opens only https claude.ai links (`ClaudeApp.safeUrl`).
- **Mute:** a session's notification has a Mute button (`tasks/MuteReceiver`). A muted session
  stays on the Tasks screen and in the widget, with Unmute on its card, but gets no notification;
  it is forgotten once it ends (`tasks/MuteList`).
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
- **Navigation**: one screen today. With several screens, use Navigation Compose.
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
