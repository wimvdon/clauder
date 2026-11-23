import { test } from "node:test";
import assert from "node:assert/strict";
import { applyHook, emptyList, resolveByUser, toPushData, BLOCKED, COMPACTING, PUSH_FAILED, type SessionList } from "../src/tasklist.ts";

// The hook events added at hooks level L4: API errors, usage limits, auto mode denials, subagents,
// compaction, the model, effort and permission mode.

const NOW = "2026-11-23T10:00:00.000Z";
const CWD = "/home/user/clauder";

function run(events: unknown[], start: SessionList = emptyList("cse_abc", null, NOW)): SessionList {
  let list = start;
  for (const event of events) list = applyHook(list, event, NOW, true) ?? list;
  return list;
}

const working = [
  { hook_event_name: "UserPromptSubmit", cwd: CWD, clauder: { source: "wake:mention" } },
  { hook_event_name: "TaskCreated", cwd: CWD, task_id: "1", task_subject: "Build it" },
];
const stopFailure = (error: string, text = "API Error: Rate limit reached") => ({
  hook_event_name: "StopFailure", cwd: CWD, error, error_details: "429 Too Many Requests", last_assistant_message: text,
});
const notification = (type: string, message = "") => ({ hook_event_name: "Notification", cwd: CWD, notification_type: type, message });

test("an API error ends the turn waiting on the person, with the error text, instead of staying running", () => {
  const list = run([...working, stopFailure("rate_limit")]);
  assert.equal(list.state, "waiting");
  assert.equal(list.needKind, "error");
  assert.equal(list.error, "rate_limit");
  assert.equal(list.need, "API Error: Rate limit reached");
  assert.equal(list.askCheck, undefined, "the error text is not a reply for the model to read");
  const push = toPushData(list);
  assert.equal(push.error, "rate_limit");
  assert.equal(push.state, "waiting");
});

test("an API error wins over an open decision card", () => {
  const card = { hook_event_name: "PostToolUse", cwd: CWD, tool_name: "mcp__hearthbot__request_decision", tool_input: { question: "Which?" } };
  const list = run([...working, card, stopFailure("server_error", "API Error: Overloaded")]);
  assert.equal(list.needKind, "error");
  assert.equal(list.need, "API Error: Overloaded");
});

test("the next prompt or any work clears the error", () => {
  const failed = run([...working, stopFailure("server_error")]);
  const prompted = run([{ hook_event_name: "UserPromptSubmit", cwd: CWD, clauder: { source: "notification" } }], failed);
  assert.equal(prompted.state, "running");
  assert.equal(prompted.error, undefined);
  assert.equal(prompted.need, undefined);
  assert.equal(toPushData(prompted).error, "");
  const worked = run([{ hook_event_name: "TaskCreated", cwd: CWD, task_id: "2", task_subject: "More" }], failed);
  assert.equal(worked.error, undefined);
});

test("an odd error type is kept as unknown, and a session without a list ignores StopFailure", () => {
  assert.equal(run([...working, stopFailure("Weird Type!")]).error, "unknown");
  assert.equal(applyHook(emptyList("cse_abc", null, NOW), stopFailure("rate_limit"), NOW, true), null);
});

test("resolving on the phone clears the error", () => {
  const list = resolveByUser(run([...working, stopFailure("rate_limit")]), NOW)!;
  assert.equal(list.state, "done");
  assert.equal(list.error, undefined);
});

test("a usage limit Claude Code won't wait out waits on the person; one it waited out goes on", () => {
  const stale = run([...working, notification("quota_auto_resume_stale", "Usage limit reset while you were away")]);
  assert.equal(stale.state, "waiting");
  assert.equal(stale.error, "usage_limit");
  assert.equal(stale.need, "Usage limit reset while you were away");
  const disabled = run([...working, notification("quota_auto_resume_disabled", "Stopped waiting")]);
  assert.equal(disabled.error, "usage_limit");
  const resumed = run([notification("quota_auto_resume_fired")], stale);
  assert.equal(resumed.state, "running");
  assert.equal(resumed.error, undefined);
  // When nothing waits on an error, the resume changes nothing.
  assert.equal(applyHook(run(working), notification("quota_auto_resume_fired"), NOW, true), null);
});

test("an MCP server asking to open a URL waits on the person, like a question form", () => {
  const list = run([...working, notification("elicitation_url_dialog", "Sign in to GitHub")]);
  assert.equal(list.state, "waiting");
  assert.equal(list.needKind, "permission");
  assert.equal(list.need, "Sign in to GitHub");
});

test("auto mode refusing a tool call shows the policy on the now line, not the command", () => {
  const list = run([...working, {
    hook_event_name: "PermissionDenied", cwd: CWD, tool_name: "Bash",
    tool_input: { command: "rm -rf /tmp/build" }, reason: "[Irreversible Local Destruction]",
  }]);
  assert.deepEqual(list.doing, { tool: BLOCKED, detail: "Irreversible Local Destruction" });
  assert.equal(list.state, "running");
  assert.ok(!JSON.stringify(toPushData(list)).includes("rm -rf"));
});

test("a failed git push shows on the now line and leaves the pull request's CI alone", () => {
  const list = run([...working, {
    hook_event_name: "PostToolUseFailure", cwd: CWD, tool_name: "Bash",
    tool_input: { command: "git push -u origin x" }, error: "Exit code 1\nrejected", is_interrupt: false,
  }]);
  assert.deepEqual(list.doing, { tool: PUSH_FAILED });
  assert.equal(list.pr, undefined);
});

test("subagents count as helpers while they run; internal agents without a start are ignored", () => {
  const start = (id: string) => ({ hook_event_name: "SubagentStart", cwd: CWD, agent_id: id, agent_type: "Explore" });
  const stop = (id: string) => ({ hook_event_name: "SubagentStop", cwd: CWD, agent_id: id, agent_type: "Explore" });
  const two = run([...working, start("a1"), start("a2"), start("a2")]);
  assert.equal(two.helpers, 2);
  assert.deepEqual(two.agents, ["a1", "a2"]);
  assert.equal(toPushData(two).helpers, "2");
  assert.ok(!("agents" in toPushData(two)));
  const one = run([stop("a1")], two);
  assert.equal(one.helpers, 1);
  assert.equal(applyHook(one, stop("internal"), NOW, true), null);
  const none = run([stop("a2")], one);
  assert.equal(none.helpers, undefined);
  assert.equal(none.agents, undefined);
});

test("compaction shows on the now line until it is over", () => {
  const compacting = run([...working, { hook_event_name: "PreCompact", cwd: CWD, trigger: "auto", custom_instructions: null }]);
  assert.deepEqual(compacting.doing, { tool: COMPACTING });
  const after = run([{ hook_event_name: "PostCompact", cwd: CWD, trigger: "auto" }], compacting);
  assert.equal(after.doing, undefined);
  // The SessionStart that follows a compaction ends it too.
  const restarted = run([{ hook_event_name: "SessionStart", cwd: CWD, source: "compact" }], compacting);
  assert.equal(restarted.doing, undefined);
});

test("the model comes from SessionStart and follows model switches", () => {
  const started = run([...working, { hook_event_name: "SessionStart", cwd: CWD, source: "resume", model: "claude-opus-5-5[1m]" }]);
  assert.equal(started.model, "claude-opus-5-5[1m]");
  assert.equal(toPushData(started).model, "claude-opus-5-5[1m]");
  const switched = run([{ hook_event_name: "PostModelSwitch", cwd: CWD, from_model: "claude-opus-5-5", to_model: "claude-sonnet-5-5", source: "auto" }], started);
  assert.equal(switched.model, "claude-sonnet-5-5");
  // A SessionStart without a model (after /clear) keeps the one known; junk is refused.
  assert.equal(run([{ hook_event_name: "SessionStart", cwd: CWD, source: "clear" }], switched).model, "claude-sonnet-5-5");
  assert.equal(run([{ hook_event_name: "SessionStart", cwd: CWD, model: "<script>" }], switched).model, "claude-sonnet-5-5");
});

test("effort and permission mode come from any event that has them, and only known values count", () => {
  const list = run([...working, {
    hook_event_name: "PostToolUse", cwd: CWD, tool_name: "TaskUpdate", permission_mode: "plan", effort: { level: "high" },
    tool_input: { taskId: "1", status: "in_progress" }, tool_response: { success: true, taskId: "1", statusChange: { from: "pending", to: "in_progress" } },
  }]);
  assert.equal(list.effort, "high");
  assert.equal(list.mode, "plan");
  const push = toPushData(list);
  assert.equal(push.effort, "high");
  assert.equal(push.mode, "plan");
  const odd = run([{ hook_event_name: "TaskCreated", cwd: CWD, task_id: "9", task_subject: "x", permission_mode: "wild", effort: { level: "ultra" } }], list);
  assert.equal(odd.effort, "high");
  assert.equal(odd.mode, "plan");
});
