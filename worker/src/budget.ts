// Keeps Workers AI inside its free daily allocation (10,000 neurons a day, reset at 00:00 UTC).
// Every call reports the tokens it used; they are counted here in neurons, per UTC day. Near the
// limit, the calls that only add a line of text stop first, so the reads that decide a state
// (whether a reply asks the user, or says the work is finished) keep working longest. Plain logic,
// so it runs under plain Node tests.

/** Workers AI's free allocation per day. */
export const FREE_NEURONS = 10_000;

/** Above this, only reads that can change a session's state still run. */
export const EXTRA_LIMIT = 7_000;

/** Above this, nothing runs: a call that would cross the free allocation fails anyway. */
export const STATE_LIMIT = 9_700;

/** What a call is for. "state": it can change what the phone says the session is doing.
 * "extra": it only writes a line of text, such as a summary. */
export type Priority = "state" | "extra";

/** Neurons per million tokens, from https://developers.cloudflare.com/workers-ai/platform/pricing/ */
export const RATES: Record<string, { input: number; output: number }> = {
  "@cf/meta/llama-3.3-70b-instruct-fp8-fast": { input: 26_668, output: 204_805 },
  "@cf/meta/llama-3.1-8b-instruct-fp8-fast": { input: 4_119, output: 34_868 },
};

export interface Budget {
  /** The UTC day the count is for, such as "2026-11-23". */
  day: string;
  neurons: number;
  /** Workers AI refused a call for the day's allocation: nothing runs until the next day. */
  exhausted: boolean;
}

export function utcDay(now: Date): string {
  return now.toISOString().slice(0, 10);
}

/** The budget for [day]: the stored one, or a fresh one when the day changed. */
export function current(stored: Budget | null | undefined, day: string): Budget {
  return stored && stored.day === day ? stored : { day, neurons: 0, exhausted: false };
}

export function allows(budget: Budget, priority: Priority): boolean {
  if (budget.exhausted) return false;
  return budget.neurons < (priority === "state" ? STATE_LIMIT : EXTRA_LIMIT);
}

/** About four characters a token, rounded up: used when a call doesn't report its tokens. */
export function estimateTokens(text: string): number {
  return Math.ceil(text.length / 4);
}

/** The neurons a call used, from the token counts it reported. */
export function neurons(model: string, inputTokens: number, outputTokens: number): number {
  const rate = RATES[model] ?? RATES["@cf/meta/llama-3.3-70b-instruct-fp8-fast"];
  return (Math.max(0, inputTokens) * rate.input + Math.max(0, outputTokens) * rate.output) / 1_000_000;
}

/** The token counts in a Workers AI answer ({usage: {prompt_tokens, completion_tokens}}), or null. */
export function reportedUsage(output: unknown): { input: number; output: number } | null {
  const usage = typeof output === "object" && output !== null ? (output as { usage?: unknown }).usage : null;
  if (typeof usage !== "object" || usage === null) return null;
  const { prompt_tokens: input, completion_tokens: out } = usage as { prompt_tokens?: unknown; completion_tokens?: unknown };
  return typeof input === "number" && typeof out === "number" ? { input, output: out } : null;
}

/** True when Workers AI refused the call because the day's allocation is used up (error 3036,
 * "Account limited"). */
export function isAllocationError(error: unknown): boolean {
  const text = error instanceof Error ? error.message : String(error);
  return /\b3036\b|daily free allocation/i.test(text);
}

export function spend(budget: Budget, used: number, exhausted = false): Budget {
  return { day: budget.day, neurons: budget.neurons + used, exhausted: budget.exhausted || exhausted };
}
