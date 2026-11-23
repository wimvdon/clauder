// Clauder Worker: receives Claude Code hook events, keeps each session's task list in a Durable
// Object and pushes every change to the registered phones through FCM.
//
// Every route needs `Authorization: Bearer <HOOK_TOKEN>`.
//   POST   /hook             hook input JSON (header X-Claude-Remote-Session: cse_…)
//   POST   /devices          {"token": "<FCM token>", "name": "Pixel"} registers a phone
//   DELETE /devices          {"token": "<FCM token>"} unregisters it
//   GET    /sessions         task lists of sessions changed in the last day, newest first
//   POST   /sessions/resolve {"key": "cse_…"} the person marked the thread resolved on the phone
//   POST   /test             sends a demo task list to every phone
//   GET    /ai               today's Workers AI use in neurons (free allocation: 10,000 a day)
//   GET    /status           when the last hook event arrived and whether its hooks are current
//   GET    /log, DELETE /log raw hook payloads, for debugging (last 500)
//   GET    /github           whether GitHub is connected (see github.ts)
//   POST   /github/connect   a one-time link that creates the GitHub App and connects it
//
// GitHub's own calls carry no token; they are checked another way:
//   POST   /github           webhooks, signed with the app's webhook secret
//   GET    /github/start     the one-time link: hands the app manifest to GitHub
//   GET    /github/callback  GitHub sends the new app here; the link's state must match

import { DurableObject } from "cloudflare:workers";
import { isAuthorized, parseLimit, sessionUrl } from "./auth";
import { FcmClient, parseServiceAccount } from "./fcm";
import { HOOKS_LEVEL, HOOKS_VERSION, installScript } from "./install";
import { loadedIsCurrent, statusFrom } from "./hooks";
import { readRequest, readTurn, type AskModel } from "./ask";
import { allows, current, spend, utcDay, type Budget, type Priority } from "./budget";
import { applyAsk, applyHook, applyPrUpdate, applyRequest, emptyList, needsStateRead, resolveByUser, sessionKey, toPushData, withoutAsk, type SessionList } from "./tasklist";
import {
  appFromConversion, applyEvent, changeOf, emptyTrack, headOf, logSummary, messagePage, prUrlsOf, startPage, verifySignature,
  type GithubApp, type PrChange, type PrTrack,
} from "./github";

export interface Env {
  HOOK_TOKEN: string;
  FCM_SERVICE_ACCOUNT?: string;
  SPIKE_LOG: DurableObjectNamespace<SpikeLog>;
  SESSION: DurableObjectNamespace<Session>;
  HUB: DurableObjectNamespace<Hub>;
  /** Workers AI: reads whether a turn's last reply asks the user something (see ask.ts). */
  AI?: Ai;
}

const MAX_LOG_ENTRIES = 500;
const MAX_BODY_BYTES = 256 * 1024;
const PUSH_DEBOUNCE_MS = 400;
/** At a turn's end the push waits this long for the model's summary, so the phone gets one update. */
const ASK_WAIT_MS = 5000;
const SESSION_TTL_MS = 24 * 60 * 60 * 1000;
/** GitHub's check and workflow payloads run to tens of KB. */
const MAX_GITHUB_BYTES = 2 * 1024 * 1024;
/** A PR's webhook state is kept this long after its last event. */
const PR_TTL_MS = 14 * 24 * 60 * 60 * 1000;
/** How long the one-time GitHub link works. */
const CONNECT_TTL_MS = 30 * 60 * 1000;

export interface LogEntry {
  receivedAt: string;
  /** The installer version of the hook script (X-Clauder-Hooks) and of the hook settings the
   * session loaded (X-Clauder-Hooks-Loaded); null when the hooks don't send it (older ones). */
  hooks?: string | null;
  hooksLoaded?: string | null;
  remoteSessionId: string | null;
  sessionUrl: string | null;
  event: string | null;
  body: unknown;
}

/** Raw payload log, kept for debugging the hooks. */
export class SpikeLog extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.storage.sql.exec(
      "CREATE TABLE IF NOT EXISTS log (id INTEGER PRIMARY KEY AUTOINCREMENT, entry TEXT NOT NULL)",
    );
  }

  append(entry: LogEntry): void {
    const sql = this.ctx.storage.sql;
    sql.exec("INSERT INTO log (entry) VALUES (?)", JSON.stringify(entry));
    sql.exec("DELETE FROM log WHERE id <= (SELECT MAX(id) FROM log) - ?", MAX_LOG_ENTRIES);
  }

  recent(limit: number): LogEntry[] {
    return this.ctx.storage.sql
      .exec<{ entry: string }>("SELECT entry FROM log ORDER BY id DESC LIMIT ?", limit)
      .toArray()
      .map((row) => JSON.parse(row.entry) as LogEntry);
  }

  clear(): void {
    this.ctx.storage.sql.exec("DELETE FROM log");
  }
}

/** One instance per Claude session: its task list, and a short debounce before each push. */
export class Session extends DurableObject<Env> {
  async apply(key: string, url: string | null, body: unknown, hooksCurrent?: boolean): Promise<SessionList | null> {
    const now = new Date().toISOString();
    const stored = (await this.ctx.storage.get<SessionList>("list")) ?? emptyList(key, url, now);
    const base = url && !stored.url ? { ...stored, url } : stored;
    let next = applyHook(base, body, now, hooksCurrent);
    if (!next) {
      if (base !== stored) await this.ctx.storage.put("list", base);
      return null;
    }
    // A PR the session starts to follow may already have CI results from GitHub's webhooks.
    if (next.pr && next.pr.url !== base.pr?.url) {
      const known = await hub(this.env).prChange(next.pr.url);
      if (known) next = applyPrUpdate(next, known, now) ?? next;
    }
    await this.ctx.storage.put("list", next);
    // A change only to what the phone doesn't see (the reply, a pending check) isn't pushed.
    // The model reads each turn's reply once, when the turn ends.
    const ended = (body as { hook_event_name?: unknown } | null)?.hook_event_name === "Stop";
    const asking = ended && next.askCheck !== undefined && this.env.AI !== undefined;
    if (next.seq !== base.seq) await this.schedulePush(asking ? ASK_WAIT_MS : PUSH_DEBOUNCE_MS);
    if (asking) this.ctx.waitUntil(this.checkAsk(next.askCheck!));
    // The person's new message: a small model writes the "Working on" line.
    if (next.askRequest !== undefined && next.askRequest !== base.askRequest && this.env.AI !== undefined) {
      this.ctx.waitUntil(this.checkRequest(next.askRequest));
    }
    return next;
  }

  /** A GitHub webhook changed the PR this session follows. */
  async applyPr(change: PrChange): Promise<boolean> {
    const list = await this.ctx.storage.get<SessionList>("list");
    if (!list) return false;
    const next = applyPrUpdate(list, change, new Date().toISOString());
    if (!next) return false;
    await this.ctx.storage.put("list", next);
    await this.schedulePush(PUSH_DEBOUNCE_MS);
    return true;
  }

  /** Marks the thread resolved for the person (see [resolveByUser]). False when the session is
   * unknown or already resolved. */
  async resolve(): Promise<boolean> {
    const list = await this.ctx.storage.get<SessionList>("list");
    if (!list) return false;
    const next = resolveByUser(list, new Date().toISOString());
    if (!next) return false;
    await this.ctx.storage.put("list", next);
    await this.schedulePush(PUSH_DEBOUNCE_MS);
    return true;
  }

  /** Has the model read the turn's last reply: its summary line, and whether it asks the user.
   * Only the summary when the read can't change the state, and nothing when the day's free
   * Workers AI allocation is (nearly) used up. */
  private async checkAsk(reply: string): Promise<void> {
    if (!this.env.AI) return;
    const before = await this.ctx.storage.get<SessionList>("list");
    if (!before) return;
    const mode = needsStateRead(before) ? "full" : "summary";
    const priority: Priority = mode === "full" ? "state" : "extra";
    const budget = hub(this.env);
    let read = null;
    let pausedOn: string | undefined;
    if (await budget.aiAllows(priority)) {
      const result = await readTurn(this.env.AI as unknown as AskModel, reply, mode);
      await budget.aiSpent(result.neurons, result.allocationUsed);
      read = result.read;
      if (result.allocationUsed) pausedOn = utcDay(new Date());
    } else {
      pausedOn = utcDay(new Date());
    }
    const list = await this.ctx.storage.get<SessionList>("list");
    if (!list) return;
    const next = applyAsk(list, reply, read, new Date().toISOString(), pausedOn);
    if (!next) return;
    await this.ctx.storage.put("list", next);
    // Push now: the update held back for this read, if any, goes out with it.
    if (next.seq !== list.seq || (await this.ctx.storage.get<boolean>("pushPending"))) await this.schedulePush(PUSH_DEBOUNCE_MS);
  }

  /** Has a small model read the person's message: what Claude now works on, and a title for a
   * session outside a project. Skipped when the day's free Workers AI allowance runs low. */
  private async checkRequest(text: string): Promise<void> {
    if (!this.env.AI) return;
    const budget = hub(this.env);
    let read = null;
    if (await budget.aiAllows("extra")) {
      const result = await readRequest(this.env.AI as unknown as AskModel, text);
      await budget.aiSpent(result.neurons, result.allocationUsed);
      read = result.read;
    }
    const list = await this.ctx.storage.get<SessionList>("list");
    if (!list) return;
    const next = applyRequest(list, text, read, new Date().toISOString());
    if (!next) return;
    await this.ctx.storage.put("list", next);
    if (next.seq !== list.seq) await this.schedulePush(PUSH_DEBOUNCE_MS);
  }

  /** Pushes after [delay], or sooner if a push is already due. */
  private async schedulePush(delay: number): Promise<void> {
    await this.ctx.storage.put("pushPending", true);
    const alarm = await this.ctx.storage.getAlarm();
    if (alarm === null || alarm > Date.now() + delay) {
      await this.ctx.storage.setAlarm(Date.now() + delay);
    }
  }

  async alarm(): Promise<void> {
    const list = await this.ctx.storage.get<SessionList>("list");
    if (!list) return;
    if (await this.ctx.storage.get<boolean>("pushPending")) {
      await this.ctx.storage.put("pushPending", false);
      // The reply text stays here; the phone and /sessions get only what they show.
      const { reply: _reply, askCheck: _askCheck, staleFinish: _staleFinish, askRequest: _askRequest, agents: _agents, ...shown } = list;
      try {
        await hub(this.env).publish(shown);
      } catch (e) {
        // Cloudflare runs the alarm again; the push must still be due then, or the retry would
        // take the session for a quiet one and forget it.
        await this.ctx.storage.put("pushPending", true);
        throw e;
      }
      await this.ctx.storage.setAlarm(Date.now() + SESSION_TTL_MS);
      return;
    }
    // A day without changes: forget the session.
    await this.ctx.storage.deleteAll();
  }
}

interface DeviceRow extends Record<string, SqlStorageValue> {
  token: string;
  name: string;
}

/** Singleton: registered phones and the latest task list of every recent session. */
export class Hub extends DurableObject<Env> {
  private fcm: FcmClient | null = null;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.storage.sql.exec(
      `CREATE TABLE IF NOT EXISTS devices (token TEXT PRIMARY KEY, name TEXT NOT NULL, added_at TEXT NOT NULL);
       CREATE TABLE IF NOT EXISTS sessions (key TEXT PRIMARY KEY, list TEXT NOT NULL, updated_at INTEGER NOT NULL);
       CREATE TABLE IF NOT EXISTS github_prs (url TEXT PRIMARY KEY, track TEXT NOT NULL, updated_at INTEGER NOT NULL);`,
    );
  }

  private client(): FcmClient | null {
    if (this.fcm) return this.fcm;
    const sa = parseServiceAccount(this.env.FCM_SERVICE_ACCOUNT);
    this.fcm = sa ? new FcmClient(sa) : null;
    return this.fcm;
  }

  register(token: string, name: string): number {
    this.ctx.storage.sql.exec(
      "INSERT INTO devices (token, name, added_at) VALUES (?, ?, ?) ON CONFLICT(token) DO UPDATE SET name = excluded.name",
      token, name, new Date().toISOString(),
    );
    return this.devices().length;
  }

  unregister(token: string): void {
    this.ctx.storage.sql.exec("DELETE FROM devices WHERE token = ?", token);
  }

  devices(): DeviceRow[] {
    return this.ctx.storage.sql.exec<DeviceRow>("SELECT token, name FROM devices").toArray();
  }

  sessions(): SessionList[] {
    const since = Date.now() - SESSION_TTL_MS;
    this.ctx.storage.sql.exec("DELETE FROM sessions WHERE updated_at < ?", since);
    return this.ctx.storage.sql
      .exec<{ list: string }>("SELECT list FROM sessions ORDER BY updated_at DESC LIMIT 20")
      .toArray()
      .map((row) => JSON.parse(row.list) as SessionList);
  }

  // GitHub (see github.ts). The app and the one-time link states live in key-value storage.

  async githubApp(): Promise<GithubApp | null> {
    return (await this.ctx.storage.get<GithubApp>("githubApp")) ?? null;
  }

  async lastGithubEvent(): Promise<string | null> {
    return (await this.ctx.storage.get<string>("githubEventAt")) ?? null;
  }

  /** A new one-time state for the link that creates the GitHub App. */
  async newConnectState(): Promise<string> {
    const bytes = crypto.getRandomValues(new Uint8Array(24));
    const state = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
    const states = await this.liveStates();
    states[state] = Date.now() + CONNECT_TTL_MS;
    await this.ctx.storage.put("githubStates", states);
    return state;
  }

  /** True when [state] is a live link state. [consume] uses it up, after GitHub sent the app. */
  async checkConnectState(state: string, consume: boolean): Promise<boolean> {
    const states = await this.liveStates();
    if (!(state in states)) return false;
    if (consume) {
      delete states[state];
      await this.ctx.storage.put("githubStates", states);
    }
    return true;
  }

  private async liveStates(): Promise<Record<string, number>> {
    const states = (await this.ctx.storage.get<Record<string, number>>("githubStates")) ?? {};
    const now = Date.now();
    return Object.fromEntries(Object.entries(states).filter(([, until]) => until > now));
  }

  async setGithubApp(app: GithubApp): Promise<void> {
    await this.ctx.storage.put("githubApp", app);
  }

  /** What the Worker knows of a PR from GitHub, or null. */
  prChange(url: string): PrChange | null {
    const row = this.ctx.storage.sql.exec<{ track: string }>("SELECT track FROM github_prs WHERE url = ?", url).toArray()[0];
    return row ? changeOf(JSON.parse(row.track) as PrTrack) : null;
  }

  /** Applies a webhook to the PRs it is about and to the sessions that follow them. Returns how
   * many sessions changed. */
  async githubEvent(event: string, payload: Record<string, unknown>): Promise<number> {
    const sql = this.ctx.storage.sql;
    const now = Date.now();
    await this.ctx.storage.put("githubEventAt", new Date(now).toISOString());
    sql.exec("DELETE FROM github_prs WHERE updated_at < ?", now - PR_TTL_MS);
    // The stored tracks; a PR seen for the first time starts empty (and isn't stored yet).
    const tracks = new Map<string, { track: PrTrack; stored: boolean }>();
    for (const url of prUrlsOf(event, payload)) {
      const row = sql.exec<{ track: string }>("SELECT track FROM github_prs WHERE url = ?", url).toArray()[0];
      tracks.set(url, row ? { track: JSON.parse(row.track) as PrTrack, stored: true } : { track: emptyTrack(url), stored: false });
    }
    // A check event that names no PR (GitHub leaves it out at times) counts for the PRs whose head
    // is that commit.
    const head = headOf(event, payload);
    const repo = (payload.repository as { full_name?: unknown } | undefined)?.full_name;
    if (tracks.size === 0 && head && typeof repo === "string") {
      for (const row of sql.exec<{ url: string; track: string }>("SELECT url, track FROM github_prs").toArray()) {
        const track = JSON.parse(row.track) as PrTrack;
        if (track.head === head && row.url.startsWith(`https://github.com/${repo}/pull/`)) tracks.set(row.url, { track, stored: true });
      }
    }
    let changed = 0;
    for (const [url, { track, stored }] of tracks) {
      const next = applyEvent(track, event, payload);
      if (next === track && stored) continue;
      sql.exec(
        "INSERT INTO github_prs (url, track, updated_at) VALUES (?, ?, ?) ON CONFLICT(url) DO UPDATE SET track = excluded.track, updated_at = excluded.updated_at",
        url, JSON.stringify(next), now,
      );
      const change = changeOf(next);
      for (const list of this.sessions()) {
        if (list.pr?.url !== url) continue;
        if (await this.env.SESSION.get(this.env.SESSION.idFromName(list.key)).applyPr(change)) changed++;
      }
    }
    return changed;
  }

  /** Today's Workers AI use, counted in neurons (see budget.ts). */
  private async budget(): Promise<Budget> {
    return current(await this.ctx.storage.get<Budget>("aiBudget"), utcDay(new Date()));
  }

  /** Whether a Workers AI call of [priority] still fits in today's free allocation. */
  async aiAllows(priority: Priority): Promise<boolean> {
    return allows(await this.budget(), priority);
  }

  /** Counts a call's neurons; [allocationUsed] when Workers AI refused it for the day's allocation. */
  async aiSpent(neurons: number, allocationUsed: boolean): Promise<void> {
    await this.ctx.storage.put("aiBudget", spend(await this.budget(), neurons, allocationUsed));
  }

  /** Today's Workers AI use, for GET /ai. */
  async aiUsage(): Promise<Budget> {
    return this.budget();
  }

  /** Stores the list and pushes it to every phone. Returns how many phones got it. */
  async publish(
    list: SessionList,
    store = true,
  ): Promise<{ sent: number; devices: number; fcmConfigured: boolean; error?: string }> {
    if (store) this.ctx.storage.sql.exec(
      "INSERT INTO sessions (key, list, updated_at) VALUES (?, ?, ?) ON CONFLICT(key) DO UPDATE SET list = excluded.list, updated_at = excluded.updated_at",
      list.key, JSON.stringify(list), Date.now(),
    );
    const client = this.client();
    const devices = this.devices();
    if (!client) return { sent: 0, devices: devices.length, fcmConfigured: false };
    const data = toPushData(list);
    let sent = 0;
    let error: string | undefined;
    for (const device of devices) {
      try {
        const result = await client.send(device.token, data);
        if (result === "sent") sent++;
        if (result === "unregistered") this.unregister(device.token);
        if (result === "failed") error ??= "FCM refused the message (see the Worker log)";
      } catch (e) {
        // Usually the service account key: report it instead of failing the whole request.
        error = e instanceof Error ? e.message : String(e);
        console.log(`FCM push failed: ${error}`);
        break;
      }
    }
    return { sent, devices: devices.length, fcmConfigured: true, ...(error ? { error } : {}) };
  }
}

function hub(env: Env): DurableObjectStub<Hub> {
  return env.HUB.get(env.HUB.idFromName("hub"));
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: { "content-type": "application/json" },
  });
}

async function readJson(request: Request): Promise<Record<string, unknown> | null> {
  const text = await request.text();
  if (text.length > MAX_BODY_BYTES) return null;
  try {
    const value: unknown = JSON.parse(text);
    return typeof value === "object" && value !== null ? (value as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

function validToken(value: unknown): value is string {
  return typeof value === "string" && value.length > 20 && value.length < 4096;
}

export function demoList(now: string): SessionList {
  return {
    key: "demo",
    title: "Test notification",
    url: null,
    state: "running",
    seq: Date.now(),
    updatedAt: now,
    tasks: [
      { id: "1", subject: "Read the hook payloads", status: "completed" },
      { id: "2", subject: "Push the task list to the phone", status: "in_progress" },
      { id: "3", subject: "Show it as a Live Update", status: "pending" },
    ],
  };
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    // A cloud environment's setup script runs: curl -fsS https://<worker>/install.sh | bash
    if (request.method === "GET" && url.pathname === "/install.sh") {
      return new Response(installScript(url.host), {
        headers: { "content-type": "text/x-shellscript; charset=utf-8", "cache-control": "no-store" },
      });
    }
    if (url.pathname === "/github" && request.method === "POST") return githubWebhook(request, env, ctx);
    if (url.pathname === "/github/start" && request.method === "GET") return githubStart(url, env);
    if (url.pathname === "/github/callback" && request.method === "GET") return githubCallback(url, env);
    if (!isAuthorized(request.headers.get("authorization"), env.HOOK_TOKEN)) {
      return json({ error: "unauthorized" }, 401);
    }
    const route = `${request.method} ${url.pathname}`;

    if (route === "POST /hook") {
      const text = await request.text();
      if (text.length > MAX_BODY_BYTES) return json({ error: "too large" }, 413);
      let body: unknown = text;
      try {
        body = JSON.parse(text);
      } catch {
        // Kept as raw text in the log.
      }
      const remoteSessionId = request.headers.get("x-claude-remote-session");
      const entry: LogEntry = {
        receivedAt: new Date().toISOString(),
        hooks: request.headers.get("x-clauder-hooks"),
        hooksLoaded: request.headers.get("x-clauder-hooks-loaded"),
        remoteSessionId,
        sessionUrl: sessionUrl(remoteSessionId),
        event:
          typeof body === "object" && body !== null && "hook_event_name" in body
            ? String((body as { hook_event_name: unknown }).hook_event_name)
            : null,
        body: withoutAsk(body),
      };
      const log = env.SPIKE_LOG.get(env.SPIKE_LOG.idFromName("spike"));
      ctx.waitUntil(log.append(entry));
      const key = sessionKey(remoteSessionId, body);
      // Which hook settings the session loaded at its start; older settings don't say.
      const loaded = request.headers.get("x-clauder-hooks-loaded");
      if (key) {
        const current = loadedIsCurrent(loaded);
        await env.SESSION.get(env.SESSION.idFromName(key)).apply(key, entry.sessionUrl, body, current);
      }
      // An empty JSON object tells Claude Code to carry on as normal. The prompt hook sends its
      // installer version; an old one is told to install the hooks again.
      const hooks = request.headers.get("x-clauder-hooks");
      const response = json({});
      if (hooks !== null && hooks !== HOOKS_VERSION) response.headers.set("x-clauder-update", "1");
      return response;
    }

    if (route === "POST /devices") {
      const body = await readJson(request);
      if (!body || !validToken(body.token)) return json({ error: "token missing" }, 400);
      const name = typeof body.name === "string" ? body.name.slice(0, 80) : "Phone";
      const devices = await hub(env).register(body.token, name);
      return json({ registered: true, devices, fcmConfigured: parseServiceAccount(env.FCM_SERVICE_ACCOUNT) !== null });
    }

    if (route === "DELETE /devices") {
      const body = await readJson(request);
      if (!body || !validToken(body.token)) return json({ error: "token missing" }, 400);
      await hub(env).unregister(body.token);
      return json({ unregistered: true });
    }

    if (route === "GET /sessions") {
      return json(await hub(env).sessions());
    }

    if (route === "POST /sessions/resolve") {
      const body = await readJson(request);
      const key = body?.key;
      if (typeof key !== "string" || sessionKey(null, { session_id: key }) !== key) return json({ error: "key missing" }, 400);
      return json({ resolved: await env.SESSION.get(env.SESSION.idFromName(key)).resolve() });
    }

    if (route === "GET /github") {
      const app = await hub(env).githubApp();
      return json({
        connected: app !== null,
        app: app?.url ?? null,
        installUrl: app ? `${app.url}/installations/new` : null,
        lastEventAt: await hub(env).lastGithubEvent(),
      });
    }

    if (route === "POST /github/connect") {
      const state = await hub(env).newConnectState();
      return json({ url: `https://${url.host}/github/start?state=${state}` });
    }

    if (route === "POST /test") {
      const result = await hub(env).publish(demoList(new Date().toISOString()), false);
      return json(result, result.error && result.sent === 0 ? 502 : 200);
    }

    if (route === "GET /ai") {
      return json(await hub(env).aiUsage());
    }

    if (route === "GET /status") {
      const log = env.SPIKE_LOG.get(env.SPIKE_LOG.idFromName("spike"));
      // The RPC stub can't type `body: unknown`, so the entries come back untyped.
      const entries = (await log.recent(1)) as unknown as LogEntry[];
      return json(statusFrom(entries[0] ?? null, HOOKS_VERSION, HOOKS_LEVEL));
    }

    if (route === "GET /log") {
      const log = env.SPIKE_LOG.get(env.SPIKE_LOG.idFromName("spike"));
      return json(await log.recent(parseLimit(url.searchParams.get("limit"), MAX_LOG_ENTRIES)));
    }

    if (route === "DELETE /log") {
      const log = env.SPIKE_LOG.get(env.SPIKE_LOG.idFromName("spike"));
      await log.clear();
      return json({ cleared: true });
    }

    return json({ error: "not found" }, 404);
  },
} satisfies ExportedHandler<Env>;

function html(body: string, status = 200): Response {
  return new Response(body, {
    status,
    headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "referrer-policy": "no-referrer" },
  });
}

const EXPIRED = messagePage(
  "This link has expired",
  "Open Clauder, go to Settings and tap Connect GitHub again for a new link.",
);

async function githubStart(url: URL, env: Env): Promise<Response> {
  const state = url.searchParams.get("state") ?? "";
  if (!(await hub(env).checkConnectState(state, false))) return html(EXPIRED, 403);
  return html(startPage(url.host, state));
}

/** GitHub created the app: trade the one-time code for its webhook secret, then send the person on
 * to install it on their repositories. */
async function githubCallback(url: URL, env: Env): Promise<Response> {
  const state = url.searchParams.get("state") ?? "";
  const code = url.searchParams.get("code") ?? "";
  if (!/^[\w-]{1,100}$/.test(code) || !(await hub(env).checkConnectState(state, true))) return html(EXPIRED, 403);
  const response = await fetch(`https://api.github.com/app-manifests/${code}/conversions`, {
    method: "POST",
    headers: { accept: "application/vnd.github+json", "user-agent": "clauder-worker", "x-github-api-version": "2022-11-28" },
  });
  const app = response.ok ? appFromConversion(await response.json(), new Date().toISOString()) : null;
  if (!app) {
    console.log(`GitHub app conversion failed: HTTP ${response.status}`);
    return html(messagePage("GitHub didn't finish", "Clauder couldn't read the new GitHub App. Tap Connect GitHub in Clauder to try again."), 502);
  }
  await hub(env).setGithubApp(app);
  return Response.redirect(`${app.url}/installations/new`, 302);
}

async function githubWebhook(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const text = await request.text();
  if (text.length > MAX_GITHUB_BYTES) return json({ error: "too large" }, 413);
  const app = await hub(env).githubApp();
  if (!app || !(await verifySignature(app.webhookSecret, text, request.headers.get("x-hub-signature-256")))) {
    return json({ error: "bad signature" }, 401);
  }
  const event = request.headers.get("x-github-event") ?? "";
  let payload: Record<string, unknown>;
  try {
    payload = JSON.parse(text) as Record<string, unknown>;
  } catch {
    return json({ error: "not json" }, 400);
  }
  const log = env.SPIKE_LOG.get(env.SPIKE_LOG.idFromName("spike"));
  ctx.waitUntil(log.append({
    receivedAt: new Date().toISOString(), remoteSessionId: null, sessionUrl: null,
    event: `github:${event}`, body: logSummary(event, payload),
  }));
  if (event === "ping") return json({ ok: true });
  const sessions = await hub(env).githubEvent(event, payload);
  return json({ ok: true, sessions });
}
