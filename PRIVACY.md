# Privacy policy

_Last updated: 2026-11-22_

Clauder shows the task lists of your own Claude Code sessions. It talks only to a Cloudflare
Worker that you (the owner) run, and to Firebase Cloud Messaging to receive updates.

## What the app sends and receives

- **To your Worker:** the token you enter, this phone's Firebase Cloud Messaging token and its
  model name (so the Worker knows where to push), and requests for the current task lists.
- **From your Worker, through Firebase Cloud Messaging:** each session's title (the heading of a project thread's
  checklist, the thread's title, or else the folder name), its project and repository names, its steps and task subjects with their states, and a link to the session on claude.ai. Google's FCM service carries
  these messages to the phone.
- The app has no analytics, advertising or crash reporting.

## What the Worker keeps

The Worker receives the raw Claude Code hook events of your sessions: task text, the progress
checklist of project threads, the working
folder, the transcript path and, when Claude stops, its last message. When you send a prompt, a
small script on the session's machine adds the names and ids of the claude.ai project and thread,
read from the session's own transcript; the prompt's text is left out. It keeps each session's task
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
