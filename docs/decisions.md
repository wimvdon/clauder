# Decision log

Newest first. Add an entry for every notable choice: what was decided, why, and what was rejected.

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

