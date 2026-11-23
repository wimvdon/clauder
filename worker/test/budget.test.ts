import { test } from "node:test";
import assert from "node:assert/strict";
import { allows, current, EXTRA_LIMIT, isAllocationError, neurons, reportedUsage, spend, STATE_LIMIT, utcDay } from "../src/budget.ts";

test("the count starts again each UTC day", () => {
  assert.equal(utcDay(new Date("2026-11-23T23:59:00+01:00")), "2026-11-23");
  assert.equal(utcDay(new Date("2026-11-24T00:30:00+01:00")), "2026-11-23");
  const used = { day: "2026-11-22", neurons: 9_900, exhausted: true };
  assert.deepEqual(current(used, "2026-11-22"), used);
  assert.deepEqual(current(used, "2026-11-23"), { day: "2026-11-23", neurons: 0, exhausted: false });
  assert.deepEqual(current(undefined, "2026-11-23"), { day: "2026-11-23", neurons: 0, exhausted: false });
});

test("near the limit the extra lines stop first, then everything", () => {
  const day = "2026-11-23";
  const at = (n: number) => ({ day, neurons: n, exhausted: false });
  assert.equal(allows(at(0), "extra"), true);
  assert.equal(allows(at(EXTRA_LIMIT), "extra"), false);
  assert.equal(allows(at(EXTRA_LIMIT), "state"), true);
  assert.equal(allows(at(STATE_LIMIT), "state"), false);
  assert.equal(allows({ day, neurons: 10, exhausted: true }, "state"), false);
  assert.deepEqual(spend(at(100), 30.5), at(130.5));
  assert.equal(spend(at(100), 0, true).exhausted, true);
});

test("neurons come from the model's token counts", () => {
  assert.equal(neurons("@cf/meta/llama-3.3-70b-instruct-fp8-fast", 1_000_000, 0), 26_668);
  assert.equal(neurons("@cf/meta/llama-3.1-8b-instruct-fp8-fast", 0, 1_000_000), 34_868);
  // An unknown model counts at the big model's price, never cheaper.
  assert.equal(neurons("@cf/other", 1_000_000, 0), 26_668);
  assert.deepEqual(reportedUsage({ usage: { prompt_tokens: 12, completion_tokens: 3, total_tokens: 15 } }), { input: 12, output: 3 });
  assert.equal(reportedUsage({ response: "x" }), null);
  assert.equal(isAllocationError(new Error("AiError: 3036: You have used up your daily free allocation of 10,000 neurons.")), true);
  assert.equal(isAllocationError(new Error("3040: Capacity temporarily exceeded")), false);
});
