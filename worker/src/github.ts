// GitHub webhooks: real CI results and PR states for the pull requests sessions follow. GitHub
// sends check, workflow and pull request events to POST /github, signed with the webhook secret.
// The secret comes from a GitHub App the person creates in one go from a Clauder link (GitHub's
// app manifest flow), so they never copy it by hand. Plain logic, so it runs under plain Node tests.

import { prFromUrl, type CiState, type PrState, type PullRequest } from "./extras.ts";

type Json = Record<string, unknown>;

function obj(value: unknown): Json | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Json) : null;
}

function str(value: unknown): string | null {
  return typeof value === "string" && value.length > 0 ? value : null;
}

const encoder = new TextEncoder();

/** True when [signature] (the X-Hub-Signature-256 header, "sha256=<hex>") is the HMAC-SHA256 of
 * [body] with [secret]. */
export async function verifySignature(secret: string, body: string, signature: string | null): Promise<boolean> {
  const m = /^sha256=([0-9a-f]{64})$/.exec(signature?.trim() ?? "");
  if (!secret || !m) return false;
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  const bytes = new Uint8Array(32);
  for (let i = 0; i < 32; i++) bytes[i] = parseInt(m[1].slice(i * 2, i * 2 + 2), 16);
  // crypto.subtle.verify compares in constant time.
  return crypto.subtle.verify("HMAC", key, bytes, encoder.encode(body));
}

export type Review = "approved" | "changes_requested";

/** One group of checks on the PR's head: a GitHub Actions workflow, or another app's check suite. */
export interface CheckGroup {
  ci: CiState;
  /** When GitHub last changed it; an older event doesn't overwrite a newer one. */
  at: string;
  /** For a workflow: its run id and attempt, so a re-run replaces the run before it. */
  run?: number;
  attempt?: number;
}

/** What the Worker knows of one pull request from its webhooks. Kept in the Hub. */
export interface PrTrack {
  url: string;
  /** The head commit the checks are for, and when the PR or a run first said so. */
  head?: string;
  headAt?: string;
  state?: PrState;
  review?: Review;
  /** Keyed "wf:<workflow id>" for GitHub Actions, "cs:<check suite id>" for other apps. */
  groups: Record<string, CheckGroup>;
}

const FAILED = ["failure", "timed_out", "cancelled", "action_required", "startup_failure"];

/** The PR's CI from its check groups: failed when one failed, running while one runs, passed when
 * every one finished. Null when no checks were seen (a repository without CI). */
export function ciOf(track: PrTrack): CiState | null {
  const groups = Object.values(track.groups);
  if (groups.length === 0) return null;
  if (groups.some((g) => g.ci === "failed")) return "failed";
  if (groups.some((g) => g.ci === "running")) return "running";
  return "passed";
}

/** What a session's followed PR gets from its track. */
export interface PrChange {
  url: string;
  state?: PrState;
  ci?: CiState;
  review?: Review;
}

export function changeOf(track: PrTrack): PrChange {
  const ci = ciOf(track);
  return {
    url: track.url,
    ...(track.state ? { state: track.state } : {}),
    ...(ci ? { ci } : {}),
    ...(track.review ? { review: track.review } : {}),
  };
}

/** Applies [change] to the PR a session follows. Returns [current] when it isn't that PR or
 * nothing changes. */
export function applyPrChange(current: PullRequest | undefined, change: PrChange): PullRequest | undefined {
  if (!current || current.url !== change.url) return current;
  const next: PullRequest = { ...current };
  if (change.state) next.state = change.state;
  if (change.ci) next.ci = change.ci;
  if (change.review) next.review = change.review;
  else delete next.review;
  return JSON.stringify(next) === JSON.stringify(current) ? current : next;
}

function prUrl(repo: Json | null, number: unknown): string | null {
  const name = str(repo?.full_name);
  if (!name || typeof number !== "number") return null;
  return prFromUrl(`https://github.com/${name}/pull/${number}`)?.url ?? null;
}

/** The pull requests an event is about, by URL. Check and workflow events list the open PRs
 * whose head is the checked commit (none for a fork's PR). */
export function prUrlsOf(event: string, payload: Json): string[] {
  const repo = obj(payload.repository);
  if (event === "pull_request" || event === "pull_request_review") {
    const url = prUrl(repo, obj(payload.pull_request)?.number);
    return url ? [url] : [];
  }
  const subject = obj(payload[event]);
  const prs = subject?.pull_requests;
  if (!Array.isArray(prs)) return [];
  return prs.flatMap((pr) => {
    // A PR listed from another repository (a fork's base) isn't this repository's.
    const base = obj(obj(obj(pr)?.base)?.repo);
    if (base && base.id !== repo?.id) return [];
    const url = prUrl(repo, obj(pr)?.number);
    return url ? [url] : [];
  });
}

/** The commit a check or workflow event is about. */
export function headOf(event: string, payload: Json): string | null {
  const subject = obj(payload[event]);
  if (event === "check_run") return str(subject?.head_sha) ?? str(obj(subject?.check_suite)?.head_sha);
  return str(subject?.head_sha);
}

export function emptyTrack(url: string): PrTrack {
  return { url, groups: {} };
}

function later(a: string | undefined, b: string): boolean {
  return a === undefined || Date.parse(b) >= Date.parse(a);
}

/** The track for a new head commit: its checks start over. */
function onHead(track: PrTrack, head: string, at: string): PrTrack {
  if (track.head === head) return track;
  return { ...track, head, headAt: at, groups: {} };
}

/** Whether a check or workflow event for commit [head] at [at] counts: it is for the PR's head,
 * or for a commit pushed after it (its own pull_request event may still be on its way). */
function forHead(track: PrTrack, head: string | null, at: string): PrTrack | null {
  if (!head) return null;
  if (track.head === head) return track;
  if (track.headAt !== undefined && !later(track.headAt, at)) return null;
  return onHead(track, head, at);
}

function groupCi(status: unknown, conclusion: unknown): CiState {
  if (status !== "completed") return "running";
  return FAILED.includes(String(conclusion)) ? "failed" : "passed";
}

function setGroup(track: PrTrack, key: string, group: CheckGroup): PrTrack {
  const old = track.groups[key];
  if (old) {
    // A re-run (a higher attempt or a newer run) replaces the one before it, also when GitHub's
    // times are equal; otherwise the newer event wins.
    const rank = (g: CheckGroup) => [g.run ?? 0, g.attempt ?? 0];
    const [oldRun, oldAttempt] = rank(old);
    const [run, attempt] = rank(group);
    if (run < oldRun || (run === oldRun && attempt < oldAttempt)) return track;
    if (run === oldRun && attempt === oldAttempt && !later(old.at, group.at)) return track;
    if (old.ci === group.ci && old.at === group.at) return track;
  }
  return { ...track, groups: { ...track.groups, [key]: group } };
}

function isActions(app: Json | null): boolean {
  return str(app?.slug) === "github-actions";
}

/**
 * Applies one webhook event to a PR's track. Returns [track] unchanged when the event says
 * nothing new for it.
 *
 * - workflow_run: a GitHub Actions workflow on the head (requested, in_progress, completed).
 *   Actions' own check suites and runs are skipped: the workflow covers every job, including jobs
 *   that start only after others finish, so CI doesn't pass while a later job is still to come.
 * - check_suite / check_run: other apps' checks, one group per check suite.
 * - pull_request: a push (synchronize) starts the checks over; closed, reopened.
 * - pull_request_review: the latest approval or change request.
 */
export function applyEvent(track: PrTrack, event: string, payload: Json): PrTrack {
  const action = str(payload.action);
  switch (event) {
    case "workflow_run": {
      const run = obj(payload.workflow_run);
      const at = str(run?.updated_at) ?? str(run?.created_at);
      const workflow = run?.workflow_id;
      if (!run || !at || typeof workflow !== "number") return track;
      const base = forHead(track, headOf(event, payload), str(run.created_at) ?? at);
      if (!base) return track;
      return setGroup(base, `wf:${workflow}`, {
        ci: groupCi(run.status, run.conclusion),
        at,
        run: typeof run.id === "number" ? run.id : 0,
        attempt: typeof run.run_attempt === "number" ? run.run_attempt : 1,
      });
    }
    case "check_suite":
    case "check_run": {
      const subject = obj(payload[event]);
      const suite = event === "check_suite" ? subject : obj(subject?.check_suite);
      const app = obj(subject?.app) ?? obj(suite?.app);
      const id = suite?.id;
      if (!subject || isActions(app) || typeof id !== "number") return track;
      const at = str(subject.completed_at) ?? str(subject.updated_at) ?? str(subject.started_at) ?? str(subject.created_at);
      if (!at) return track;
      const base = forHead(track, headOf(event, payload), at);
      if (!base) return track;
      const key = `cs:${id}`;
      if (event === "check_suite") return setGroup(base, key, { ci: groupCi(subject.status, subject.conclusion), at });
      // A run that starts makes its suite run again (a re-run); a failed run fails it right away.
      // A run that passes says nothing about the rest of the suite: its check_suite event does.
      const ci = groupCi(subject.status, subject.conclusion);
      if (ci === "passed") return base.groups[key] ? base : setGroup(base, key, { ci: "running", at });
      return setGroup(base, key, { ci, at });
    }
    case "pull_request": {
      const pr = obj(payload.pull_request);
      if (!pr) return track;
      const at = str(pr.updated_at) ?? new Date().toISOString();
      let next = track;
      const head = str(obj(pr.head)?.sha);
      if (head && (action === "opened" || action === "reopened" || action === "synchronize")) {
        // The PR says what its head is, so it may replace a head seen from a check event.
        next = head === next.head ? next : { ...next, head, headAt: at, groups: {} };
      }
      const state: PrState = pr.merged === true ? "merged" : pr.state === "closed" ? "closed" : "open";
      return state === next.state ? next : { ...next, state };
    }
    case "pull_request_review": {
      const review = obj(payload.review);
      const kind = str(review?.state)?.toLowerCase();
      if (action === "dismissed") {
        if (!track.review) return track;
        const { review: _review, ...rest } = track;
        return rest;
      }
      if (action !== "submitted" || (kind !== "approved" && kind !== "changes_requested")) return track;
      return kind === track.review ? track : { ...track, review: kind };
    }
    default:
      return track;
  }
}

/** The events the GitHub App subscribes to, and the read permissions they need. */
export const APP_EVENTS = ["check_run", "check_suite", "pull_request", "pull_request_review", "workflow_run"];
export const APP_PERMISSIONS = { checks: "read", pull_requests: "read", actions: "read", metadata: "read" };

/** The manifest for GitHub's app manifest flow: a private app on the person's account that only
 * reads checks and PRs, and sends their webhooks to this Worker. */
export function appManifest(host: string): Json {
  const sub = host.split(".")[1] ?? host;
  return {
    name: `Clauder ${sub}`.slice(0, 34),
    url: `https://${host}/`,
    description: "Sends CI results and pull request states to the Clauder Worker, so the Clauder app shows them.",
    hook_attributes: { url: `https://${host}/github`, active: true },
    redirect_url: `https://${host}/github/callback`,
    public: false,
    default_permissions: APP_PERMISSIONS,
    default_events: APP_EVENTS,
  };
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

/** The page that hands the manifest to GitHub. It submits itself; the button is there for a
 * browser that blocks that. */
export function startPage(host: string, state: string): string {
  const action = `https://github.com/settings/apps/new?state=${encodeURIComponent(state)}`;
  const manifest = escapeHtml(JSON.stringify(appManifest(host)));
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Connect GitHub to Clauder</title>
<style>body{font:16px/1.5 system-ui,sans-serif;max-width:32rem;margin:3rem auto;padding:0 1rem}button{font:inherit;padding:.6rem 1.2rem}</style>
</head><body>
<h1>Connect GitHub to Clauder</h1>
<p>GitHub will ask you to create a small GitHub App that only reads checks and pull requests. Tap <b>Create GitHub App</b>, then install it on your repositories.</p>
<form id="f" method="post" action="${escapeHtml(action)}">
<input type="hidden" name="manifest" value="${manifest}">
<button type="submit">Continue to GitHub</button>
</form>
<script>document.getElementById("f").submit()</script>
</body></html>`;
}

/** A short page for a link that no longer works. */
export function messagePage(title: string, body: string): string {
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escapeHtml(title)}</title>
<style>body{font:16px/1.5 system-ui,sans-serif;max-width:32rem;margin:3rem auto;padding:0 1rem}</style>
</head><body><h1>${escapeHtml(title)}</h1><p>${escapeHtml(body)}</p></body></html>`;
}

/** What the Worker keeps of the app GitHub created: the webhook secret, and where to install it. */
export interface GithubApp {
  id: number;
  slug: string;
  url: string;
  webhookSecret: string;
  createdAt: string;
}

/** Reads GitHub's answer to the manifest conversion. The app's private key and client secret
 * aren't kept: the Worker only receives webhooks. */
export function appFromConversion(json: unknown, now: string): GithubApp | null {
  const o = obj(json);
  const id = o?.id;
  const slug = str(o?.slug);
  const url = str(o?.html_url);
  const webhookSecret = str(o?.webhook_secret);
  if (typeof id !== "number" || !slug || !url || !webhookSecret || !/^https:\/\/github\.com\//.test(url)) return null;
  return { id, slug, url, webhookSecret, createdAt: now };
}

/** A short record of a webhook for the debug log (GET /log); the payloads themselves are large. */
export function logSummary(event: string, payload: Json): Json {
  const subject = obj(payload[event]);
  return {
    action: str(payload.action),
    repo: str(obj(payload.repository)?.full_name),
    prs: prUrlsOf(event, payload),
    head: headOf(event, payload) ?? str(obj(obj(payload.pull_request)?.head)?.sha),
    name: str(subject?.name),
    app: str(obj(subject?.app)?.slug),
    status: str(subject?.status),
    conclusion: str(subject?.conclusion),
    attempt: typeof subject?.run_attempt === "number" ? subject.run_attempt : null,
  };
}

// Workflow runs a session started itself (actions_run_trigger), such as a release. They belong to
// no pull request, so the session's own tool call ties them to it: the PreToolUse hook sends the
// repository and the workflow (or the run, for a re-run), and the run GitHub then creates for
// that workflow is the session's.

export const DISPATCH_TOOL = "mcp__github__actions_run_trigger";

/** A workflow a session asked GitHub to run: when, in which repository, and which workflow file
 * or id ("release.yml") or, for a re-run, which run ("run:123"). */
export interface Dispatch {
  key: string;
  /** "owner/name" from the tool call; null from hooks that don't send it. */
  repo: string | null;
  /** The session's repository folder, the fallback when [repo] is missing. */
  folder: string | null;
  target: string | null;
  at: string;
}

/** The dispatch in a PreToolUse tool detail ("wimvdon/clauder release.yml"). */
export function dispatchFrom(key: string, detail: string | undefined, folder: string | null, at: string): Dispatch {
  const m = /^([\w.-]+\/[\w.-]+)\s+(\S+)$/.exec(detail?.trim() ?? "");
  return { key, repo: m ? m[1] : null, folder, target: m ? m[2] : null, at };
}

/** A workflow run as the session shows it. */
export interface WorkflowRun {
  name: string;
  url: string;
  state: CiState;
}

/** What the Worker reads of a workflow_run event. */
export interface RunEvent {
  id: number;
  run: WorkflowRun;
  repo: string;
  path: string;
  workflowId: number | null;
  event: string | null;
  /** When the run (or its latest attempt) started. */
  startedAt: string;
}

export function runOf(payload: Json): RunEvent | null {
  const run = obj(payload.workflow_run);
  const repo = str(obj(payload.repository)?.full_name);
  const url = str(run?.html_url);
  if (!run || typeof run.id !== "number" || !repo || !url || !url.startsWith("https://github.com/")) return null;
  const startedAt = str(run.run_started_at) ?? str(run.created_at);
  if (!startedAt) return null;
  return {
    id: run.id,
    run: { name: (str(run.name) ?? "Workflow").slice(0, 60), url, state: groupCi(run.status, run.conclusion) },
    repo,
    path: str(run.path) ?? "",
    workflowId: typeof run.workflow_id === "number" ? run.workflow_id : null,
    event: str(run.event),
    startedAt,
  };
}

/** Whether [run] is the one [dispatch] asked for: same repository and workflow (or run), started
 * between 30 seconds before and 5 minutes after the tool call. */
export function matchesDispatch(dispatch: Dispatch, run: RunEvent): boolean {
  const name = run.repo.split("/")[1] ?? "";
  const sameRepo = dispatch.repo ? dispatch.repo.toLowerCase() === run.repo.toLowerCase() : dispatch.folder === name;
  if (!sameRepo) return false;
  const target = dispatch.target;
  if (target?.startsWith("run:")) {
    if (Number(target.slice(4)) !== run.id) return false;
  } else {
    if (run.event !== "workflow_dispatch") return false;
    const file = run.path.split("/").pop() ?? "";
    if (target && target !== file && target !== String(run.workflowId)) return false;
  }
  const delta = Date.parse(run.startedAt) - Date.parse(dispatch.at);
  return delta >= -30_000 && delta <= 5 * 60_000;
}

/** The dispatch [run] belongs to, the latest that matches, or null. */
export function claimDispatch(dispatches: Dispatch[], run: RunEvent): Dispatch | null {
  return dispatches.filter((d) => matchesDispatch(d, run)).sort((a, b) => Date.parse(b.at) - Date.parse(a.at))[0] ?? null;
}
