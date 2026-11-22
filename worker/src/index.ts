// Clauder Worker: receives Claude Code hook events, keeps each session's task list in a Durable
// Object and pushes every change to the registered phones through FCM.
//
// Every route needs `Authorization: Bearer <HOOK_TOKEN>`.
//   POST   /hook             hook input JSON (header X-Claude-Remote-Session: cse_…)
//   POST   /devices          {"token": "<FCM token>", "name": "Pixel"} registers a phone
//   DELETE /devices          {"token": "<FCM token>"} unregisters it
//   GET    /sessions         task lists of sessions changed in the last day, newest first
//   POST   /test             sends a demo task list to every phone
//   GET    /log, DELETE /log raw hook payloads, for debugging (last 500)

import { DurableObject } from "cloudflare:workers";
import { isAuthorized, parseLimit, sessionUrl } from "./auth";
import { FcmClient, parseServiceAccount } from "./fcm";
import { HOOKS_VERSION, installScript } from "./install";
import { findAsk, type AskModel } from "./ask";
import { applyAsk, applyHook, emptyList, sessionKey, toPushData, type SessionList } from "./tasklist";

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
const SESSION_TTL_MS = 24 * 60 * 60 * 1000;

export interface LogEntry {
  receivedAt: string;
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
    const next = applyHook(base, body, now, hooksCurrent);
    if (!next) {
      if (base !== stored) await this.ctx.storage.put("list", base);
      return null;
    }
    await this.ctx.storage.put("list", next);
    // A change only to what the phone doesn't see (the reply, a pending check) isn't pushed.
    if (next.seq !== base.seq) await this.schedulePush();
    if (next.askCheck !== undefined && this.env.AI) this.ctx.waitUntil(this.checkAsk(next.seq, next.askCheck));
    return next;
  }

  /** Has the model read the turn's last reply, then marks the thread waiting if it asks the user. */
  private async checkAsk(seq: number, reply: string): Promise<void> {
    if (!this.env.AI) return;
    const question = await findAsk(this.env.AI as unknown as AskModel, reply);
    const list = await this.ctx.storage.get<SessionList>("list");
    if (!list) return;
    const next = applyAsk(list, seq, question, new Date().toISOString());
    if (!next) return;
    await this.ctx.storage.put("list", next);
    if (next.seq !== list.seq) await this.schedulePush();
  }

  private async schedulePush(): Promise<void> {
    await this.ctx.storage.put("pushPending", true);
    const alarm = await this.ctx.storage.getAlarm();
    if (alarm === null || alarm > Date.now() + PUSH_DEBOUNCE_MS) {
      await this.ctx.storage.setAlarm(Date.now() + PUSH_DEBOUNCE_MS);
    }
  }

  async alarm(): Promise<void> {
    const list = await this.ctx.storage.get<SessionList>("list");
    if (!list) return;
    if (await this.ctx.storage.get<boolean>("pushPending")) {
      await this.ctx.storage.put("pushPending", false);
      // The reply text stays here; the phone and /sessions get only what they show.
      const { reply: _reply, askCheck: _askCheck, ...shown } = list;
      await hub(this.env).publish(shown);
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
       CREATE TABLE IF NOT EXISTS sessions (key TEXT PRIMARY KEY, list TEXT NOT NULL, updated_at INTEGER NOT NULL);`,
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
        remoteSessionId,
        sessionUrl: sessionUrl(remoteSessionId),
        event:
          typeof body === "object" && body !== null && "hook_event_name" in body
            ? String((body as { hook_event_name: unknown }).hook_event_name)
            : null,
        body,
      };
      const log = env.SPIKE_LOG.get(env.SPIKE_LOG.idFromName("spike"));
      ctx.waitUntil(log.append(entry));
      const key = sessionKey(remoteSessionId, body);
      // Which hook settings the session loaded at its start; older settings don't say.
      const loaded = request.headers.get("x-clauder-hooks-loaded");
      if (key) {
        const current = loaded === null ? undefined : loaded === HOOKS_VERSION;
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

    if (route === "POST /test") {
      const result = await hub(env).publish(demoList(new Date().toISOString()), false);
      return json(result, result.error && result.sent === 0 ? 502 : 200);
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
