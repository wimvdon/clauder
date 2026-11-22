import { test } from "node:test";
import assert from "node:assert/strict";
import { applyPrTool, ciFromCheckRuns, linkFromOutputs, usageFrom, wakeAtFrom, responseText } from "../src/extras.ts";

const blocks = (value: unknown) => [{ type: "text", text: JSON.stringify(value) }];
const PR = { owner: "wimvdon", repo: "clauder", pullNumber: 22 };

test("a created PR is followed, with CI running", () => {
  const pr = applyPrTool(undefined, "mcp__github__create_pull_request", {}, blocks({ id: "1", url: "https://github.com/wimvdon/clauder/pull/22" }));
  assert.deepEqual(pr, { number: 22, url: "https://github.com/wimvdon/clauder/pull/22", state: "open", ci: "running" });
});

test("check runs, merges and closes update the PR the session follows", () => {
  const open = { number: 22, url: "https://github.com/wimvdon/clauder/pull/22", state: "open" as const, ci: "running" as const };
  const runs = (...r: unknown[]) => blocks({ total_count: r.length, check_runs: r });
  const read = (response: unknown, pr = PR) => applyPrTool(open, "mcp__github__pull_request_read", { ...pr, method: "get_check_runs" }, response);
  assert.equal(read(runs({ status: "completed", conclusion: "success" }, { status: "completed", conclusion: "skipped" }))?.ci, "passed");
  assert.equal(read(runs({ status: "in_progress" }, { status: "completed", conclusion: "success" }))?.ci, "running");
  assert.equal(read(runs({ status: "in_progress" }, { status: "completed", conclusion: "failure" }))?.ci, "failed");
  // Another PR's checks don't count.
  assert.equal(read(runs({ status: "completed", conclusion: "failure" }), { ...PR, pullNumber: 9 }), open);
  assert.equal(applyPrTool(open, "mcp__github__merge_pull_request", PR, blocks({ merged: true }))?.state, "merged");
  assert.equal(applyPrTool(open, "mcp__github__merge_pull_request", PR, blocks({ merged: false })), open);
  assert.equal(applyPrTool(open, "mcp__github__update_pull_request", { ...PR, state: "closed" }, blocks({}))?.state, "closed");
});

test("GitHub notifications finish CI", () => {
  const open = { number: 22, url: "https://github.com/wimvdon/clauder/pull/22", state: "open" as const, ci: "running" as const };
  const event = (data: string) => `--- Notification 1 of 1 ---\n<event source="github" kind="check_suite.completed" from="system">\n${data}\n</event>`;
  // The real event: "no check suite on the head is still running or failed", with no conclusion.
  const passed = event(`{"head_sha":"abc","pr":"wimvdon/clauder#22"}`);
  assert.equal(applyPrTool(open, "ReadNotifications", {}, passed)?.ci, "passed");
  const failed = event(`{"head_sha":"abc","conclusion":"failure","pr":"wimvdon/clauder#22"}`);
  assert.equal(applyPrTool(open, "ReadNotifications", {}, failed)?.ci, "failed");
  assert.equal(applyPrTool(open, "ReadNotifications", {}, passed.replace("#22", "#23")), open);
  const merged = `<event source="github" kind="pull_request.closed" from="system" trust="relay">\n    <!-- The PR has been merged. -->\n    {"outcome":"merged","pr":"wimvdon/clauder#22"}\n</event>`;
  assert.equal(applyPrTool(open, "ReadNotifications", {}, merged)?.state, "merged");
  const closed = merged.replace('"merged"', '"closed"');
  assert.equal(applyPrTool(open, "ReadNotifications", {}, closed)?.state, "closed");
});

test("subscribing follows a PR the session didn't create", () => {
  const pr = applyPrTool(undefined, "mcp__claude-code-remote__subscribe_pr_activity", PR, "Subscribed");
  assert.equal(pr?.url, "https://github.com/wimvdon/clauder/pull/22");
});

test("ciFromCheckRuns ignores an empty list", () => {
  assert.equal(ciFromCheckRuns({ check_runs: [] }), null);
  assert.equal(responseText([{ type: "text", text: "a" }, { type: "text", text: "b" }]), "a\nb");
});

test("only web links and Artifacts become an open link", () => {
  assert.deepEqual(
    linkFromOutputs([{ kind: "file", ref: "/mnt/project-files/a.md" }, { kind: "frame", ref: "https://claude.ai/artifact/x", title: "Player UI mockup" }]),
    { url: "https://claude.ai/artifact/x", title: "Player UI mockup" },
  );
  assert.equal(linkFromOutputs([{ kind: "link", ref: "javascript:alert(1)" }]), null);
  assert.equal(linkFromOutputs(undefined), null);
});

test("reminders: delays and RFC 3339 times", () => {
  const now = "2026-11-22T15:00:00.000Z";
  assert.equal(wakeAtFrom({ delay_minutes: 30 }, now), "2026-11-22T15:30:00.000Z");
  assert.equal(wakeAtFrom({ at: "2026-11-22T19:00:00Z" }, now), "2026-11-22T19:00:00.000Z");
  assert.equal(wakeAtFrom({ at: "2026-11-22T20:00:00[Europe/Brussels]" }, now), null);
});

test("usage adds tokens and prices them per model", () => {
  const usage = usageFrom([
    { model: "claude-opus-5-5", input: 1_000_000, output: 100_000, cacheRead: 10_000_000, cacheWrite5m: 0, cacheWrite1h: 100_000 },
    { model: "claude-unknown", input: 5 },
  ]);
  // 4 + 2 + 2 + 0.8 = 8.8
  assert.deepEqual(usage, { tokens: 11_200_005, cost: 8.8 });
  assert.equal(usageFrom([]), null);
});
