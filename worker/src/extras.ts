// What a session is waiting on and what it produced, read from hook payloads: its pull request
// and CI, the link its last reply attached, a reminder it set, and what it cost. Plain logic, so
// it runs under plain Node tests.

export type PrState = "open" | "merged" | "closed";
export type CiState = "running" | "passed" | "failed";

export interface PullRequest {
  number: number;
  url: string;
  state: PrState;
  ci?: CiState;
  /** The latest review that approved the PR or asked for changes (from GitHub webhooks). */
  review?: "approved" | "changes_requested";
  /** True while the session is subscribed to the PR's activity, so CI and review events wake it. */
  subscribed?: boolean;
}

export interface Link {
  url: string;
  title?: string;
}

export interface Usage {
  /** Every token the session's model calls read or wrote, cached or not. */
  tokens: number;
  /** What the same tokens would cost on the Claude API, in US dollars. An estimate: a
   * subscription doesn't bill per token. */
  cost: number;
}

type Json = Record<string, unknown>;

function obj(value: unknown): Json | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Json) : null;
}

/** An MCP tool's answer as text: a string, or content blocks with text. */
export function responseText(response: unknown): string {
  if (typeof response === "string") return response;
  if (Array.isArray(response)) {
    return response.map((block) => (typeof obj(block)?.text === "string" ? (obj(block)!.text as string) : "")).join("\n");
  }
  const single = obj(response);
  if (single && typeof single.text === "string") return single.text;
  if (single && Array.isArray(single.content)) return responseText(single.content);
  return single ? JSON.stringify(single) : "";
}

/** The answer parsed as JSON, or null. */
export function responseJson(response: unknown): Json | null {
  const direct = obj(response);
  if (direct && !("text" in direct) && !("content" in direct)) return direct;
  try {
    return obj(JSON.parse(responseText(response)));
  } catch {
    return null;
  }
}

const PR_URL = /^https:\/\/github\.com\/([\w.-]+)\/([\w.-]+)\/pull\/(\d+)\b/;

export function prFromUrl(url: unknown): PullRequest | null {
  if (typeof url !== "string") return null;
  const m = PR_URL.exec(url);
  if (!m) return null;
  return { number: Number(m[3]), url: `https://github.com/${m[1]}/${m[2]}/pull/${m[3]}`, state: "open" };
}

function prFromParts(owner: unknown, repo: unknown, number: unknown): PullRequest | null {
  const n = typeof number === "number" ? number : typeof number === "string" ? Number(number) : NaN;
  if (typeof owner !== "string" || typeof repo !== "string" || !Number.isInteger(n) || n <= 0) return null;
  return prFromUrl(`https://github.com/${owner}/${repo}/pull/${n}`);
}

/** The overall result of a PR's check runs. Skipped and neutral runs count as passed. */
export function ciFromCheckRuns(json: Json | null): CiState | null {
  const runs = json?.check_runs;
  if (!Array.isArray(runs) || runs.length === 0) return null;
  let running = false;
  for (const run of runs) {
    const r = obj(run);
    if (!r) continue;
    if (r.status !== "completed") running = true;
    else if (FAILED.includes(String(r.conclusion))) {
      return "failed";
    }
  }
  return running ? "running" : "passed";
}

const GITHUB = "mcp__github__";

/** The tool names that tell us about a pull request. */
export const PR_TOOLS = [
  `${GITHUB}create_pull_request`,
  `${GITHUB}merge_pull_request`,
  `${GITHUB}pull_request_read`,
  `${GITHUB}update_pull_request`,
  "mcp__claude-code-remote__subscribe_pr_activity",
  "mcp__claude-code-remote__unsubscribe_pr_activity",
  "ReadNotifications",
];

/**
 * The session's pull request after one tool call, or [current] when the call says nothing new.
 * A session follows one PR at a time: the newest one it created or subscribed to.
 */
export function applyPrTool(current: PullRequest | undefined, tool: string, input: Json | null, response: unknown): PullRequest | undefined {
  const same = (pr: PullRequest | null) => pr !== null && current !== undefined && pr.url === current.url;
  switch (tool) {
    case `${GITHUB}create_pull_request`: {
      const pr = prFromUrl(responseJson(response)?.url);
      return pr ? { ...pr, ci: "running" } : current;
    }
    case "mcp__claude-code-remote__subscribe_pr_activity": {
      const pr = prFromParts(input?.owner, input?.repo, input?.pullNumber);
      if (!pr) return current;
      if (same(pr)) return current!.subscribed ? current : { ...current!, subscribed: true };
      return { ...pr, subscribed: true };
    }
    case "mcp__claude-code-remote__unsubscribe_pr_activity": {
      const pr = prFromParts(input?.owner, input?.repo, input?.pullNumber);
      if (!same(pr) || !current!.subscribed) return current;
      const { subscribed: _subscribed, ...rest } = current!;
      return rest;
    }
    case `${GITHUB}merge_pull_request`: {
      const pr = prFromParts(input?.owner, input?.repo, input?.pullNumber);
      if (!same(pr) || responseJson(response)?.merged !== true) return current;
      return { ...current!, state: "merged" };
    }
    case `${GITHUB}update_pull_request`: {
      const pr = prFromParts(input?.owner, input?.repo, input?.pullNumber);
      if (!same(pr) || input?.state !== "closed") return current;
      return { ...current!, state: "closed" };
    }
    case `${GITHUB}pull_request_read`: {
      const pr = prFromParts(input?.owner, input?.repo, input?.pullNumber);
      if (!same(pr)) return current;
      const json = responseJson(response);
      if (input?.method === "get_check_runs") {
        const ci = ciFromCheckRuns(json);
        return ci ? { ...current!, ci } : current;
      }
      if (input?.method === "get" && json) {
        if (json.merged === true) return { ...current!, state: "merged" };
        if (json.state === "closed") return { ...current!, state: "closed" };
      }
      return current;
    }
    case "ReadNotifications":
      return current ? prFromNotifications(current, responseText(response)) : current;
    default:
      return current;
  }
}

/** GitHub events delivered to the session, read from each event's kind and data. The event
 * "check_suite.completed" is defined as "no check suite on the head is still running or failed",
 * so it means passed unless its data carries a failed conclusion. "pull_request.closed" carries
 * its outcome ("merged" or not). */
function prFromNotifications(current: PullRequest, text: string): PullRequest {
  const m = PR_URL.exec(current.url);
  if (!m) return current;
  const ref = `${m[1]}/${m[2]}#${m[3]}`;
  let next = current;
  // The guidance comment between the event tag and its data is skipped.
  const events = /kind="([\w.]+)"[^>]*>\s*(?:<!--[\s\S]*?-->\s*)?(\{[^\n]*\})/g;
  for (let e = events.exec(text); e; e = events.exec(text)) {
    let data: Json | null = null;
    try {
      data = obj(JSON.parse(e[2]));
    } catch {
      continue;
    }
    if (data?.pr !== ref) continue;
    const kind = e[1];
    if (kind === "check_suite.completed") {
      next = { ...next, ci: FAILED.includes(String(data.conclusion)) ? "failed" : "passed" };
    } else if (/^(check_suite|check_run|workflow_run)\./.test(kind) && FAILED.includes(String(data.conclusion))) {
      // The kind a failed run arrives as hasn't been seen yet; any check event that says it failed counts.
      next = { ...next, ci: "failed" };
    } else if (kind === "pull_request.merged" || (kind === "pull_request.closed" && data.outcome === "merged")) {
      next = { ...next, state: "merged" };
    } else if (kind === "pull_request.closed" && next.state !== "merged") next = { ...next, state: "closed" };
  }
  return next;
}

const FAILED = ["failure", "timed_out", "cancelled", "action_required", "startup_failure"];


/** The first link the phone can open among a reply's attached outputs: a web link, or an
 * Artifact. Files live in the project folder and can't be opened from the phone. */
export function linkFromOutputs(outputs: unknown): Link | null {
  if (!Array.isArray(outputs)) return null;
  for (const item of outputs) {
    const o = obj(item);
    const ref = typeof o?.ref === "string" ? o.ref : "";
    if ((o?.kind === "link" || o?.kind === "frame") && /^https:\/\/[^\s]+$/.test(ref)) {
      const title = typeof o.title === "string" && o.title.trim() ? o.title.trim().slice(0, 60) : undefined;
      return { url: ref, ...(title ? { title } : {}) };
    }
  }
  return null;
}

export const REMINDER_TOOL = "mcp__claude-code-remote__send_later";

/** When a reminder set with send_later fires, as an ISO time, or null. "at" may carry a zone
 * name in brackets ("2026-11-22T20:00:00[Europe/Brussels]"); without an offset that can't be
 * resolved here, so only RFC 3339 times and delays count. */
export function wakeAtFrom(input: Json | null, now: string, response?: unknown): string | null {
  // The tool answers with the time it will fire; that is exact, whatever form the input took.
  const fireAt = responseJson(response)?.fire_at;
  if (typeof fireAt === "string" && /(Z|[+-]\d\d:\d\d)$/.test(fireAt) && !Number.isNaN(Date.parse(fireAt))) {
    return new Date(Date.parse(fireAt)).toISOString();
  }
  const delay = input?.delay_minutes;
  if (typeof delay === "number" && delay >= 1) return new Date(Date.parse(now) + delay * 60_000).toISOString();
  const at = typeof input?.at === "string" ? input.at : "";
  if (!/(Z|[+-]\d\d:\d\d)$/.test(at)) return null;
  const ms = Date.parse(at);
  return Number.isNaN(ms) ? null : new Date(ms).toISOString();
}

/** Claude API prices per million tokens (input, output, cache read), from the claude-api skill's
 * model table (2026-11-03). A cache write costs 1.25x input for 5 minutes and 2x for an hour. */
const PRICES: [RegExp, number, number, number][] = [
  [/fable-5-2|mythos-5-2/, 8, 40, 0.2],
  [/fable|mythos/, 10, 50, 1],
  [/opus-5-5/, 4, 20, 0.2],
  [/opus/, 5, 25, 0.5],
  [/sonnet-5-5/, 2, 10, 0.1],
  [/sonnet-5/, 2, 10, 0.2],
  [/sonnet/, 3, 15, 0.3],
  [/haiku-5-5/, 0.1, 0.5, 0.01],
  [/haiku/, 1, 5, 0.1],
];

function num(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : 0;
}

/** Totals the per-model token counts the Stop hook script reads from the transcript. */
export function usageFrom(entries: unknown): Usage | null {
  if (!Array.isArray(entries) || entries.length === 0) return null;
  let tokens = 0;
  let cost = 0;
  for (const item of entries) {
    const e = obj(item);
    if (!e) continue;
    const input = num(e.input);
    const output = num(e.output);
    const read = num(e.cacheRead);
    const write5m = num(e.cacheWrite5m);
    const write1h = num(e.cacheWrite1h);
    tokens += input + output + read + write5m + write1h;
    const model = typeof e.model === "string" ? e.model : "";
    const price = PRICES.find(([pattern]) => pattern.test(model));
    if (!price) continue;
    const [, inPrice, outPrice, readPrice] = price;
    cost += (input * inPrice + output * outPrice + read * readPrice + write5m * inPrice * 1.25 + write1h * inPrice * 2) / 1e6;
  }
  return tokens > 0 ? { tokens, cost: Math.round(cost * 100) / 100 } : null;
}

/** A git push from the shell (the hooks send only commands that start with "git push"), or files
 * pushed with the GitHub tool: the followed PR's CI runs again. Returns [current] otherwise. */
export function afterPush(current: PullRequest | undefined, tool: string, input: Json | null): PullRequest | undefined {
  if (!current || current.state !== "open") return current;
  const command = typeof input?.command === "string" ? input.command : "";
  const pushed = tool === "Bash" ? /(^|[;&|(]\s*|\s)git\s+push\b/.test(command) : tool === "mcp__github__push_files";
  return pushed && current.ci !== "running" ? { ...current, ci: "running" } : current;
}

/** True while a PR's events will wake the session: it is subscribed, the PR is open and its CI
 * has not finished (a passed or failed run is answered within the turn that receives it). */
export function prWakes(pr: PullRequest | undefined): boolean {
  return pr?.subscribed === true && pr.state === "open" && pr.ci !== "passed" && pr.ci !== "failed";
}

export const WATCH_TOOL = "mcp__claude-code-remote__watch_url";
export const UNWATCH_TOOL = "mcp__claude-code-remote__unwatch_url";

/** The trigger id watch_url returns, or a stand-in when the result carries none. */
export function watchIdFrom(response: unknown): string {
  const id = responseJson(response)?.trigger_id;
  return typeof id === "string" && id ? id : "watch";
}
