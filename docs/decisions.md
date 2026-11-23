# Decision log

Newest first. Add an entry for every notable choice: what was decided, why, and what was rejected.

## 2026-11-23: claude.ai's thread list on the Tasks screen, laid over the hooks

The live test (0.3.4) answered for projects, threads, project chat and the live stream. The Tasks
screen now reads each recent project's thread list and lays it over the Worker's sessions: a
thread's status (resolved, needs input, working, idle) wins over the hooks unless a hook reported
after the read, and threads without hooks are added while they wait or work. The hooks stay the
main source: they know steps, tasks, CI and summaries, and push instantly. Reads happen when the
screen opens or refreshes and after each push, at most once a minute, with no background job.
Rejected for now: the live stream (needs the app open, or a foreground service), and using
claude.ai for the widget and alerts (next, once this proves stable).

## 2026-11-23: Read thread state from claude.ai's private API, on the phone only

Hooks can't see threads without hooks, threads Wim resolves by hand or project chat replies, but
the Claude Android app reads all of them from private claude.ai endpoints (`v1/code/channels/…`,
found by decompiling Claude for Android 1.261120.19; see `docs/spike-claude-ai-api.md`). Wim chose
to use them, knowing the risks: the sign-in (`sessionKey` cookie) is a full claude.ai login, using
these endpoints goes against Anthropic's terms, and any Claude update can break them. The calls
run **on the phone**: claude.ai answers server traffic (this container, and most likely the
Worker) with a Cloudflare bot check, and we don't try to get past it. Keeping the login on the
phone also means it never reaches the Worker. It is stored AES-GCM encrypted with an Android
Keystore key in its own DataStore file (`claude_ai_login`), not backed up; no new library. The
sign-in is claude.ai's own page in a WebView; the app copies the cookie and clears the WebView.
This first step only adds the sign-in and a test that reports field names and counts, to confirm
the shapes live before the Tasks screen uses them. Rejected: the Worker calling claude.ai (bot
check, and a full login on a server); pasting the cookie by hand (hard on a phone); a foreground
service for instant background updates (an always-on notification and a new permission).

## 2026-11-23: CI results from GitHub webhooks, through a GitHub App made with the manifest flow

The hook events and PR subscription wakes carry no CI result, so Clauder guessed "running" after a
push and learned the outcome only when Claude read its notifications. GitHub now sends webhooks
straight to the Worker. They come from a private, read-only GitHub App that the owner creates from
a one-time Clauder link (GitHub's app manifest flow), so the webhook secret is never copied by hand
and one installation covers every repository, including new ones. Rejected: a webhook per
repository (the owner would paste the URL and a secret into each repository's settings) and a
GitHub token in the Worker to ask the Checks API (more access than webhooks need). GitHub Actions
results come from `workflow_run`, not from Actions' own check runs, so a job that starts after
others doesn't make CI look passed in between.

## 2026-11-22: Icons: same padding in every app, white background, white details; colour scheme follows the icon

All ten apps get one icon family. For Clauder the ring already sat at the shared padding (its
furthest point is 26.3 from the middle of the 108 canvas), so the shape keeps its size (scale
1.0) but is now wrapped in the same `<group>` as every other app. The adaptive-icon background
changes from the soft terracotta plate to plain white (`ic_launcher_background`). The check stays
in the gradient (a white check would vanish on white) and the two open segments keep their pale
tint `#F0C9BC` (not light enough to count as a plate-coloured detail). The app has no in-app logo
or icon-on-a-plate: the status bar and Quick Settings icon already showed the bare shape, and the
empty-state circles hold generic Material icons, so nothing else changed there. Colour scheme: the
scheme is already built around the icon's terracotta; the icon gradient's middle stop is `#DA6440`
and the primaries `#B0502C` (light) and `#F0A487` (dark) are its darker and lighter shades. They
stay, because `#DA6440` itself gives only about 3.6:1 with white text and about 4:1 on dark
containers (WCAG AA needs 4.5:1). Dynamic colour is unchanged.

## 2026-11-22: Status bar and Quick Settings tile show the ring too

The owner asked for every icon to follow the new design. The status bar icon, the Quick Settings
tile and the "add tile" prompt still showed the old speech bubble; they now use the themed icon's
ring and check, cropped so it fills the 24dp icon. The two open segments stay half transparent.

## 2026-11-22: The app icon is a segmented progress ring on a soft terracotta plate

Part of making all of the owner's app icons one family. The speech bubble with a check said
"chat" more than what Clauder does: show a session's task progress. The new icon is a ring of
six segments, four done and two to go, around a check, like the notification's segmented
progress bar. The owner asked for clearly separate segments and a stronger gradient (amber,
terracotta, rose). The white background becomes a soft plate in the app's terracotta. Rejected
(mockup rounds 13–16): a task-list card, a bubble with a progress bar, and rings of eight or
twelve segments (the gaps close up at small sizes).

## 2026-11-22: The Android app: Firebase without google-services.json, a warm Claude-like theme

The app receives the Worker's pushes through Firebase Cloud Messaging (`firebase-messaging`,
through the Firebase BoM). Firebase is started by hand in `ClauderApp` from `BuildConfig` fields
instead of the Google Services Gradle plugin and a `google-services.json` file: the project id,
sender id and app ids are in `app/build.gradle.kts`, and the API key comes from the
`FIREBASE_API_KEY` GitHub secret, so no Firebase file sits in the repository and no extra Gradle
plugin is needed. `org.json` is a test-only dependency, because Android's own copy is a stub in
JVM unit tests. The permissions INTERNET, POST_NOTIFICATIONS and POST_PROMOTED_NOTIFICATIONS
come from the briefing (Worker, notifications, Live Updates on Android 16). The theme uses a full
Material 3 scheme built around a terracotta primary on cream (Claude's own feel, not its logo),
checked for WCAG AA contrast, with the system serif for display, headline and Title Large text.
Dynamic color stays available as a switch, off by default. Rejected: the Google Services plugin
(a JSON file with keys in the repository), and a bundled serif font (an extra asset for little
gain over the system serif).

## 2026-11-22: Backend is a Cloudflare Worker fed by Claude Code HTTP hooks

The live task list comes from Claude Code hooks (`hooks/settings.json`) that POST each task event
to a Cloudflare Worker in `worker/` (TypeScript, Durable Objects for per-session state, deployed
by the `Worker` workflow with Wrangler). Dev dependencies are only `wrangler`, `typescript` and
`@cloudflare/workers-types`; tests use Node's built-in test runner, so no test framework is added.
The milestone 1 spike (`docs/spike-hooks.md`) showed the hooks carry task text, ids and status but
no list title. The Android app stays in `app/` at the repository root, where the template and its
CI expect it, instead of moving to `android/` as the briefing sketched. Rejected: polling the
Claude API (the app must never talk to Claude directly), and a third-party request bin for the
spike (session data would leave our own accounts).

## 2026-11-01: Claude Code sessions don't build the APK before pushing

Sessions ran `./gradlew testDebugUnitTest lintDebug assembleDebug` before every push, and CI then
runs the same and more. The APK built in the session was never used: the owner installs the one
from CI. Sessions now run only the unit tests and lint, which catch nearly every problem and
compile the app's code and resources. CI still builds the debug and release APKs, so a packaging
problem still turns the pull request red. Rejected: no local checks at all (every test or lint
failure would then cost a CI round trip).

## 2026-10-17: Design review across the apps: one way to reorder, readable widths, shared wording

A Material 3 review of every app built from this template found the same things done in different ways. Lists in the user's own order used four controls (drag handles, a long press, chevrons and arrows), even within one app; navigation bars mixed filled and outlined icons differently; most lists stretched across tablets although `DESIGN.md` asked for about 640dp; English said "Log out" next to "Sign in" and "Dismiss" where other apps said "Close"; and one app removed a workspace (with its presets) without asking. New shared rules in `DESIGN.md`: "Reordering" (a drag handle at the end of the row, `Reorder.kt`; a long press only for grids of cards), filled selected and outlined unselected navigation icons, `Modifier.readableWidth()` (`Layout.kt`), and wording for Close, Done, Sign out, Reorder and Move up / Move down. The two new kit files are copied into every app (Snoozer, without lists, takes only `Layout.kt`). The template now ships the same six languages as the apps. Rejected: up and down arrows for short lists (easier to build, but a second way to do the same thing) and a long press on list rows (hidden: nothing on screen says the row can move).

## 2026-10-17: Help text only where the screen doesn't speak for itself

The owner found the apps over-explanatory. A scan of every hint, summary and help paragraph removed text that restated a label, described standard controls (drag handles, tapping a row) or explained the app's design, and shortened the rest to what the screen can't show: consequences, where data goes, unfamiliar terms. `DESIGN.md` ("Wording") now says so, and the Dynamic color supporting text is one shared phrase without the app's colour name. Rejected: removing all help text (consequences and privacy notes are information, not decoration) and tooltips or info buttons instead (hidden text people rarely open, and more to translate).

## 2026-10-16: One empty state for every app, setup as a full-screen state

A second Material 3 audit found empty screens drawn three different ways: an icon in a tinted circle with a title (Tracker for Simkl), a title and text whose icon didn't show (RSS Reader), and a grey icon with one line of text (Noter for Notion). Apps that can't work until something is set up showed it differently too: Time Tracker for Notion put a small banner above a disabled timer, Noter filled the screen, Snoozer did both. Material 3 asks an empty state to say what it means and offer the next useful step, so the shared kit gained `ui/theme/EmptyState.kt` and `DESIGN.md` a section "Empty and setup states": one centred layout for empty, no-result, failed and setup-needed screens, with setup-needed replacing the screen's content. The same audit added two smaller rules: button labels name the action in as few words as possible ("Gezien", not "Markeren als gezien"), and rows that scroll sideways keep one height. Rejected: illustrations per screen (more to draw and translate for each app, and Material 3 warns against decoration that hides the next step) and keeping the banner for setup (easy to miss, and it leaves a screen full of controls that do nothing).

## 2026-10-16: Settings are one list; top-level navigation is a docked bar

An audit of the apps against Material 3 found two structural problems. Settings were a stack of collapsed cards, so even a single switch took two taps to find and every section was boxed in a card, which Material 3 lists as an anti-pattern. Settings are now one list grouped by section headers, with `ListItem` rows (switches apply at once) and only setup instructions folded away (`SettingsKit.kt`: `SettingsSection`, `SwitchSetting`, `SettingItem`, `ExpandableSetting`, `SettingsBlock`). Some apps put their top-level sections in a floating pill bar; Material 3 Expressive reserves floating toolbars for actions on the current screen, and keeps the navigation bar docked for destinations, so the shared rules now require the docked `NavigationBar`. `DESIGN.md` gained a "Navigation and structure" section. Rejected: a settings home page that links to sub-pages (more taps for apps with a handful of settings) and keeping the collapsible cards with everything expanded (still a card per section).

## 2026-10-15: Android's backup copies settings only, from an allow list

Users expect their settings back after a reset or on a new phone, and Android does that for free with its own backup (Google account) and phone-to-phone transfer. The template now lists exactly one file, the DataStore `backed_up_settings`, in `data_extraction_rules.xml`; everything else stays on the phone. An allow list means a file added later (a sign-in token, device state) is never backed up by accident, and `BackupRulesTest` fails if the rules drift. Rules for what goes where are in `docs/architecture.md`, "Backup". Rejected: backing up everything with exclusions (one forgotten exclusion leaks a token into the backup), switching backup off (users lose their settings) and our own backup to Google Drive (needs network access, sign-in and more code for what Android already does).

## 2026-10-15: Phone testing doesn't block merging

The owner can't review code and can't always test a pull request on the device right away, so pull requests waited for a manual test that adds little over CI. Green CI (unit tests, lint, builds) is now enough to merge; the owner tests afterwards with the steps from the pull request and always before a release, and problems are fixed in a new pull request. Merging to `main` does not reach users: releases stay manual. Rejected: keeping the phone test as a merge gate (pull requests pile up and go stale).

## 2026-10-15: Code is "all rights reserved" by default

Without a `LICENSE` file the legal default is already all rights reserved, but that isn't visible and is easy to misread for a public repository. The template now ships an explicit all-rights-reserved `LICENSE`, so every app starts strict and the owner opts in to sharing. Switching to a permissive licence (MIT or Apache-2.0) is a one-file swap, described in the README. Rejected: a commented-out alternative inside `LICENSE` (GitHub and licence scanners read the whole file, so it would be misdetected) and starting with MIT (opening up later is easy, taking it back for code already shared is not).

## 2026-10-14: Shared design rules have their master copy in the template

`DESIGN.md` was kept identical in the three sibling apps by hand, with no single place to start a change, and the copies had drifted (Snoozer was missing the Loading section; the Tracker for Simkl icon row was out of date in two apps). The android-app template (`wimvdon/android-app`) now holds the master copy; each app keeps a full copy so Claude sessions can read the rules without opening another repository, and only its "This app" section is its own. Rejected: keeping the file only in the template (sessions in an app would have to fetch it every time) and a shared library for the design kit (a new dependency and more setup than three small apps need). The design kit files (`ui/theme/`) stay in the apps.

## 2026-10-13: Template baseline

- **Language/UI:** Kotlin + Jetpack Compose + Material 3, single `app` module. This is Google's
  recommended, mainstream stack with the most documentation and examples.
- **Tool versions (latest stable on this date):** Gradle 9.8.1, Android Gradle Plugin 9.4.1
  (uses its built-in Kotlin support, so no separate `kotlin-android` plugin), Kotlin 2.4.21
  (Compose compiler plugin), Compose BOM 2026.09.00. Dependabot keeps these current.
- **minSdk 31 (Android 12).** The owner installs apps on their own, recent phone, so older
  Android versions aren't worth the extra code and testing. Android 12 brings Material You dynamic
  colour (no fallback code path), the modern splash screen and per-app language settings. It still
  covers the large majority of active devices. Lower it only if someone with an older phone needs
  the app.
- **compileSdk / targetSdk 37 (Android 17)**, the latest stable API level. The minor SDK release
  37.2 exists but isn't needed by anything yet.
- **Java 17 bytecode, JDK 21 to build.** JDK 21 is what Claude Code cloud sessions provide, so local
  checks match CI exactly.
- **Version numbers:** release `X.Y.Z` → `versionCode = X*10000 + Y*100 + Z`, passed in by the release
  workflow. All other builds use `git describe --tags --long` (e.g. `1.2.0-3-gabc1234`), falling back to
  `0.0.0-dev`, so a dev build can never be mistaken for a release. Version logic lives in
  `scripts/release_version.py` (unit tested) and `app/build.gradle.kts`.
- **Debug builds use `applicationIdSuffix = ".debug"`** so a pull request's APK installs next to the
  released app instead of conflicting with it.
- **Releases are GitHub Releases with a signed APK**, published by `gh release create` (preinstalled
  on runners) instead of a third-party action, which means one fewer dependency with write access.
  The R8 mapping file is attached to each release.
- **Signing secrets** are base64 and decoded by a tolerant, tested script (`scripts/decode_keystore.py`)
  that never prints the secret. The key password is checked with a small Java program
  (`scripts/VerifySigningKey.java`), because `keytool` ignores the key password of PKCS12 keystores and
  the build would otherwise fail later with an unclear error.
- **Caching:** `gradle/actions/setup-gradle` with `cache-provider: basic` (MIT licensed). The default
  "enhanced" provider is closed source under separate terms of use.
- **CI artifacts:** the debug APK is uploaded unzipped (`archive: false`) so it can be installed on a
  phone straight from the download.
- **Languages:** English (default), Dutch, French, German, Spanish and Italian, the languages every app built from this template ships. `generateLocaleConfig`
  makes the app appear under Android's per-app language settings.
- **Persistence:** DataStore when needed (version in the catalog, not added as a dependency yet). No
  network, analytics or crash-reporting libraries.
- **No instrumented/UI tests** yet: they need an emulator in CI (slow, flaky). Logic is kept in plain
  Kotlin and unit tested instead. Manual device checks are listed in `docs/testing.md`.
- **No lint baseline:** the template has zero lint issues.
- **Play Store upload** is prepared but commented out in `release.yml` until a Play Console app and
  service account exist.

## 2026-11-22: steps and tasks
- Project threads track their plan with a progress checklist, not with Claude Code's task tools.
  Wim follows that checklist, so it is the main list ("steps"), and its heading names the session.
  Claude Code's tasks and to-dos stay visible as folded detail. The hooks match the checklist tool
  (`mcp__hearthbot__update_progress_checklist`) and `TodoWrite` in addition to the task tools.

## 2026-11-22: widgets with Glance
- Home screen widgets use Jetpack Glance (`glance-appwidget`, `glance-material3` 1.2.0), the
  AndroidX library for Compose-style widgets, asked for by Wim. The widgets take Clauder's own
  colours, or the wallpaper's when Dynamic color is on.

## 2026-11-22: "Waiting for you" only when Claude asks, project names from the transcript
- Stop ends every turn, so it now means Idle, not Waiting. Waiting comes from a decision card
  (`request_decision`, until the next prompt or `close_decision`) or a permission prompt.
- Hooks don't carry the project, so a small command hook on UserPromptSubmit reads the project
  and thread names from the session's transcript (the session-context block) and adds them to the
  event. Rejected: asking claude.ai for them (the Worker has no sign-in there, and the app must
  never talk to Claude directly).

## 2026-11-22: The Worker serves the hook installer
Each cloud environment's setup script used to hold the whole installer, so every hook change meant
pasting a new block into every environment. The Worker now serves `hooks/install-user-hooks.sh`
at `GET /install.sh` (no token: the script holds no secret, the token comes from the
environment's variable), and the setup script needs one `curl … | bash` line that never changes.
The trade-off: whoever controls the Worker controls what the environments run, which was already
true of where the hooks send their data.

## 2026-11-22: No shortcuts, no link tester
The Claude app opens its Projects and Artifacts tabs only from its own widget (through an activity
other apps can't start), so Clauder's shortcuts for them could only open the app's home screen.
Wim chose to drop shortcuts altogether: the app shortcuts, pinned links, the share target, the
Shortcuts tab and widget, and the test build's link tester that was used to look into this.
Clauder is now the Tasks screen, the Tasks widget and the notifications.


## 2026-11-22: Keep WorkManager's constructors in the release build
Release 0.1.0 crashed on start. Glance brings WorkManager 2.7.1 and Room 2.2.5, which WorkManager
starts with the app and which create their database and input mergers by class name. Their
shrinking rules keep those classes but not their constructors, and R8's full mode (the default
since AGP 8) then removes the constructors. `app/proguard-rules.pro` now keeps them. Rejected for
now: adding a newer WorkManager to override Glance's, since that is a new dependency.

## 2026-11-22: The checklist decides whether a session is working

A project thread's turn ends many times before its work is done: while it waits on CI, a pull
request event or a reminder. Marking every Stop as Idle showed working threads as "Inactive" and
hid them under a folded "N inactive sessions" row. Now a step (or task) marked in progress keeps a
session Running across turns, in the Worker and on the phone; Idle means nothing is in progress.
The phone treats 12 hours of silence as Idle, so abandoned sessions still move aside. The Tasks
screen no longer hides inactive sessions behind a toggle: they show as one-line rows under the
active cards. A session that closed with open steps reads "Ended early" instead of "Ended".
Rejected: a shorter silence timeout (a slow CI run or an evening reminder would flip a thread to
Idle) and guessing from the time alone without the checklist.

## 2026-11-22: A question in Claude's last reply means "Waiting for you"
A project thread asked "Which do you like?" in a plain reply and waited for approval of a mockup,
but Clauder showed it as idle: only decision cards and permission prompts counted. The hooks now
also send the reply tool (`post_message`), and the Worker looks for a question put to the user in
the turn's last reply. To avoid false alarms it needs words that address the user ("you", "should
I", "want me", "je", "zal ik"), skips headings, quotes and code, and ignores "let me know if…".
A pending step that starts with "After approval" (or "Na goedkeuring") also counts once nothing is
in progress. Rejected: treating every turn that ends with pending steps as waiting, because
threads often stop between steps without needing anything.

## 2026-11-22: Pull requests, reminders and cost; a summary notification and a tile
Wim asked to close the gaps where a thread read as idle while it waited on CI or a reminder, and
to show more of what Claude does. The hooks now also match the GitHub pull request tools,
`ReadNotifications`, `send_later`, `subscribe_pr_activity`, `set_thread_resolved` and the reply's
attachments. Stop moved from an HTTP hook to the existing command script, so it can total the
session's tokens from the transcript; the Worker turns them into an API-price estimate.
On the phone: buttons for the PR and the attached page, a silent Done, an optional single summary
notification (a session that waits for you still alerts on its own) and a Quick Settings tile.
Earlier Wim said no to a tile; he asked for it now, to show the summary. No new permissions or
dependencies: `BIND_QUICK_SETTINGS_TILE` only guards the tile service.


## 2026-11-22: Project threads pause instead of ending, and hooks update themselves
A project thread's session gets `SessionEnd` (reason "other") between wakes and resumes on the
next message. Clauder read that as Ended, so a thread that had just asked Wim something lost its
"Waiting for you" and dropped into the inactive group. For a project thread, reason "other" now
pauses: a question stays open, anything else becomes Idle. The Worker also reads Claude's own
turn summary ("waiting for their answer", "for Wim to approve").
Cloud environments keep the result of their setup script, so sessions kept starting with the
hooks from before the reply tool was matched. The prompt hook now sends its installer version,
and when the Worker answers that it is out of date, the hook runs `/install.sh` again; the new
hooks apply from the next session start. Sessions with the old hook script can't update this way
and only catch up when the environment rebuilds.

## 2026-11-22: A visual summary notification
Wim asked for a visual summary within what Live Updates allow. Of two mockups (one block per
session, or every step in one bar) he chose one block per session: each block is coloured by
state and carries a dot at the session's progress, so the one that needs him stands out. The bar
stays full (`setStyledByProgress(false)`), so every block keeps its colour. Android may still
adjust the colours for contrast.

## 2026-11-22: States come only from raw hook data
Wim asked for no workarounds: every state must come from real hook data, not from reading text.
The question check on the turn's last reply (`worker/src/ask.ts`) and the "After approval" step
check are removed, and so is the fallback that read "permission" in a notification without a
`notification_type`. Waiting for you now means exactly a decision card (`request_decision`) or
a `permission_prompt` or `elicitation_dialog` notification. A question in a plain reply has no
hook signal (the thread's "blocked" state lives on claude.ai and hooks can't read it), so it
shows as Idle. A `pull_request.closed` event with `outcome` "merged" now counts as merged
(it read as closed before). Replaces the
entry "A question in Claude's last reply means Waiting for you" above.

## 2026-11-22: Waiting for you means nothing Claude set up will wake the thread
Wim wanted a reliable way to tell "waiting for CI" from "waiting on the user", without asking
threads to use decision cards. A thread that ends its turn is only woken by something it set up
(a reminder, an in-session cron, a PR subscription while CI runs, a watched URL, running helpers)
or by the user. The Worker now tracks those wake sources from hook data, and a turn that ends
with none armed means Waiting for you. A step marked in progress no longer keeps a thread
Running on its own, because nothing wakes it. Rejected: reading the reply's text (see the entry
above) and requiring decision cards for every question.

## 2026-11-22: Fold inactive sessions; plain alerts beside the summary; segmented bars
Wim asked to collapse inactive sessions. Earlier he disliked folding everything by default, so
only idle and finished sessions fold, under one "N inactive sessions" row per project; projects
and working or waiting sessions stay open. The widget shows the same count as one line.
With the summary notification on, the summary is the only Live Update: a session that waits for
you gets a plain notification that alerts and can be swiped away, not a second promoted one.
The progress bars in the app and the widget now have one segment per step, like the
notification. In dark mode the old track almost matched the card (1.02:1). The app now uses
outlineVariant for empty segments (1.67:1 against the card, filled primary 4.49:1 against them),
the widget a surface-coloured groove (1.76:1 against the card, primary 8.22:1 against it).
Also added "Alert when Claude waits" (on by default). The Claude app already notifies Wim when a
project thread needs him, so with it off Clauder keeps showing progress and the waiting state but
no longer makes its own sound or pop-up.

## 2026-11-22: Sessions on older hook settings show Idle, not Waiting for you
Right after the wake-source rule shipped, a thread waiting on CI showed "Waiting for you". Its
session had loaded the hook settings from before PR #27 at its start, so its PR subscription
never reached the Worker; the installer had already replaced the script file, which is why the
script's own version looked current. The hooks now say which settings the session loaded (the
version is part of the hook command and the HTTP hooks' headers), and a session on older
settings with nothing known armed shows Idle: the Worker can't tell whether it waits on CI or on
the user.

## 2026-11-22: Only a person or the coordinator reopens a resolved thread
A resolved thread went back to "Waiting for you" after a PR-merged event woke it: every prompt
cleared the resolved flag. On claude.ai only a member's message or tap, or a coordinator message,
reopens a thread. The prompt hook now sends the kind of envelope the platform put around the
prompt (its tag and wake reason, not the text), and the Worker reopens only for those. Hook
scripts that don't send it yet keep the old behaviour.

## 2026-11-22: One "Inactive" list instead of one per project
Wim asked to put all inactive sessions under one group that names each session's project or
repository instead of grouping them by it. The Tasks screen now groups only working and waiting
sessions by project; every idle and finished session sits in one "Inactive" list at the bottom,
folded by default, with the project or repository as a small label above each title. A project
with only inactive sessions no longer gets a header. The widget no longer shows inactive sessions
at all (Wim: "they don't need to be shown at all").

## 2026-11-22: Settings as a page behind a gear icon
Wim asked for settings on their own page, opened from an icon at the top right. With only two
destinations, a bottom navigation bar took space on every screen for a page that is rarely
opened. The bottom bar is gone: the main screen's top app bar shows a gear icon that opens
Settings as a child page with a back arrow (the system back gesture also returns), as
`DESIGN.md` describes for child pages. The main screen's title is now "Claude sessions", the
same as the widget. The app's name and launcher label stay "Clauder".

## 2026-11-22: A language model checks unclear turn endings for a question
A thread asked Wim to pick between three mockups while CI ran on another of its PRs, so Clauder
kept it Working: hook data can't tell "waiting on CI" from "waiting on CI and on you". Wim asked
for a reliable middle ground. Hook data still decides first (decision card, permission prompt,
nothing armed). Only when a turn ends with a wake source armed, or on older hooks, the Worker asks
a small model on Cloudflare Workers AI (`@cf/meta/llama-3.3-70b-instruct-fp8-fast`) whether the
turn's last reply waits on the user. Wim chose Workers AI over Claude Haiku (needs an API key and
a new library) and over the old keyword list (missed "Pick one: …"). It runs in Wim's Cloudflare
account on the free tier and needs no new dependency.

## 2026-11-22: A Mark resolved button, and plain "Waiting for you" alerts in summary mode

Wim resolved a thread by hand in the Claude app and its "Waiting for you" alert stayed. The hook
log shows why: resolving by hand sends no hook event at all (the session got the same SessionEnd
"other" as any pause between wakes), and the session record the Worker could read has no resolved
field either. Rather than guessing, the alert now has a Mark resolved button that tells the Worker;
the thread is then Done until the person writes again, like `set_thread_resolved`. In summary mode
the alert also drops its segmented progress bar and status bar chip: it was already not ongoing or
promoted, but on Android 16 it still looked like a Live Update.


## 2026-11-22: An open question survives background wakes

The review of the hook handling found that any prompt set a thread to Working, so a CI event that
arrived seconds after a decision card (the PR subscription's own event comes within a second) hid
the card until the person answered. An open decision card, and a question the reply check found,
now stay Waiting for you until the person answers (a message, a decision tap or a coordinator
message, read from `clauder.source`) or Claude closes the card. A permission prompt still ends with
the next prompt, because it is answered within its turn.

## 2026-11-22: Fixes from the hook handling review

Wim chose to fix everything the review found (one pull request). Besides the open question above:
the summary survives background wakes; a finished checklist doesn't mean Done right after the
person writes; the model's answer survives other events until the next prompt; `seq` is based on
the time so it never goes back; a failed push stays due; FCM messages keep a day with a collapse key
per session; the phone alerts once per wait. The hooks gain a `git push` hook (the `if` filter keeps
other shell commands out), one retry, and a level instead of the installer's hash, so an installer
edit no longer makes every live session Idle. Threads on hooks from before the self-update show a
message to paste into the thread; the Worker doesn't inject instructions into Claude's context
itself.

## 2026-11-22: The reply check also marks finished threads Done

Wim asked whether the Workers AI call could close finished threads. It can't resolve them in the
Claude app (the Worker has no access there), but it can mark them Done in Clauder. The same model
call that writes the summary line now also answers whether the reply says the work is finished
with nothing left for the user. Only then, and only when nothing will wake the thread but the
person (no CI, reminder, watched URL or helpers, no open card or question, current hooks), is the
session marked resolved, like the Mark resolved button. The prompt tells the model to answer false
when unsure or when the user still has something to review, approve or pick, so a wrong fold is
rarer than a missed one; the person's next message reopens it either way. Still one call per turn,
within the free tier; when the model fails, nothing changes.

## 2026-11-22: The status bar chip says what is going on

Wim's Pixel Watch showed the summary Live Update as "Clauder" with just "1". Wear OS 7 shows a
bridged Live Update in its tray as the app name plus the status bar chip, and the phone and watch
share that one text, so the chip now uses words: "1 waiting" when any session waits for you, else
"2 working" (it was "3 · 1?"). A session's own chip stays "3/5" while working and says "Waiting"
when it waits. With one active session, the summary's line says what that session is doing
instead of only its project, which is also what the watch shows when the Live Update is opened.

## 2026-11-23: A decision card stays open until it is picked or closed

A Noter thread put up a card, then the person asked a side question. That message set the thread
to Working, and while Claude answered, asked about the card again and kept building, Clauder showed
1 working instead of Waiting for you. Now only a decision tap (`wake:decision-chosen`) or Claude's
`close_decision` ends a card's wait; a person's other message or a coordinator message doesn't.
Older hook scripts that don't send the source still end it on any prompt. The same log showed a
background notification injected into the running turn wiping the reply Claude had just sent, so
the check at the turn's end had nothing to read: a background wake now keeps the reply and the
pending check, and only the person's message clears them.

## 2026-11-23: The reply check must name the question

The thread that built the status sections showed Waiting for you while it waited on CI for PR 49.
Its last reply said it would merge once CI passed and that the PR lists the steps to try it on the
phone. The model read that as waiting on the user but couldn't say what the question was, so the
phone showed "Claude asks you something". Now a "waiting" answer only counts when the model names
the question, and the prompt says that test steps for later and a next step Claude takes itself
(such as merging once CI passes) aren't requests.

## 2026-11-23: The Tasks screen and widget are split by status

Wim asked for sections instead of one list of projects with an Inactive list below: Needs input
(waiting for you), then Running (working, CI waits included), then Inactive. Inside each section,
sessions are grouped by project under a plain header that doesn't fold, so a project with a
waiting and a working thread shows up in both sections. Only Inactive folds (it starts folded),
and the widget shows the same two active sections without it. Project folding is gone: with the
status split, a long project no longer pushes waiting threads out of view. Inactive rows lost the
project line above their title, since the project header now says it.

## 2026-11-23: Ideas taken from Claude Code Notifier, without its code

Wim asked what Clauder could take from Claude Code Notifier (`touch-tap/ccn`). Its licence
(PolyForm Shield 1.0.0) forbids using the code for a competing product, free ones included, so
only ideas were taken and written from scratch. Taken: a receipt when the work ends (time, files,
lines), a setup check that shows hook events reach the Worker, a shared "Worker is down" note so
hooks don't wait on each call, a settings backup, and the tool Claude runs while it works. Not
taken: answering Claude from the phone, which depends on holding a local terminal's hooks open.
The receipt counts from the transcript's own edit results (raw data, no guessing), on the
session's machine, so only counts leave it. The tool hook is a command hook rather than an HTTP
hook, so it can skip calls (at most one per 10 seconds) and send only a short detail instead of
the whole tool input.

## 2026-11-23: API errors and the hook events Clauder missed

The audit of data sources found that an API error ends a turn with `StopFailure` instead of
`Stop`, so such threads stayed "Working". Wim asked to use every relevant source, so the hooks now
also send `StopFailure`, `PermissionDenied`, `SubagentStart`/`Stop`, `PreCompact`/`PostCompact`,
`PostModelSwitch`, `SessionStart` (command hooks only, run in the background) and a failed
`git push`, and the Worker acts on more notification types. An error is a kind of "waiting"
(`needKind: "error"`) rather than a sixth state, because like every other wait only the person can
move the thread on; the phone gives it its own label. Events whose payload holds text (a helper's
reply, the compaction summary, the command auto mode refused) go through the hook script, which
leaves that text out. `duration_ms` is not used: it is one tool call's run time, which the phone has
no use for. Hooks level L4.

## 2026-11-23: Workers AI stays inside its free daily allocation

Wim asked whether Workers AI could take on a larger role without risking wrong states or leaving
the free tier (10,000 neurons a day). The Worker log showed about 30 neurons a read and up to
about 20 reads an hour on a busy evening, so a long busy day could run out, and then every read
failed without notice. The Worker now counts the neurons of every call per UTC day and stops the
reads that only write a line first (from 7,000), so the reads that decide a state run longest.
Reads that can't change the state only ask for the summary, of Llama 3.1 8B, which costs about a
seventh of the 70B model; the reads that decide Waiting for you and Done stay on the 70B model. Wim
picked this together with a "Working on" line from the person's message (a separate change).
Ideas left out: an AI that decides Running or Done on its own, reads CI results or judges how
urgent an alert is, because hook data decides those exactly or a wrong guess would hide an alert.

## 2026-11-23: A "Working on" line from the person's message

Wim picked this with the AI budget. Until now the phone showed nothing new between the person's
message and the first checklist, and a session outside a project was titled after its folder.
The prompt hook now sends the person's own message (never a coordinator note or a CI event), and
the small model writes one line about what Claude now works on, plus a title for sessions outside
a project. It is display only: no state depends on it. The message is dropped once read and never
logged, so the hooks' rule "the prompt's text stays in the session" now has this one exception,
which PRIVACY.md describes. No new hook events, so the hook level stays the same.

## 2026-11-23: Follow the runs a session starts itself

Wim expected a notification when Clauder 0.3.4's release finished. The Release run had no pull
request, so the Worker dropped its webhooks, and the app only alerts when Claude waits. The tool
hook now always sends `actions_run_trigger` calls with the repository and workflow, the Worker ties
the run GitHub then creates to that session by workflow and time, and the app shows the run and
alerts once when it passes or fails, on its own channel so it can be silenced apart from "Waiting
for you". Matching by time is a small guess, so it only counts `workflow_dispatch` runs of the
named workflow that start within 5 minutes. No new hook events, so the hook level stays L4.
