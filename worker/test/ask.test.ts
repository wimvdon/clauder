import { test } from "node:test";
import assert from "node:assert/strict";
import { ASK_MODEL, askInput, parseRequest, readRequest, MAX_REPLY_CHARS, parseSummary, parseTurn, readTurn, replyTail, SUMMARY_MODEL } from "../src/ask.ts";

test("the model's JSON answer is read, as text or as an object", () => {
  assert.deepEqual(
    parseTurn({ response: '{"waiting": true, "question": "Pick T1, T2 or T3", "summary": "Mockups ready, pick T1-T3."}' }),
    { question: "Pick T1, T2 or T3", summary: "Mockups ready, pick T1-T3", finished: false },
  );
  assert.deepEqual(parseTurn({ response: 'Sure: {"waiting": false, "question": "", "summary": "PR #38 merged"}' }), { question: "", summary: "PR #38 merged", finished: false });
  assert.deepEqual(parseTurn({ response: { waiting: true, question: "  Merge   it? " } }), { question: "Merge it?", summary: "", finished: false });
  // Finished only on a clear true, and never while it asks something.
  assert.equal(parseTurn({ response: '{"waiting": false, "question": "", "summary": "0.2.11 released", "finished": true}' })?.finished, true);
  assert.equal(parseTurn({ response: { waiting: false, finished: "yes" } })?.finished, false);
  assert.equal(parseTurn({ response: { waiting: true, question: "Release?", finished: true } })?.finished, false);
  // "Waiting" without a question it can name isn't a question (PR test steps, a CI wait).
  assert.deepEqual(parseTurn({ response: { waiting: true, question: "", summary: "PR #49 open, CI running" } }), { question: "", summary: "PR #49 open, CI running", finished: false });
  assert.equal(parseTurn({ response: { waiting: true } })?.question, "");
  assert.equal(parseTurn({ response: "yes" }), null);
  assert.equal(parseTurn({ response: '{"waiting": "maybe"}' }), null);
  assert.equal(parseTurn(null), null);
  assert.equal(parseTurn({ response: { waiting: true, question: "x".repeat(300) } })?.question.length, 200);
  assert.equal(parseTurn({ response: { waiting: false, summary: "y".repeat(300) } })?.summary.length, 120);
});

test("readTurn sends the reply's tail to the model and survives failures", async () => {
  const calls: Array<{ model: string; input: Record<string, unknown> }> = [];
  const model = {
    async run(m: string, input: Record<string, unknown>) {
      calls.push({ model: m, input });
      return { response: '{"waiting": true, "question": "Kies T1, T2 of T3", "summary": "Mockups klaar, kies T1-T3"}' };
    },
  };
  assert.deepEqual((await readTurn(model, "Kies er een: T1, T2 of T3.")).read, { question: "Kies T1, T2 of T3", summary: "Mockups klaar, kies T1-T3", finished: false });
  assert.equal(calls[0].model, ASK_MODEL);
  const messages = calls[0].input.messages as Array<{ role: string; content: string }>;
  assert.equal(messages[0].role, "system");
  assert.match(messages[1].content, /Kies er een/);
  assert.deepEqual((await readTurn(model, "   ")).read, { question: "", summary: "", finished: false });
  assert.match(messages[0].content, /"finished"/);
  assert.equal(calls.length, 1);
  const broken = { async run(): Promise<unknown> { throw new Error("timeout"); } };
  assert.deepEqual(await readTurn(broken, "Merge?"), { read: null, neurons: 0, allocationUsed: false });
  const limited = { async run(): Promise<unknown> { throw new Error("3036: You have used up your daily free allocation of 10,000 neurons."); } };
  assert.equal((await readTurn(limited, "Merge?")).allocationUsed, true);
});

test("a long reply keeps its end, where it asks", () => {
  const text = `${"report line\n".repeat(1000)}Pick one: A or B?`;
  const tail = replyTail(text);
  assert.ok(tail.length <= MAX_REPLY_CHARS);
  assert.ok(tail.endsWith("Pick one: A or B?"));
  assert.ok(tail.startsWith("report line"));
  assert.equal(replyTail("short"), "short");
  assert.equal((askInput("x").messages as unknown[]).length, 2);
});

test("each read reports its neurons, from the usage the model gives or an estimate", async () => {
  const reported = { async run() { return { response: '{"waiting": false, "summary": "CI running"}', usage: { prompt_tokens: 1000, completion_tokens: 50 } }; } };
  const full = await readTurn(reported, "CI runs.");
  assert.ok(Math.abs(full.neurons - (1000 * 26_668 + 50 * 204_805) / 1e6) < 1e-9);
  const bare = { async run() { return { response: '{"waiting": false, "summary": "CI running"}' } ; } };
  const estimated = await readTurn(bare, "CI runs.");
  assert.ok(estimated.neurons > 5 && estimated.neurons < 40, String(estimated.neurons));
});

test("summary mode asks the small model for the line only", async () => {
  const calls: string[] = [];
  const model = {
    async run(m: string, input: Record<string, unknown>) {
      calls.push(m);
      const system = (input.messages as Array<{ content: string }>)[0].content;
      assert.doesNotMatch(system, /"finished"/);
      return { response: '{"summary": "Card is up, pick a colour."}', usage: { prompt_tokens: 300, completion_tokens: 15 } };
    },
  };
  const result = await readTurn(model, "Card is up.", "summary");
  assert.deepEqual(calls, [SUMMARY_MODEL]);
  assert.deepEqual(result.read, { question: "", summary: "Card is up, pick a colour", finished: false });
  assert.ok(result.neurons < 2);
  assert.equal(parseSummary({ response: "no json" }), null);
  assert.equal(parseSummary({ response: '{"summary": ""}' }), null);
  assert.equal((askInput("x", "summary").max_tokens as number) < (askInput("x").max_tokens as number), true);
});

test("the person's message is read by the small model for a Working on line and a title", async () => {
  const calls: string[] = [];
  const model = {
    async run(m: string) {
      calls.push(m);
      return { response: '{"working": "Fixing the Safari export button.", "title": "Fix Safari export button"}', usage: { prompt_tokens: 250, completion_tokens: 20 } };
    },
  };
  const result = await readRequest(model, "The export button does nothing on Safari. Can you look?");
  assert.deepEqual(calls, [SUMMARY_MODEL]);
  assert.deepEqual(result.read, { working: "Fixing the Safari export button", title: "Fix Safari export button" });
  assert.ok(result.neurons < 2);
  assert.deepEqual(parseRequest({ response: '{"working": "", "title": ""}' }), { working: "", title: "" });
  assert.equal(parseRequest({ response: "no" }), null);
  assert.equal(parseRequest({ response: '{"other": 1}' }), null);
  assert.equal(parseRequest({ response: { working: "w", title: "t".repeat(100) } })?.title.length, 60);
  const broken = { async run(): Promise<unknown> { throw new Error("3036: daily free allocation"); } };
  assert.deepEqual(await readRequest(broken, "Hi"), { read: null, neurons: 0, allocationUsed: true });
});
