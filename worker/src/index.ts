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
import { applyHook, emptyList, sessionKey, toPushData, type SessionList } from "./tasklist";

export interface Env {
  HOOK_TOKEN: string;
  FCM_SERVICE_ACCOUNT?: string;
  SPIKE_LOG: DurableObjectNamespace<SpikeLog>;
  SESSION: DurableObjectNamespace<Session>;
  HUB: DurableObjectNamespace<Hub>;
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
  async apply(key: string, url: string | null, body: unknown): Promise<SessionList | null> {
    const now = new Date().toISOString();
    const stored = (await this.ctx.storage.get<SessionList>("list")) ?? emptyList(key, url, now);
    const base = url && !stored.url ? { ...stored, url } : stored;
    const next = applyHook(base, body, now);
    if (!next) {
      if (base !== stored) await this.ctx.storage.put("list", base);
      return null;
    }
    await this.ctx.storage.put("list", next);
    await this.ctx.storage.put("pushPending", true);
    const alarm = await this.ctx.storage.getAlarm();
    if (alarm === null || alarm > Date.now() + PUSH_DEBOUNCE_MS) {
      await this.ctx.storage.setAlarm(Date.now() + PUSH_DEBOUNCE_MS);
    }
    return next;
  }

  async alarm(): Promise<void> {
    const list = await this.ctx.storage.get<SessionList>("list");
    if (!list) return;
    if (await this.ctx.storage.get<boolean>("pushPending")) {
      await this.ctx.storage.put("pushPending", false);
      await hub(this.env).publish(list);
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
  async publish(list: SessionList, store = true): Promise<{ sent: number; devices: number; fcmConfigured: boolean }> {
    if (store) this.ctx.storage.sql.exec(
      "INSERT INTO sessions (key, list, updated_at) VALUES (?, ?, ?) ON CONFLICT(key) DO UPDATE SET list = excluded.list, updated_at = excluded.updated_at",
      list.key, JSON.stringify(list), Date.now(),
    );
    const client = this.client();
    const devices = this.devices();
    if (!client) return { sent: 0, devices: devices.length, fcmConfigured: false };
    const data = toPushData(list);
    let sent = 0;
    for (const device of devices) {
      const result = await client.send(device.token, data);
      if (result === "sent") sent++;
      if (result === "unregistered") this.unregister(device.token);
    }
    return { sent, devices: devices.length, fcmConfigured: true };
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
      if (key) {
        await env.SESSION.get(env.SESSION.idFromName(key)).apply(key, entry.sessionUrl, body);
      }
      // An empty JSON object tells Claude Code to carry on as normal.
      return json({});
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
      return json(await hub(env).publish(demoList(new Date().toISOString()), false));
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
