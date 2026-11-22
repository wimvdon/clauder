// Spike Worker (milestone 1): stores every raw Claude Code hook payload so we can
// see what each hook event really sends. No phone, no FCM yet.
//
//   POST /hook   body = hook input JSON, Authorization: Bearer <HOOK_TOKEN>
//   GET  /log    recent payloads, newest first (?limit=50), same bearer token
//   DELETE /log  clears the stored payloads, same bearer token

import { DurableObject } from "cloudflare:workers";
import { isAuthorized, parseLimit, sessionUrl } from "./auth";

export interface Env {
  HOOK_TOKEN: string;
  SPIKE_LOG: DurableObjectNamespace<SpikeLog>;
}

const MAX_ENTRIES = 500;
const MAX_BODY_BYTES = 256 * 1024;

export interface LogEntry {
  receivedAt: string;
  remoteSessionId: string | null;
  sessionUrl: string | null;
  event: string | null;
  body: unknown;
}

/** One instance holds the whole spike log in its SQLite-backed storage. */
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
    sql.exec(
      "DELETE FROM log WHERE id <= (SELECT MAX(id) FROM log) - ?",
      MAX_ENTRIES,
    );
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

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: { "content-type": "application/json" },
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (!isAuthorized(request.headers.get("authorization"), env.HOOK_TOKEN)) {
      return json({ error: "unauthorized" }, 401);
    }
    const log = env.SPIKE_LOG.get(env.SPIKE_LOG.idFromName("spike"));

    if (url.pathname === "/hook" && request.method === "POST") {
      const text = await request.text();
      if (text.length > MAX_BODY_BYTES) return json({ error: "too large" }, 413);
      let body: unknown = text;
      try {
        body = JSON.parse(text);
      } catch {
        // Keep the raw text: the point of the spike is to see exactly what arrives.
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
      console.log(JSON.stringify(entry));
      await log.append(entry);
      // An empty JSON object tells Claude Code to carry on as normal.
      return json({});
    }

    if (url.pathname === "/log" && request.method === "GET") {
      return json(await log.recent(parseLimit(url.searchParams.get("limit"), MAX_ENTRIES)));
    }

    if (url.pathname === "/log" && request.method === "DELETE") {
      await log.clear();
      return json({ cleared: true });
    }

    return json({ error: "not found" }, 404);
  },
} satisfies ExportedHandler<Env>;
