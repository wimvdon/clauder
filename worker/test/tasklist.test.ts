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

test("Stop with open tasks means waiting, a new prompt resumes", () => {
  const waiting = run([created("1", "a"), { hook_event_name: "Stop", cwd: CWD }]);
  assert.equal(waiting.state, "waiting");
  assert.equal(run([{ hook_event_name: "UserPromptSubmit" }], waiting).state, "running");
  assert.equal(run([update("1", "pending", "in_progress")], waiting).state, "running");
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
