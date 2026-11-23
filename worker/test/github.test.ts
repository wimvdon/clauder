import { test } from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import {
  appFromConversion, appManifest, applyEvent, applyPrChange, changeOf, ciOf, emptyTrack, prUrlsOf, startPage, verifySignature,
  type PrTrack,
} from "../src/github.ts";
import { applyPrUpdate, emptyList, toPushData } from "../src/tasklist.ts";

const URL22 = "https://github.com/wimvdon/clauder/pull/22";
const repository = { id: 1, full_name: "wimvdon/clauder" };
const prs = [{ number: 22, head: { sha: "aaa" }, base: { repo: { id: 1 } } }];

function workflowRun(over: Record<string, unknown> = {}, action = "completed") {
  return {
    action,
    repository,
    workflow_run: {
      id: 100, run_attempt: 1, workflow_id: 7, head_sha: "aaa", status: "completed", conclusion: "success",
      created_at: "2026-11-23T10:00:00Z", updated_at: "2026-11-23T10:05:00Z", pull_requests: prs, ...over,
    },
  };
}

test("the signature must be GitHub's HMAC of the exact body", async () => {
  const body = '{"zen":"Keep it logically awesome."}';
  const sig = `sha256=${createHmac("sha256", "s3cret").update(body).digest("hex")}`;
  assert.equal(await verifySignature("s3cret", body, sig), true);
  assert.equal(await verifySignature("other", body, sig), false);
  assert.equal(await verifySignature("s3cret", body + " ", sig), false);
  assert.equal(await verifySignature("s3cret", body, null), false);
  assert.equal(await verifySignature("s3cret", body, "sha1=abc"), false);
  assert.equal(await verifySignature("", body, sig), false);
});

test("events name the PRs of their own repository", () => {
  assert.deepEqual(prUrlsOf("workflow_run", workflowRun()), [URL22]);
  const fork = workflowRun({ pull_requests: [{ number: 5, base: { repo: { id: 99 } } }] });
  assert.deepEqual(prUrlsOf("workflow_run", fork), []);
  assert.deepEqual(prUrlsOf("pull_request", { repository, pull_request: { number: 22 } }), [URL22]);
  assert.deepEqual(prUrlsOf("check_suite", { repository, check_suite: {} }), []);
});

test("a workflow runs, fails, and passes on a re-run", () => {
  let t = emptyTrack(URL22);
  t = applyEvent(t, "workflow_run", workflowRun({ status: "in_progress", conclusion: null, updated_at: "2026-11-23T10:01:00Z" }, "in_progress"));
  assert.equal(ciOf(t), "running");
  t = applyEvent(t, "workflow_run", workflowRun({ conclusion: "failure" }));
  assert.equal(ciOf(t), "failed");
  // A late in_progress event from before doesn't undo the result.
  const late = applyEvent(t, "workflow_run", workflowRun({ status: "in_progress", conclusion: null, updated_at: "2026-11-23T10:01:00Z" }, "in_progress"));
  assert.equal(ciOf(late), "failed");
  t = applyEvent(t, "workflow_run", workflowRun({ run_attempt: 2, status: "queued", conclusion: null, updated_at: "2026-11-23T10:06:00Z" }, "requested"));
  assert.equal(ciOf(t), "running");
  t = applyEvent(t, "workflow_run", workflowRun({ run_attempt: 2, updated_at: "2026-11-23T10:09:00Z" }));
  assert.equal(ciOf(t), "passed");
});

test("CI passes only when every workflow finished", () => {
  let t = emptyTrack(URL22);
  t = applyEvent(t, "workflow_run", workflowRun());
  t = applyEvent(t, "workflow_run", workflowRun({ id: 101, workflow_id: 8, status: "in_progress", conclusion: null }, "in_progress"));
  assert.equal(ciOf(t), "running");
  t = applyEvent(t, "workflow_run", workflowRun({ id: 101, workflow_id: 8, conclusion: "skipped", updated_at: "2026-11-23T10:07:00Z" }));
  assert.equal(ciOf(t), "passed");
});

test("a push starts the checks over, and old commits' runs are ignored", () => {
  let t = applyEvent(emptyTrack(URL22), "workflow_run", workflowRun({ conclusion: "failure" }));
  assert.equal(t.head, "aaa");
  const push = { action: "synchronize", repository, pull_request: { number: 22, state: "open", head: { sha: "bbb" }, updated_at: "2026-11-23T10:10:00Z" } };
  t = applyEvent(t, "pull_request", push);
  assert.equal(t.head, "bbb");
  assert.equal(ciOf(t), null);
  // The old commit's re-run, created before the push, doesn't count.
  const old = applyEvent(t, "workflow_run", workflowRun({ run_attempt: 2, updated_at: "2026-11-23T10:11:00Z" }));
  assert.equal(old, t);
  t = applyEvent(t, "workflow_run", workflowRun({ id: 102, head_sha: "bbb", status: "in_progress", conclusion: null, created_at: "2026-11-23T10:10:05Z" }, "in_progress"));
  assert.equal(ciOf(t), "running");
});

test("a run for a newer commit arriving before its push event takes over", () => {
  let t = applyEvent(emptyTrack(URL22), "workflow_run", workflowRun());
  t = applyEvent(t, "workflow_run", workflowRun({ id: 103, head_sha: "ccc", status: "queued", conclusion: null, created_at: "2026-11-23T10:20:00Z" }, "requested"));
  assert.equal(t.head, "ccc");
  assert.equal(ciOf(t), "running");
});

test("GitHub Actions' own check events are left to the workflow", () => {
  const actions = { slug: "github-actions" };
  const run = { action: "completed", repository, check_run: { status: "completed", conclusion: "success", head_sha: "aaa", app: actions, check_suite: { id: 5, app: actions }, pull_requests: prs, completed_at: "2026-11-23T10:02:00Z" } };
  assert.equal(ciOf(applyEvent(emptyTrack(URL22), "check_run", run)), null);
});

test("another app's check suite runs, fails early and completes", () => {
  const app = { slug: "codecov" };
  const run = (status: string, conclusion: string | null, at: string) => ({
    action: status === "completed" ? "completed" : "created", repository,
    check_run: { status, conclusion, head_sha: "aaa", app, check_suite: { id: 9, app }, pull_requests: prs, started_at: at, completed_at: status === "completed" ? at : null },
  });
  let t = applyEvent(emptyTrack(URL22), "check_run", run("completed", "success", "2026-11-23T10:01:00Z"));
  assert.equal(ciOf(t), "running");
  t = applyEvent(t, "check_run", run("completed", "failure", "2026-11-23T10:02:00Z"));
  assert.equal(ciOf(t), "failed");
  const suite = { action: "completed", repository, check_suite: { id: 9, app, head_sha: "aaa", status: "completed", conclusion: "success", pull_requests: prs, updated_at: "2026-11-23T10:04:00Z" } };
  assert.equal(ciOf(applyEvent(t, "check_suite", suite)), "passed");
});

test("PR state and reviews follow the pull_request events", () => {
  const pr = (action: string, extra: Record<string, unknown>) => ({ action, repository, pull_request: { number: 22, state: "open", head: { sha: "aaa" }, ...extra } });
  let t = applyEvent(emptyTrack(URL22), "pull_request", pr("opened", {}));
  assert.equal(t.state, "open");
  t = applyEvent(t, "pull_request_review", { action: "submitted", repository, pull_request: { number: 22 }, review: { state: "CHANGES_REQUESTED" } });
  assert.equal(t.review, "changes_requested");
  t = applyEvent(t, "pull_request_review", { action: "submitted", repository, pull_request: { number: 22 }, review: { state: "commented" } });
  assert.equal(t.review, "changes_requested");
  t = applyEvent(t, "pull_request_review", { action: "dismissed", repository, pull_request: { number: 22 }, review: { state: "dismissed" } });
  assert.equal(t.review, undefined);
  t = applyEvent(t, "pull_request", pr("closed", { state: "closed", merged: true }));
  assert.equal(t.state, "merged");
  assert.equal(applyEvent(emptyTrack(URL22), "pull_request", pr("closed", { state: "closed", merged: false })).state, "closed");
});

test("a session's followed PR takes the webhook's CI, and is pushed", () => {
  const now = "2026-11-23T10:30:00Z";
  const list = { ...emptyList("cse_1", null, now), pr: { number: 22, url: URL22, state: "open" as const, ci: "running" as const, subscribed: true } };
  const track: PrTrack = { ...applyEvent(emptyTrack(URL22), "workflow_run", workflowRun()), review: "approved" };
  const next = applyPrUpdate(list, changeOf(track), now);
  assert.equal(next?.pr?.ci, "passed");
  assert.equal(next?.pr?.subscribed, true);
  assert.ok(next!.seq > list.seq);
  assert.equal(JSON.parse(toPushData(next!).pr).r, "approved");
  // Nothing new, or another PR: no change.
  assert.equal(applyPrUpdate(next!, changeOf(track), now), null);
  assert.equal(applyPrUpdate(list, { url: "https://github.com/wimvdon/clauder/pull/9", ci: "failed" }, now), null);
  // A repository without CI leaves the CI as it is.
  assert.equal(applyPrChange(list.pr, { url: URL22, state: "open" }), list.pr);
});

test("the app manifest points GitHub at this Worker and only reads", () => {
  const m = appManifest("clauder.wim-d57.workers.dev");
  assert.equal(m.name, "Clauder wim-d57");
  assert.deepEqual(m.hook_attributes, { url: "https://clauder.wim-d57.workers.dev/github", active: true });
  assert.equal(m.redirect_url, "https://clauder.wim-d57.workers.dev/github/callback");
  assert.ok(Object.values(m.default_permissions as Record<string, string>).every((p) => p === "read"));
  const page = startPage("clauder.wim-d57.workers.dev", "abc");
  assert.match(page, /action="https:\/\/github\.com\/settings\/apps\/new\?state=abc"/);
  assert.doesNotMatch(page, /value="\{"/);
});

test("only the webhook secret and where to install are kept of the new app", () => {
  const app = appFromConversion({ id: 3, slug: "clauder-wim-d57", html_url: "https://github.com/apps/clauder-wim-d57", webhook_secret: "w", pem: "KEY", client_secret: "c" }, "now");
  assert.deepEqual(app, { id: 3, slug: "clauder-wim-d57", url: "https://github.com/apps/clauder-wim-d57", webhookSecret: "w", createdAt: "now" });
  assert.equal(appFromConversion({ id: 3, slug: "x", html_url: "https://evil.example/x", webhook_secret: "w" }, "now"), null);
  assert.equal(appFromConversion({ id: 3, slug: "x", html_url: "https://github.com/apps/x" }, "now"), null);
});
