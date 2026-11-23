# Privacy policy

_Last updated: 2026-11-23_

Clauder shows the task lists of your own Claude Code sessions. It talks only to a Cloudflare
Worker that you (the owner) run, and to Firebase Cloud Messaging to receive updates.

## What the app sends and receives

- **To your Worker:** the token you enter, this phone's Firebase Cloud Messaging token and its
  model name (so the Worker knows where to push), and requests for the current task lists and for
  when the last hook event arrived (the setup check in Settings).
- **From your Worker, through Firebase Cloud Messaging:** each session's title (the heading of a project thread's
  checklist, the thread's title, or else the folder name), its project and repository names, its steps and task subjects with their states, the tool Claude runs now with its short detail,
  the counts of files and lines it changed, and a link to the session on claude.ai. Google's FCM service carries
  these messages to the phone.
- The app has no analytics, advertising or crash reporting.

## What the Worker keeps

The Worker receives the raw Claude Code hook events of your sessions: task text, the progress
checklist of project threads, the working
folder, the transcript path, the replies Claude posts in a project thread and, when Claude stops,
its last message. Of the replies it keeps the first web link or Artifact the reply attached, and
the text of the turn's last reply until that turn ends. When a turn ends while something Claude
set up (such as CI on a pull request or a reminder) will still wake the session, the Worker has
the end of that last reply read by a language model on Cloudflare Workers AI, in your own
Cloudflare account, to see whether Claude asks you something. Only the answer (the question, in a
few words) is kept and shown on the phone; the reply text is then dropped. The Worker counts how much of Workers AI's free daily allowance these reads use (token counts only, no text), so it can stop before the allowance runs out. It also receives the pull requests Claude
opens, reads or merges and their CI results, the `git push` commands Claude runs (with their
output; no other shell commands are sent), the reminders Claude sets, and, at the end of each
turn, how many tokens the session used per model (counted on the session's machine from its
transcript; no text from the transcript is sent for this). At the end of each turn the same
script also sends how many files Claude edited and how many lines it added and removed since your
last message (only the counts, no file names or content). While Claude works it sends, at most
every 10 seconds, the name of the tool Claude runs and one short detail: the description Claude
gave a shell command (or only the program's name), a file's name, a search pattern, a web
address's host or a search query. No other tool input is sent. The hooks also report when an API error or a usage limit stops a
turn (the error's type and text), when auto mode refuses a tool call (the policy it hit, not the
command), when a `git push` fails (with its output), when helpers start and stop, when the
conversation is compacted (not its summary), and which model, effort level and permission mode the
session uses. When you send a prompt, a
small script on the session's machine adds the names and ids of the claude.ai project and thread,
read from the session's own transcript, and what kind of event woke the session (a message, a
coordinator note or a background event such as CI). Of the prompt's text it sends only the message
you wrote yourself (at most 1,000 characters; coordinator notes and background events are left out).
A smaller language model on Workers AI reads it once for a one-line "Working on" summary and, for a
session outside a project, a short title; the message itself is then dropped and never logged.
If you connect GitHub (Settings in Clauder), GitHub also sends the Worker webhooks from a
GitHub App on your own account that can only read: check runs, check suites, workflow runs, pull
requests and reviews of the repositories you install it on. The Worker keeps, per pull request,
its head commit, its state, the result of each workflow or check suite and the latest review
verdict, for 14 days after its last event, plus a short summary of each webhook in the debug log.
It keeps the app's webhook secret to check that a webhook comes from GitHub, and throws away the
app's private key and client secret right away. It keeps each session's task
list for a day after its last change, and the last 500 raw events for debugging. It runs in your
own Cloudflare account; nobody else has access.

## Permissions

- **Internet:** to reach the Worker and Firebase.
- **Notifications** and **Live Updates:** to show each session's progress.

## Stored on the phone

Your theme choice and the Worker address are included in Android's own backup (to your Google
account, if you turned that on) and in a transfer to a new phone. The Worker token, the Firebase
token, the last 20 sessions (so the widget and the Tasks screen have
something to show after a restart) and the sessions you muted stay on this phone only.

## Deleting your data

In Settings, tap Disconnect: the Worker forgets this phone. Uninstalling the app removes
everything it stored on the phone.

Questions: open an issue in this repository.
