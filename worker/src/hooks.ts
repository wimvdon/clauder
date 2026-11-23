// Which hook settings a session runs, from the X-Clauder-Hooks-Loaded header. Plain logic, so it
// runs under plain Node tests.

/** The oldest level that reports every wake source the Worker needs to tell Waiting for you from
 * Idle. Level 2 adds git push (CI runs again), which the Worker can do without. */
export const MIN_HOOKS_LEVEL = 1;

/** The installer's version before levels existed; those settings are level 1. */
const LEVEL_1_HASH = "5ba5b56f378b";

/** True when the settings the session loaded are new enough, false when they are older, and
 * undefined when the request doesn't say (hooks from before the header). */
export function loadedIsCurrent(loaded: string | null): boolean | undefined {
  if (loaded === null) return undefined;
  if (loaded === LEVEL_1_HASH) return MIN_HOOKS_LEVEL <= 1;
  const m = /^L(\d{1,4})$/.exec(loaded.trim());
  return m !== null && Number(m[1]) >= MIN_HOOKS_LEVEL;
}

/** The parts of a logged hook event the setup check needs. */
export interface LastEvent {
  receivedAt: string;
  event: string | null;
  hooks?: string | null;
  hooksLoaded?: string | null;
}

/** What the app's setup check shows (GET /status): when the last hook event arrived, and whether
 * the session that sent it runs the current hook script ([version]) and settings. Null for either
 * when its hooks don't say. */
export function statusFrom(last: LastEvent | null, version: string, level: string): Record<string, unknown> {
  return {
    hooksVersion: version,
    hooksLevel: level,
    lastEvent: last
      ? {
          at: last.receivedAt,
          event: last.event,
          scriptCurrent: last.hooks == null ? null : last.hooks === version,
          settingsCurrent: loadedIsCurrent(last.hooksLoaded ?? null) ?? null,
        }
      : null,
  };
}
