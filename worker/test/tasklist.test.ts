import { test } from "node:test";
import assert from "node:assert/strict";
import {
  applyHook,
  clip,
  emptyList,
  progress,
  sessionKey,
  titleFromCwd,
  toPushData,
  type SessionList,
} from "../src/tasklist.ts";

const NOW = "2026-11-22T10:00:00.000Z";
const CWD = "/home/user/clauder";

function run(events: unknown[], start: SessionList = emptyList("cse_abc", null, NOW)): SessionList {
  let list = start;
  for (const event of events) list = applyHook(list, event, NOW) ?? list;
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
  assert.equal(once.seq, 1);
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

test("Stop with open tasks means idle, a new prompt resumes", () => {
  const idle = run([created("1", "a"), { hook_event_name: "Stop", cwd: CWD }]);
  assert.equal(idle.state, "idle");
  assert.equal(run([{ hook_event_name: "UserPromptSubmit" }], idle).state, "running");
  assert.equal(run([update("1", "pending", "in_progress")], idle).state, "running");
});

test("Stop while a task is in progress keeps the session running", () => {
  const list = run([created("1", "a"), update("1", "pending", "in_progress"), { hook_event_name: "Stop", cwd: CWD }]);
  assert.equal(list.state, "running");
});

test("Stop while a checklist step is in progress keeps a project thread running", () => {
  const list = run([
    checklist("Fix crash\n\n✓ Find it\n✓ Open a pull request\n✱ Merge when CI is green\n○ Release"),
    { hook_event_name: "Stop", cwd: "/home/user" },
  ]);
  assert.equal(list.state, "running");
  // The steps decide, not Claude Code's own tasks.
  const noStep = run([
    created("1", "a"), update("1", "pending", "in_progress"),
    checklist("Plan\n\n✓ One\n○ Two"),
    { hook_event_name: "Stop", cwd: "/home/user" },
  ]);
  assert.equal(noStep.state, "idle");
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

test("Stop after every task is done stays done", () => {
  const list = run([created("1", "a"), completed("1", "a"), { hook_event_name: "Stop" }]);
  assert.equal(list.state, "done");
});

test("SessionEnd ends the list", () => {
  assert.equal(run([created("1", "a"), { hook_event_name: "SessionEnd", reason: "other" }]).state, "ended");
});

test("a project thread pauses between wakes instead of ending, and keeps an open decision card", () => {
  const prompt = { hook_event_name: "UserPromptSubmit", clauder: { project: "Code", thread: "Icons" } };
  const end = { hook_event_name: "SessionEnd", reason: "other" };
  assert.equal(run([prompt, created("1", "a"), end]).state, "idle");
  const decision = { hook_event_name: "PostToolUse", tool_name: "mcp__hearthbot__request_decision", tool_input: { question: "Which finish?" }, tool_response: {} };
  assert.equal(run([prompt, created("1", "a"), decision, { hook_event_name: "Stop" }, end]).state, "waiting");
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
  // The prompt is answered within the turn, so the turn's end means idle.
  const after = run([{ hook_event_name: "Stop" }], prompted);
  assert.equal(after.state, "idle");
  assert.equal(after.need, undefined);
});

const reply = (text: string) => ({
  hook_event_name: "PostToolUse", cwd: "/home/user", tool_name: "mcp__hearthbot__post_message",
  tool_input: { text }, tool_response: {},
});
const MOCKUP_PLAN = "Playful Material 3 mockup\n\n✓ Read the screens\n✓ Design a mockup\n✓ Share the mockup for approval\n✓ Add wordmark options\n○ After approval: build it in the app and open a pull request";

test("a reply's text never makes a session wait: no hook field says it waits on the user", () => {
  const asked = run([
    checklist("Plan\n\n✓ One\n○ Two"),
    reply("Which do you like? I'd go with A."),
    { hook_event_name: "Stop", cwd: "/home/user" },
  ]);
  assert.equal(asked.state, "idle");
  assert.equal(asked.need, undefined);
  assert.equal(run([checklist(MOCKUP_PLAN), { hook_event_name: "Stop", cwd: "/home/user" }]).state, "idle");
  const plain = run([created("1", "a"), { hook_event_name: "Stop", cwd: CWD, last_assistant_message: "Want me to add tests too?" }]);
  assert.equal(plain.state, "idle");
  const busy = run([checklist("Plan\n\n✓ One\n✱ Two"), reply("Waiting on CI."), { hook_event_name: "Stop", cwd: "/home/user" }]);
  assert.equal(busy.state, "running");
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

test("waiting on CI counts as working, and a passed CI makes the thread idle", () => {
  const created = tool("mcp__github__create_pull_request", { owner: "o", repo: "r" }, [{ type: "text", text: '{"url":"https://github.com/o/r/pull/5"}' }]);
  const list = run([checklist(PLAN_DONE_BUT_ONE), created, { hook_event_name: "Stop", cwd: "/home/user" }]);
  assert.equal(list.state, "running");
  assert.equal(list.pr?.ci, "running");
  const data = toPushData(list);
  assert.deepEqual(JSON.parse(data.pr), { n: 5, u: "https://github.com/o/r/pull/5", s: "open", c: "running" });
  const passed = run([
    { hook_event_name: "UserPromptSubmit" },
    tool("mcp__github__pull_request_read", { owner: "o", repo: "r", pullNumber: 5, method: "get_check_runs" }, [{ type: "text", text: '{"check_runs":[{"status":"completed","conclusion":"success"}]}' }]),
    { hook_event_name: "Stop", cwd: "/home/user" },
  ], list);
  assert.equal(passed.state, "idle");
  assert.equal(passed.pr?.ci, "passed");
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
