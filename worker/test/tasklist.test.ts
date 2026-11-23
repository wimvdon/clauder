import { test } from "node:test";
import assert from "node:assert/strict";
import {
  applyAsk,
  applyHook,
  clip,
  emptyList,
  progress,
  resolveByUser,
  sessionKey,
  titleFromCwd,
  toPushData,
  type SessionList,
} from "../src/tasklist.ts";

const NOW = "2026-11-22T10:00:00.000Z";
const CWD = "/home/user/clauder";

/** Runs hook events from a session with the current hook settings, unless [hooks] says otherwise. */
function run(events: unknown[], start: SessionList = emptyList("cse_abc", null, NOW), hooks: boolean | undefined = true): SessionList {
  let list = start;
  for (const event of events) list = applyHook(list, event, NOW, hooks) ?? list;
  return list;
}

const created = (id: string, subject: string) => ({
  hook_event_name: "TaskCreated", cwd: CWD, task_id: id, task_subject: subject, task_description: subject,
});
const createTool = (id: string, subject: string) => ({
  hook_event_name: "PostToolUse", cwd: CWD, tool_name: "TaskCreate",
  tool_input: { subject, description: subject }, tool_response: { task: { id, subject } },
});
const update = (id: string, from: string, to: string) => ({
  hook_event_name: "PostToolUse", cwd: CWD, tool_name: "TaskUpdate",
  tool_input: { taskId: id, status: to },
  tool_response: { success: true, taskId: id, updatedFields: ["status"], statusChange: { from, to } },
});
const completed = (id: string, subject: string) => ({
  hook_event_name: "TaskCompleted", cwd: CWD, task_id: id, task_subject: subject,
});

test("the real event sequence from the spike builds the list", () => {
  const list = run([
    created("1", "write a.txt"), createTool("1", "write a.txt"),
    created("2", "write b.txt"), createTool("2", "write b.txt"),
    update("1", "pending", "in_progress"),
  ]);
  assert.equal(list.title, "clauder");
  assert.deepEqual(list.tasks.map((t) => [t.id, t.status]), [["1", "in_progress"], ["2", "pending"]]);
  assert.equal(list.state, "running");
  const p = progress(list);
  assert.equal(p.done, 0);
  assert.equal(p.total, 2);
  assert.equal(p.current?.subject, "write a.txt");
  assert.equal(p.next?.subject, "write b.txt");
});

test("duplicate events don't bump seq", () => {
  const once = run([created("1", "a")]);
  assert.equal(applyHook(once, createTool("1", "a"), NOW), null);
  assert.equal(once.seq, Date.parse(NOW));
});

test("all tasks completed means done", () => {
  const list = run([
    created("1", "a"), created("2", "b"),
    update("1", "pending", "in_progress"), completed("1", "a"), update("1", "in_progress", "completed"),
    update("2", "pending", "in_progress"), completed("2", "b"),
  ]);
  assert.equal(list.state, "done");
  assert.equal(progress(list).done, 2);
});

test("a new task after done goes back to running", () => {
  const list = run([created("1", "a"), completed("1", "a"), created("2", "b")]);
  assert.equal(list.state, "running");
});

test("a turn that ends with nothing set up to wake it waits on the user", () => {
  const waiting = run([created("1", "a"), { hook_event_name: "Stop", cwd: CWD }]);
  assert.equal(waiting.state, "waiting");
  assert.equal(waiting.need, undefined);
  assert.equal(run([{ hook_event_name: "UserPromptSubmit" }], waiting).state, "running");
  // A step marked in progress doesn't wake anyone, so it still waits on the user.
  const marked = run([created("1", "a"), update("1", "pending", "in_progress"), { hook_event_name: "Stop", cwd: CWD }]);
  assert.equal(marked.state, "waiting");
  const steps = run([checklist("Fix crash\n\n✓ Find it\n✱ Merge when CI is green"), { hook_event_name: "Stop", cwd: "/home/user" }]);
  assert.equal(steps.state, "waiting");
});

test("a session on older hook settings can't be told apart, so it shows idle instead of waiting", () => {
  // Settings load at session start: such a session may follow a PR the Worker never hears of.
  const old = run([checklist("Ship\n\n✓ Fix\n✱ Merge when the checks pass"), { hook_event_name: "Stop" }], undefined, false);
  assert.equal(old.state, "idle");
  assert.equal(old.hooksCurrent, undefined);
  // Hook events that don't say which settings they come from keep what the session last said.
  const later = applyHook(old, { hook_event_name: "Stop" }, NOW, undefined) ?? old;
  assert.equal(later.state, "idle");
  const prompt = { hook_event_name: "UserPromptSubmit", clauder: { project: "P", thread: "T" } };
  const paused = run([prompt, { hook_event_name: "SessionEnd", reason: "other" }], old, false);
  assert.equal(paused.state, "idle");
  // A session on the current settings that has nothing armed waits on the user.
  const current = run([{ hook_event_name: "Stop" }], old, true);
  assert.equal(current.state, "waiting");
  assert.equal(current.hooksCurrent, true);
});

test("an in-session cron keeps the session running", () => {
  const list = run([created("1", "a"), { hook_event_name: "Stop", cwd: CWD, session_crons: [{ id: "c1" }] }]);
  assert.equal(list.state, "running");
});

test("a watched URL keeps the session running until it is unwatched or the session ends", () => {
  const watch = tool("mcp__claude-code-remote__watch_url", {}, [{ type: "text", text: '{"trigger_id":"trig_1","url":"https://x"}' }]);
  const list = run([checklist("Plan\n\n○ One"), watch, { hook_event_name: "Stop" }]);
  assert.equal(list.state, "running");
  assert.deepEqual(list.watches, ["trig_1"]);
  const unwatched = run([tool("mcp__claude-code-remote__unwatch_url", { trigger_id: "trig_1" }), { hook_event_name: "Stop" }], list);
  assert.equal(unwatched.state, "waiting");
  const prompt = { hook_event_name: "UserPromptSubmit", clauder: { project: "Code", thread: "T" } };
  const paused = run([prompt, watch, { hook_event_name: "Stop" }, { hook_event_name: "SessionEnd", reason: "other" }], list);
  assert.equal(paused.state, "waiting");
  assert.equal(paused.watches, undefined);
});

test("Stop while background work runs keeps the session running", () => {
  const list = run([
    created("1", "a"),
    { hook_event_name: "Stop", background_tasks: [{ id: "x", type: "subagent", status: "running" }] },
  ]);
  assert.equal(list.state, "running");
});

test("a decision card or a permission prompt means waiting", () => {
  const decision = { hook_event_name: "PostToolUse", tool_name: "mcp__hearthbot__request_decision", tool_input: {}, tool_response: {} };
  const waiting = run([created("1", "a"), decision, { hook_event_name: "Stop" }]);
  assert.equal(waiting.state, "waiting");
  assert.equal(run([{ hook_event_name: "UserPromptSubmit" }], waiting).state, "running");
  const closed = { hook_event_name: "PostToolUse", tool_name: "mcp__hearthbot__close_decision", tool_input: {}, tool_response: {} };
  assert.equal(run([closed], waiting).state, "running");

  const base = run([created("1", "a")]);
  assert.equal(run([{ hook_event_name: "Notification", notification_type: "permission_prompt", message: "Claude needs your permission" }], base).state, "waiting");
  assert.equal(applyHook(base, { hook_event_name: "Notification", notification_type: "idle_prompt", message: "Claude is waiting for your input" }, NOW), null);
});

test("the prompt hook names the project, the thread and the repo", () => {
  const prompt = {
    hook_event_name: "UserPromptSubmit",
    cwd: "/home/user/clauder",
    clauder: { project: "Clauder", projectId: "chan_01Abc", thread: "Build Claude companion Android app" },
  };
  const list = run([prompt]);
  assert.equal(list.project, "Clauder");
  assert.equal(list.projectId, "chan_01Abc");
  assert.equal(list.repo, "clauder");
  assert.equal(list.title, "Build Claude companion Android app");
  const later = run([created("1", "a")], list);
  assert.equal(later.title, "Build Claude companion Android app");
  const planned = run([{ hook_event_name: "PostToolUse", tool_name: "mcp__hearthbot__update_progress_checklist", tool_input: { text: "Plan\n\n○ one" }, tool_response: {} }], later);
  assert.equal(planned.title, "Plan");
  const data = toPushData(planned);
  assert.equal(data.project, "Clauder");
  assert.equal(data.projectId, "chan_01Abc");
  assert.equal(data.repo, "clauder");
  assert.equal(run([{ ...prompt, clauder: { projectId: "not-a-channel" } }]).projectId, undefined);
});

test("Stop without a task list is ignored", () => {
  assert.equal(applyHook(emptyList("k", null, NOW), { hook_event_name: "Stop" }, NOW), null);
});

test("a finished but unresolved list waits on the user; resolving makes it done", () => {
  const list = run([created("1", "a"), completed("1", "a")]);
  assert.equal(list.state, "done");
  assert.equal(run([{ hook_event_name: "Stop" }], list).state, "waiting");
});

test("SessionEnd ends the list", () => {
  assert.equal(run([created("1", "a"), { hook_event_name: "SessionEnd", reason: "other" }]).state, "ended");
});

test("a project thread pauses between wakes instead of ending", () => {
  const prompt = { hook_event_name: "UserPromptSubmit", clauder: { project: "Code", thread: "Icons" } };
  const end = { hook_event_name: "SessionEnd", reason: "other" };
  const stop = { hook_event_name: "Stop" };
  assert.equal(run([prompt, created("1", "a"), stop, end]).state, "waiting");
  const decision = { hook_event_name: "PostToolUse", tool_name: "mcp__hearthbot__request_decision", tool_input: { question: "Which finish?" }, tool_response: {} };
  assert.equal(run([prompt, created("1", "a"), decision, stop, end]).state, "waiting");
  // A reminder lives on the server, so it still wakes the thread after the pause.
  const later = tool("mcp__claude-code-remote__send_later", { delay_minutes: 60, message: "x" });
  assert.equal(run([prompt, created("1", "a"), later, stop, end]).state, "running");
  // Helpers end with the session.
  const helper = { hook_event_name: "Stop", background_tasks: [{ id: "x", type: "subagent", status: "running" }] };
  assert.equal(run([prompt, created("1", "a"), helper, end]).state, "waiting");
  assert.equal(run([prompt, created("1", "a"), { hook_event_name: "SessionEnd", reason: "logout" }]).state, "ended");
});

test("renames and deletes", () => {
  const renamed = run([
    created("1", "a"), created("2", "b"),
    { hook_event_name: "PostToolUse", tool_name: "TaskUpdate", tool_input: { taskId: "2", subject: "b (final)" },
      tool_response: { success: true, taskId: "2", updatedFields: ["subject"] } },
  ]);
  assert.equal(renamed.tasks[1].subject, "b (final)");
  const deleted = run([
    { hook_event_name: "PostToolUse", tool_name: "TaskUpdate", tool_input: { taskId: "1", status: "deleted" },
      tool_response: { success: true, taskId: "1" } },
  ], renamed);
  assert.deepEqual(deleted.tasks.map((t) => t.id), ["2"]);
});

test("a failed TaskUpdate and unrelated tools change nothing", () => {
  const list = run([created("1", "a")]);
  assert.equal(applyHook(list, { hook_event_name: "PostToolUse", tool_name: "TaskUpdate",
    tool_input: { taskId: "1", status: "completed" }, tool_response: { success: false } }, NOW), null);
  assert.equal(applyHook(list, { hook_event_name: "PostToolUse", tool_name: "Bash" }, NOW), null);
  assert.equal(applyHook(list, "not json", NOW), null);
});

test("an update for an unseen task adds it", () => {
  const list = run([update("7", "pending", "in_progress")]);
  assert.deepEqual(list.tasks, [{ id: "7", subject: "Task 7", status: "in_progress" }]);
});

test("titleFromCwd", () => {
  assert.equal(titleFromCwd("/home/user/clauder"), "clauder");
  assert.equal(titleFromCwd("/home/user"), "Claude Code");
  assert.equal(titleFromCwd("/Users/wim"), "Claude Code");
  assert.equal(titleFromCwd("/root"), "Claude Code");
  assert.equal(titleFromCwd("/Users/wim/code/notes/"), "notes");
  assert.equal(titleFromCwd(null), "Claude Code");
});

test("the title comes from the first event and then stays", () => {
  const list = run([created("1", "a"), { ...created("2", "b"), cwd: "/elsewhere/other" }]);
  assert.equal(list.title, "clauder");
});

test("sessionKey prefers the cloud session id", () => {
  assert.equal(sessionKey("cse_016Rk", { session_id: "3e6c-11" }), "cse_016Rk");
  assert.equal(sessionKey("$CLAUDE_CODE_REMOTE_SESSION_ID", { session_id: "3e6c-11" }), "3e6c-11");
  assert.equal(sessionKey(null, { session_id: "../x" }), null);
  assert.equal(sessionKey(null, {}), null);
});

test("clip", () => {
  assert.equal(clip("  a\n b  "), "a b");
  assert.equal(clip("abcdef", 4), "abc…");
});

test("push data is strings only and stays under the FCM limit", () => {
  const events = Array.from({ length: 50 }, (_, i) => created(String(i + 1), "x".repeat(200)));
  const data = toPushData(run(events));
  assert.ok(Object.values(data).every((v) => typeof v === "string"));
  assert.ok(JSON.stringify(data).length < 4000);
  const tasks = JSON.parse(data.tasks) as { i: string; s: string; t: string }[];
  assert.equal(tasks.length, 50);
  assert.equal(data.type, "tasklist");
});

const checklist = (text: string) => ({
  hook_event_name: "PostToolUse", cwd: "/home/user", tool_name: "mcp__hearthbot__update_progress_checklist",
  tool_input: { text }, tool_response: {},
});

test("a project thread's checklist becomes the steps, and its header the title", () => {
  const list = run([
    created("1", "Read the code"),
    checklist("Widgets en extra's\n\n✓ Snelkoppelingen gemerged\n✱ Worker opnieuw uitrollen\n○ Widgets bouwen"),
  ]);
  assert.equal(list.title, "Widgets en extra's");
  assert.deepEqual(list.steps?.map((s) => [s.subject, s.status]), [
    ["Snelkoppelingen gemerged", "completed"],
    ["Worker opnieuw uitrollen", "in_progress"],
    ["Widgets bouwen", "pending"],
  ]);
  assert.equal(list.tasks.length, 1, "the task list stays apart");
  assert.equal(progress(list).total, 3, "progress counts the steps");
  assert.equal(progress(list).current?.subject, "Worker opnieuw uitrollen");
});

test("a later checklist replaces the steps, and all steps done means done", () => {
  const list = run([
    checklist("Plan\n\n✱ One\n○ Two"),
    checklist("Plan\n\n✓ One\n✓ Two"),
    created("1", "A task still open"),
  ]);
  assert.equal(list.steps?.length, 2);
  assert.equal(list.state, "done");
});

test("the title from a checklist is kept when no folder name applies", () => {
  const list = run([checklist("Fix Safari export\n\n○ Look"), created("1", "x")]);
  assert.equal(list.title, "Fix Safari export");
});

test("TodoWrite to-dos join the tasks and replace the previous to-dos", () => {
  const todos = (items: [string, string][]) => ({
    hook_event_name: "PostToolUse", cwd: CWD, tool_name: "TodoWrite",
    tool_input: { todos: items.map(([content, status]) => ({ content, status, activeForm: content })) },
  });
  const list = run([
    created("1", "Task"),
    todos([["Write test", "in_progress"], ["Fix bug", "pending"]]),
    todos([["Write test", "completed"], ["Fix bug", "in_progress"]]),
  ]);
  assert.deepEqual(list.tasks.map((t) => [t.subject, t.status]), [
    ["Task", "pending"],
    ["Write test", "completed"],
    ["Fix bug", "in_progress"],
  ]);
});

test("the push carries the steps separately", () => {
  const list = run([checklist("Plan\n\n✓ One"), created("1", "Task")]);
  const data = toPushData(list);
  assert.deepEqual(JSON.parse(data.steps), [{ i: "plan:1", s: "One", t: "completed" }]);
  assert.equal(JSON.parse(data.tasks).length, 1);
});

test("waiting says what Claude asks, and forgets it once answered", () => {
  const decision = {
    hook_event_name: "PostToolUse",
    tool_name: "mcp__hearthbot__request_decision",
    tool_input: { question: "Merge the shortcuts PR now?", options: [] },
    tool_response: {},
  };
  const asked = run([created("1", "a"), decision, { hook_event_name: "Stop" }]);
  assert.equal(asked.state, "waiting");
  assert.equal(asked.need, "Merge the shortcuts PR now?");
  assert.equal(toPushData(asked).need, "Merge the shortcuts PR now?");
  const answered = run([{ hook_event_name: "UserPromptSubmit" }], asked);
  assert.equal(answered.need, undefined);
  assert.equal(answered.needKind, undefined);
  assert.equal(toPushData(answered).need, "");

  const permission = { hook_event_name: "Notification", notification_type: "permission_prompt", message: "Claude needs your permission to use Bash" };
  const prompted = run([created("1", "a"), permission]);
  assert.equal(prompted.need, "Claude needs your permission to use Bash");
  // The prompt is answered within the turn; the need goes once the turn has gone on.
  const after = run([tool("mcp__claude-code-remote__send_later", { delay_minutes: 5, message: "x" }), { hook_event_name: "Stop" }], prompted);
  assert.equal(after.state, "running");
  assert.equal(after.need, undefined);
});

const reply = (text: string) => ({
  hook_event_name: "PostToolUse", cwd: "/home/user", tool_name: "mcp__hearthbot__post_message",
  tool_input: { text }, tool_response: {},
});
const MOCKUP_PLAN = "Playful Material 3 mockup\n\n✓ Read the screens\n✓ Design a mockup\n✓ Share the mockup for approval\n✓ Add wordmark options\n○ After approval: build it in the app and open a pull request";

test("the wake sources decide first; every reply is read for a summary", () => {
  const asked = run([checklist("Plan\n\n✓ One\n○ Two"), reply("Which do you like? I'd go with A."), { hook_event_name: "Stop", cwd: "/home/user" }]);
  assert.equal(asked.state, "waiting");
  assert.equal(asked.need, undefined);
  assert.equal(asked.askCheck, "Which do you like? I'd go with A.");
  assert.equal(asked.reply, undefined);
  const later = tool("mcp__claude-code-remote__send_later", { delay_minutes: 30, message: "x" });
  const busy = run([checklist("Plan\n\n✓ One\n○ Two"), reply("Which do you like?"), later, { hook_event_name: "Stop", cwd: "/home/user" }]);
  assert.equal(busy.state, "running");
  assert.equal(busy.askCheck, "Which do you like?");
  // A turn without a reply has nothing to read.
  assert.equal(run([checklist("Plan\n\n✱ One"), { hook_event_name: "Stop" }]).askCheck, undefined);
});

test("a reply is kept without a push, and only the turn's last one counts", () => {
  const start = run([checklist("Plan\n\n✱ One")]);
  const first = applyHook(start, reply("Working on it."), NOW);
  assert.equal(first?.seq, start.seq);
  assert.equal(first?.reply, "Working on it.");
  const later = tool("mcp__claude-code-remote__send_later", { delay_minutes: 30, message: "x" });
  const list = run([reply("Pick one: T1, T2 or T3."), later, { hook_event_name: "Stop" }], first!);
  assert.equal(list.askCheck, "Pick one: T1, T2 or T3.");
  // A new prompt drops a reply from the turn before.
  const prompted = run([reply("Old"), { hook_event_name: "UserPromptSubmit" }], start);
  assert.equal(prompted.reply, undefined);
  assert.equal(toPushData(list).reply, undefined);
});

test("older hooks: the session's last message is read too", () => {
  const old = run([checklist(PLAN_DONE_BUT_ONE), { hook_event_name: "Stop", last_assistant_message: "Shall I merge it?" }], undefined, false);
  assert.equal(old.state, "idle");
  assert.equal(old.askCheck, "Shall I merge it?");
});

const read = (question: string, summary = "", finished = false) => ({ question, summary, finished });

test("the model's question marks a working thread waiting, only for the turn it read", () => {
  const later = tool("mcp__claude-code-remote__send_later", { delay_minutes: 30, message: "x" });
  const list = run([checklist(PLAN_DONE_BUT_ONE), reply("CI runs. Pick one: T1, T2 or T3."), later, { hook_event_name: "Stop" }]);
  const waiting = applyAsk(list, list.askCheck!, read("Pick T1, T2 or T3", "CI running, pick T1-T3"), NOW);
  assert.equal(waiting?.state, "waiting");
  assert.equal(waiting?.need, "Pick T1, T2 or T3");
  assert.equal(waiting?.needKind, "question");
  assert.equal(waiting?.summary, "CI running, pick T1-T3");
  assert.equal(waiting?.seq, list.seq + 1);
  assert.equal(waiting?.askCheck, undefined);
  assert.equal(toPushData(waiting!).need, "Pick T1, T2 or T3");
  assert.equal(toPushData(waiting!).summary, "CI running, pick T1-T3");
  // Asks nothing and no summary, or the model failed: the state stays, the check is done.
  for (const answer of [read(""), null]) {
    const same = applyAsk(list, list.askCheck!, answer, NOW);
    assert.equal(same?.state, "running");
    assert.equal(same?.seq, list.seq);
    assert.equal(same?.askCheck, undefined);
  }
  // A late answer for an earlier turn is dropped.
  const next = run([{ hook_event_name: "UserPromptSubmit" }], list);
  assert.equal(applyAsk(next, list.askCheck!, read("Pick one"), NOW), null);
  // The next prompt ends the wait and drops the summary.
  const prompted = run([{ hook_event_name: "UserPromptSubmit" }], waiting!);
  assert.equal(prompted.state, "running");
  assert.equal(prompted.summary, undefined);
});

test("a summary alone is pushed; a waiting thread gets the question, a resolved one doesn't wait", () => {
  const working = run([checklist(PLAN_DONE_BUT_ONE), reply("PR #5 is open, CI runs."), tool("mcp__claude-code-remote__send_later", { delay_minutes: 30, message: "x" }), { hook_event_name: "Stop" }]);
  const summed = applyAsk(working, working.askCheck!, read("", "PR #5 open, CI running"), NOW);
  assert.equal(summed?.state, "running");
  assert.equal(summed?.summary, "PR #5 open, CI running");
  assert.equal(summed?.seq, working.seq + 1);
  const waits = run([checklist(PLAN_DONE_BUT_ONE), reply("Which font?"), { hook_event_name: "Stop" }]);
  assert.equal(applyAsk(waits, waits.askCheck!, read("Which font?"), NOW)?.need, "Which font?");
  const decision = { hook_event_name: "PostToolUse", tool_name: "mcp__hearthbot__request_decision", tool_input: { question: "Which colour?" }, tool_response: {} };
  const card = run([checklist(PLAN_DONE_BUT_ONE), decision, reply("Card is up."), { hook_event_name: "Stop" }]);
  assert.equal(applyAsk(card, card.askCheck!, read("Pick a colour"), NOW)?.need, "Which colour?");
  const resolved = run([checklist(PLAN_DONE_BUT_ONE), reply("All done. Anything else?"), tool("mcp__hearthbot__set_thread_resolved", { resolved: true }), { hook_event_name: "Stop" }]);
  const after = applyAsk(resolved, resolved.askCheck!, read("Anything else?", "Merged and released"), NOW);
  assert.equal(after?.state, "done");
  assert.equal(after?.summary, "Merged and released");
});

test("a finished turn marks a thread that only the person could wake resolved", () => {
  const done = read("", "PR merged, 0.2.11 released", true);
  const idle = run([checklist(PLAN_DONE_BUT_ONE), reply("Merged and released 0.2.11."), { hook_event_name: "Stop" }]);
  assert.equal(idle.state, "waiting");
  const resolved = applyAsk(idle, idle.askCheck!, done, NOW)!;
  assert.equal(resolved.state, "done");
  assert.equal(resolved.resolved, true);
  assert.equal(resolved.summary, "PR merged, 0.2.11 released");
  assert.equal(resolved.seq, idle.seq + 1);
  assert.equal(toPushData(resolved).state, "done");
  // A CI event doesn't reopen it; the person's message does.
  const wake = (source: string) => ({ hook_event_name: "UserPromptSubmit", clauder: { source } });
  assert.equal(run([wake("notification"), { hook_event_name: "Stop" }], resolved).state, "done");
  const reopened = run([wake("wake:mention")], resolved);
  assert.equal(reopened.state, "running");
  assert.equal(reopened.resolved, undefined);
  // Not when something will still wake it, a card or question is open, or the hooks are older.
  const later = tool("mcp__claude-code-remote__send_later", { delay_minutes: 30, message: "x" });
  const armed = run([checklist(PLAN_DONE_BUT_ONE), reply("Done."), later, { hook_event_name: "Stop" }]);
  assert.equal(applyAsk(armed, armed.askCheck!, done, NOW)?.state, "running");
  const decision = { hook_event_name: "PostToolUse", tool_name: "mcp__hearthbot__request_decision", tool_input: { question: "Which colour?" }, tool_response: {} };
  const card = run([checklist(PLAN_DONE_BUT_ONE), decision, reply("Done."), { hook_event_name: "Stop" }]);
  assert.equal(applyAsk(card, card.askCheck!, done, NOW)?.state, "waiting");
  const old = run([checklist(PLAN_DONE_BUT_ONE), { hook_event_name: "Stop", last_assistant_message: "Done." }], undefined, false);
  assert.equal(applyAsk(old, old.askCheck!, done, NOW)?.state, "idle");
  // Not finished: the thread keeps waiting.
  assert.equal(applyAsk(idle, idle.askCheck!, read("", "Draft ready for review"), NOW)?.state, "waiting");
});

test("only a permission prompt or a question form notification means waiting", () => {
  const base = run([created("1", "a")]);
  const form = { hook_event_name: "Notification", notification_type: "elicitation_dialog", message: "Claude needs your input" };
  assert.equal(run([form], base).needKind, "permission");
  // Without a notification type the message text is not guessed at.
  assert.equal(applyHook(base, { hook_event_name: "Notification", message: "Claude needs your permission to use Bash" }, NOW), null);
});

test("a decision card stays waiting while the checklist moves on", () => {
  const decision = { hook_event_name: "PostToolUse", tool_name: "mcp__hearthbot__request_decision", tool_input: { question: "Which font?" }, tool_response: {} };
  const list = run([checklist("Plan\n\n✱ One"), decision, checklist("Plan\n\n✓ One\n✱ Two"), { hook_event_name: "Stop" }]);
  assert.equal(list.state, "waiting");
  assert.equal(list.need, "Which font?");
});

const tool = (name: string, input: unknown, response: unknown = [{ type: "text", text: "{}" }]) => ({
  hook_event_name: "PostToolUse", cwd: "/home/user", tool_name: name, tool_input: input, tool_response: response,
});
const PLAN_DONE_BUT_ONE = "Plan\n\n✓ One\n○ Two";

test("CI on a subscribed PR counts as working; once it finishes the thread waits on the user", () => {
  const created = tool("mcp__github__create_pull_request", { owner: "o", repo: "r" }, [{ type: "text", text: '{"url":"https://github.com/o/r/pull/5"}' }]);
  const subscribe = tool("mcp__claude-code-remote__subscribe_pr_activity", { owner: "o", repo: "r", pullNumber: 5 }, "Subscribed");
  // Without a subscription, CI events don't wake the thread.
  assert.equal(run([checklist(PLAN_DONE_BUT_ONE), created, { hook_event_name: "Stop", cwd: "/home/user" }]).state, "waiting");
  const list = run([checklist(PLAN_DONE_BUT_ONE), created, subscribe, { hook_event_name: "Stop", cwd: "/home/user" }]);
  assert.equal(list.state, "running");
  assert.equal(list.pr?.ci, "running");
  assert.equal(list.pr?.subscribed, true);
  const data = toPushData(list);
  assert.deepEqual(JSON.parse(data.pr), { n: 5, u: "https://github.com/o/r/pull/5", s: "open", c: "running" });
  const passed = run([
    { hook_event_name: "UserPromptSubmit" },
    tool("mcp__github__pull_request_read", { owner: "o", repo: "r", pullNumber: 5, method: "get_check_runs" }, [{ type: "text", text: '{"check_runs":[{"status":"completed","conclusion":"success"}]}' }]),
    { hook_event_name: "Stop", cwd: "/home/user" },
  ], list);
  assert.equal(passed.state, "waiting");
  assert.equal(passed.pr?.ci, "passed");
  const unsubscribed = run([tool("mcp__claude-code-remote__unsubscribe_pr_activity", { owner: "o", repo: "r", pullNumber: 5 }, "ok"), { hook_event_name: "Stop" }], list);
  assert.equal(unsubscribed.state, "waiting");
});

test("a reminder counts as working until it is due", () => {
  const list = run([checklist(PLAN_DONE_BUT_ONE), tool("mcp__claude-code-remote__send_later", { delay_minutes: 60, message: "x" }), { hook_event_name: "Stop" }]);
  assert.equal(list.state, "running");
  assert.equal(list.wakeAt, "2026-11-22T11:00:00.000Z");
  assert.equal(toPushData(list).wakeAt, "2026-11-22T11:00:00.000Z");
});

test("resolving finishes the thread, a new message reopens it", () => {
  const list = run([checklist(PLAN_DONE_BUT_ONE), tool("mcp__hearthbot__set_thread_resolved", { resolved: true }), { hook_event_name: "Stop" }]);
  assert.equal(list.state, "done");
  const reopened = run([{ hook_event_name: "UserPromptSubmit" }], list);
  assert.equal(reopened.state, "running");
  assert.equal(reopened.resolved, undefined);
  // A person's message or a coordinator message reopens it; CI events and other background
  // wakes don't, so the resolved thread stays done after them.
  const prompt = (source: string) => ({ hook_event_name: "UserPromptSubmit", clauder: { source } });
  assert.equal(run([prompt("wake:mention")], list).resolved, undefined);
  assert.equal(run([prompt("relay")], list).resolved, undefined);
  const ci = run([prompt("notification"), { hook_event_name: "Stop" }], list);
  assert.equal(ci.state, "done");
  assert.equal(ci.resolved, true);
  assert.equal(run([prompt("other"), { hook_event_name: "Stop" }], list).state, "done");
});

test("a thread resolved on the phone is done until the person writes again", () => {
  // Every step done and a reply, then nothing armed: the thread waits for the person.
  const waiting = run([
    { hook_event_name: "UserPromptSubmit", clauder: { project: "Content", thread: "Icon board", source: "wake:mention" } },
    checklist("Plan\n\n✓ One\n✓ Two"),
    reply("Ready for review"),
    { hook_event_name: "Stop" },
  ]);
  assert.equal(waiting.state, "waiting");
  const resolved = resolveByUser(waiting, NOW)!;
  assert.equal(resolved.state, "done");
  assert.equal(resolved.resolved, true);
  assert.equal(resolved.need, undefined);
  assert.equal(resolved.seq, waiting.seq + 1);
  assert.equal(toPushData(resolved).state, "done");
  // Resolving twice, or a closed session, changes nothing.
  assert.equal(resolveByUser(resolved, NOW), null);
  assert.equal(resolveByUser({ ...waiting, state: "ended" }, NOW), null);
  // The pause between wakes and background events keep it done; the person's message reopens it.
  const prompt = (source: string) => ({ hook_event_name: "UserPromptSubmit", clauder: { source } });
  assert.equal(run([{ hook_event_name: "SessionEnd", reason: "other" }], resolved).state, "done");
  assert.equal(run([prompt("notification"), { hook_event_name: "Stop" }], resolved).state, "done");
  assert.equal(run([prompt("wake:mention")], resolved).resolved, undefined);
});

test("the last reply's link, the helpers and the usage reach the phone", () => {
  const list = run([
    checklist(PLAN_DONE_BUT_ONE),
    { ...reply("The mockup is up."), tool_input: { text: "The mockup is up.", attached_outputs: [{ kind: "frame", ref: "https://claude.ai/artifact/abc", title: "Mockup" }] } },
    {
      hook_event_name: "Stop",
      background_tasks: [{ id: "a", type: "subagent", status: "running" }, { id: "b", type: "shell", status: "running" }],
      clauder: { usage: [{ model: "claude-sonnet-5-5", input: 1_000_000, output: 0 }] },
    },
  ]);
  assert.equal(list.state, "running");
  const data = toPushData(list);
  assert.equal(data.link, "https://claude.ai/artifact/abc");
  assert.equal(data.linkTitle, "Mockup");
  assert.equal(data.helpers, "2");
  assert.equal(data.tokens, "1000000");
  assert.equal(data.cost, "2");
});

test("a CI or reminder wake keeps an open decision card or question waiting", () => {
  // From the Worker log (22 nov 2026): a card went up, and a GitHub event woke the thread 7
  // seconds later, before the person had answered.
  const decision = { hook_event_name: "PostToolUse", tool_name: "mcp__hearthbot__request_decision", tool_input: { question: "Add a summary?" }, tool_response: {} };
  const wake = (source: string) => ({ hook_event_name: "UserPromptSubmit", clauder: { source } });
  const stop = { hook_event_name: "Stop" };
  const card = run([checklist(PLAN_DONE_BUT_ONE), decision, stop, wake("notification"), tool("ReadNotifications", {}), stop]);
  assert.equal(card.state, "waiting");
  assert.equal(card.needKind, "decision");
  assert.equal(card.need, "Add a summary?");
  assert.equal(run([wake("wake:decision-chosen")], card).state, "running");
  // The person writing about something else, or a coordinator message, leaves the card open.
  assert.equal(run([wake("wake:mention")], card).state, "waiting");
  assert.equal(run([wake("relay")], card).state, "waiting");
  // Older hook scripts don't say where a prompt comes from: any prompt answers.
  assert.equal(run([{ hook_event_name: "UserPromptSubmit" }], card).state, "running");
  // Claude may still close the card itself.
  assert.equal(run([wake("notification"), tool("mcp__hearthbot__close_decision", {}), stop], card).state, "waiting");
  assert.equal(run([wake("notification"), tool("mcp__hearthbot__close_decision", {})], card).state, "running");

  // A question the model read in the last reply is kept the same way.
  const asked = run([checklist(PLAN_DONE_BUT_ONE), reply("Shall I release?"), stop]);
  const question = applyAsk(asked, asked.askCheck!, { question: "Shall I release?", summary: "", finished: false }, NOW)!;
  assert.equal(question.needKind, "question");
  const later = run([wake("notification"), checklist("Plan\n\n✓ One\n✱ Two"), stop], question);
  assert.equal(later.state, "waiting");
  assert.equal(later.need, "Shall I release?");
  assert.equal(run([wake("relay")], question).state, "running");
  // A permission prompt is answered within its turn, so a new prompt ends it.
  const permission = run([checklist(PLAN_DONE_BUT_ONE), { hook_event_name: "Notification", notification_type: "permission_prompt", message: "Allow Bash?" }]);
  assert.equal(run([wake("notification")], permission).state, "running");
});

test("the summary stays through CI and reminder wakes; only the person's message clears it", () => {
  const wake = (source: string) => ({ hook_event_name: "UserPromptSubmit", clauder: { source } });
  const ended = run([checklist(PLAN_DONE_BUT_ONE), reply("PR open, CI runs."), { hook_event_name: "Stop" }]);
  const summed = applyAsk(ended, ended.askCheck!, read("", "PR #5 open, CI running"), NOW)!;
  // From the log: the PR subscription's own event wakes the thread a second later, and that turn
  // ends without a reply.
  const echo = run([wake("notification"), tool("ReadNotifications", {}), { hook_event_name: "Stop" }], summed);
  assert.equal(echo.summary, "PR #5 open, CI running");
  assert.equal(run([wake("wake:mention")], echo).summary, undefined);
});

test("the model's answer still counts after a pause or another event, until the next prompt", () => {
  const ended = run([checklist(PLAN_DONE_BUT_ONE), reply("Shall I merge?"), { hook_event_name: "Stop" }]);
  const paused = run([{ hook_event_name: "SessionEnd", reason: "other" }], { ...ended, project: "P" });
  assert.equal(paused.askCheck, "Shall I merge?");
  assert.equal(applyAsk(paused, "Shall I merge?", read("", "PR ready"), NOW)?.summary, "PR ready");
  // A background wake doesn't answer the reply; the person's message does.
  const ci = run([{ hook_event_name: "UserPromptSubmit", clauder: { source: "notification" } }], paused);
  assert.equal(applyAsk(ci, "Shall I merge?", read("Merge?"), NOW)?.state, "waiting");
  const next = run([{ hook_event_name: "UserPromptSubmit", clauder: { source: "wake:mention" } }], paused);
  assert.equal(applyAsk(next, "Shall I merge?", read("Merge?"), NOW), null);
});

test("a card stays waiting while the person asks something else and Claude works on", () => {
  // From the Worker log (23 nov 2026, Noter thread): a card went up, the person asked a side
  // question, Claude answered and asked about the card again, then kept building while CI-like
  // notifications came in. The phone showed it as working until the person picked.
  const wake = (source: string) => ({ hook_event_name: "UserPromptSubmit", clauder: { source } });
  const decision = { hook_event_name: "PostToolUse", tool_name: "mcp__hearthbot__request_decision", tool_input: { question: "Should Sort wait for Save?" }, tool_response: {} };
  const list = run([
    wake("wake:mention"),
    wake("other"),
    decision,
    reply("Mostly yes, with one difference."),
    checklist(PLAN_DONE_BUT_ONE),
    wake("wake:mention"),
    reply("True. In Notion, an unsaved filter still syncs."),
    tool("Bash", { command: "ls" }),
    wake("notification"),
    wake("wake:mention"),
    reply("Shall I build it that way, which is the first option on the card?"),
    tool("Bash", { command: "./gradlew test" }),
    wake("notification"),
  ]);
  assert.equal(list.state, "waiting");
  assert.equal(list.need, "Should Sort wait for Save?");
  // The background notification didn't drop the reply, so the turn's end reads it.
  const ended = run([{ hook_event_name: "Stop" }], list);
  assert.equal(ended.askCheck, "Shall I build it that way, which is the first option on the card?");
  assert.equal(ended.state, "waiting");
  // The person's pick, or Claude closing the card after an answer in words, ends the wait.
  assert.equal(run([wake("wake:decision-chosen")], ended).state, "running");
  assert.equal(run([wake("wake:mention"), tool("mcp__hearthbot__close_decision", { chose: 0 })], ended).state, "running");
});

test("a finished checklist doesn't show Done while Claude works on the person's next message", () => {
  const finished = run([checklist("Plan\n\n✓ One\n✓ Two")]);
  assert.equal(finished.state, "done");
  const asked = run([{ hook_event_name: "UserPromptSubmit", clauder: { source: "wake:mention" } }], finished);
  assert.equal(asked.state, "running");
  assert.equal(toPushData(asked).state, "running");
  // A new checklist counts again, also one that is finished at once.
  assert.equal(run([checklist("Plan\n\n✓ One\n✓ Two\n✓ Three")], asked).state, "done");
  assert.equal(run([checklist("Plan\n\n✓ One\n✓ Two\n✱ Three")], asked).state, "running");
  // A background wake leaves a finished checklist as it was.
  assert.equal(run([{ hook_event_name: "UserPromptSubmit", clauder: { source: "notification" } }], finished).state, "done");
});

test("seq never goes back, so a session the Worker forgot still reaches the phone", () => {
  const old = run([checklist(PLAN_DONE_BUT_ONE)]);
  const fresh = emptyList("cse_abc", null, "2026-11-24T10:00:00.000Z");
  const later = applyHook(fresh, checklist(PLAN_DONE_BUT_ONE), "2026-11-24T10:00:00.000Z", true)!;
  assert.ok(later.seq > old.seq);
});

test("a git push or pushed files start a subscribed PR's CI again", () => {
  const created = tool("mcp__github__create_pull_request", { owner: "o", repo: "r" }, [{ type: "text", text: '{"url":"https://github.com/o/r/pull/5"}' }]);
  const subscribe = tool("mcp__claude-code-remote__subscribe_pr_activity", { owner: "o", repo: "r", pullNumber: 5 }, "Subscribed");
  const passed = tool("mcp__github__pull_request_read", { owner: "o", repo: "r", pullNumber: 5, method: "get_check_runs" },
    [{ type: "text", text: '{"check_runs":[{"status":"completed","conclusion":"success"}]}' }]);
  const stop = { hook_event_name: "Stop" };
  const green = run([checklist(PLAN_DONE_BUT_ONE), created, subscribe, passed, stop]);
  assert.equal(green.state, "waiting");
  const push = (command: string) => tool("Bash", { command }, { stdout: "", stderr: "" });
  assert.equal(run([push("cd /home/user/clauder && git push -u origin fix"), stop], green).state, "running");
  assert.equal(run([push("git push"), stop], green).pr?.ci, "running");
  assert.equal(run([tool("mcp__github__push_files", { owner: "o", repo: "r", branch: "fix" }), stop], green).state, "running");
  assert.equal(applyHook(green, push("git status"), NOW, true), null);
  assert.equal(applyHook(green, push("echo git pushed"), NOW, true), null);
});

test("a reminder's time comes from the tool's answer when it gives one", () => {
  const at = tool("mcp__claude-code-remote__send_later", { at: "2026-11-22T21:00:00[Europe/Brussels]", message: "x" },
    [{ type: "text", text: '{"fire_at":"2026-11-22T20:00:00Z","trigger_id":"trig_1"}' }]);
  assert.equal(run([checklist(PLAN_DONE_BUT_ONE), at]).wakeAt, "2026-11-22T20:00:00.000Z");
});

test("the phone hears when a session runs old hook settings", () => {
  assert.equal(toPushData(run([checklist(PLAN_DONE_BUT_ONE)])).hooks, "");
  const old = applyHook(emptyList("cse_abc", null, NOW), checklist(PLAN_DONE_BUT_ONE), NOW, undefined)!;
  assert.equal(toPushData(old).hooks, "old");
});
