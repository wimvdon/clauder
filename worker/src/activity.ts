// What Claude is doing right now, and what it changed since the person last wrote. Both come from
// the hook script, which reads them from the raw hook input and the transcript (see
// hooks/install-user-hooks.sh). Plain logic, so it runs under plain Node tests.

type Json = Record<string, unknown>;

/** The tool Claude is about to run (PreToolUse, at most every 10 seconds): its name and a short
 * detail, such as a file's name or the description Claude gave a shell command. */
export interface Doing {
  tool: string;
  detail?: string;
}

/** The files Claude edited and the lines it added and removed since the person last wrote, and
 * when that was. [endedAt] is when the turn that reported it ended. */
export interface Work {
  since: string;
  endedAt: string;
  files: number;
  added: number;
  removed: number;
}

export const MAX_TOOL = 80;
export const MAX_DETAIL = 80;
const MAX_COUNT = 1_000_000;

function obj(value: unknown): Json | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Json) : null;
}

function text(value: unknown, max: number): string | null {
  if (typeof value !== "string") return null;
  const flat = value.replace(/\s+/g, " ").trim();
  if (!flat) return null;
  return flat.length <= max ? flat : `${flat.slice(0, max - 1).trimEnd()}…`;
}

function count(value: unknown): number | null {
  return typeof value === "number" && Number.isInteger(value) && value >= 0 ? Math.min(value, MAX_COUNT) : null;
}

function isTime(value: unknown): value is string {
  return typeof value === "string" && value.length <= 40 && !Number.isNaN(Date.parse(value));
}

/** The `clauder.tool` the PreToolUse hook script sends. Null when it is missing or malformed. */
export function doingFrom(info: unknown): Doing | null {
  const tool = obj(info);
  const name = text(tool?.name, MAX_TOOL);
  if (!name || !/^[A-Za-z0-9_.:-]+$/.test(name)) return null;
  const detail = text(tool?.detail, MAX_DETAIL);
  return detail ? { tool: name, detail } : { tool: name };
}

/** The `clauder.work` the Stop hook script sends: what changed since [since]. Null when it is
 * missing or malformed, or [since] is later than [now]. */
export function workFrom(info: unknown, now: string): Work | null {
  const work = obj(info);
  if (!work || !isTime(work.since) || Date.parse(work.since) > Date.parse(now) + 60_000) return null;
  const files = count(work.files);
  const added = count(work.added);
  const removed = count(work.removed);
  if (files === null || added === null || removed === null) return null;
  return { since: new Date(Date.parse(work.since)).toISOString(), endedAt: now, files, added, removed };
}
