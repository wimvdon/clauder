// Pure helpers, kept free of Worker types so they run under plain Node tests.

/** Compares two strings in constant time for equal lengths. */
export function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** True when the Authorization header carries exactly `Bearer <expected>`. */
export function isAuthorized(header: string | null, expected: string | undefined): boolean {
  if (!expected || !header) return false;
  const match = /^Bearer\s+(.+)$/.exec(header.trim());
  return match !== null && safeEqual(match[1], expected);
}

/**
 * Turns the cloud session id that the hook sends in a header (`cse_…`) into the
 * claude.ai URL of that session (`https://claude.ai/code/session_…`).
 * Returns null for anything else, including the unexpanded `$VAR` text a hook
 * sends when the variable is missing.
 */
export function sessionUrl(remoteSessionId: string | null): string | null {
  const match = /^cse_([A-Za-z0-9]+)$/.exec(remoteSessionId?.trim() ?? "");
  return match ? `https://claude.ai/code/session_${match[1]}` : null;
}

/** Clamps the `limit` query parameter of GET /log. */
export function parseLimit(value: string | null, max: number): number {
  const n = Number.parseInt(value ?? "", 10);
  if (!Number.isFinite(n) || n <= 0) return max;
  return Math.min(n, max);
}
