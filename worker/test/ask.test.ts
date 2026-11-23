import { test } from "node:test";
import assert from "node:assert/strict";
import { ASK_MODEL, askInput, MAX_REPLY_CHARS, parseTurn, readTurn, replyTail } from "../src/ask.ts";

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
  assert.deepEqual(await readTurn(model, "Kies er een: T1, T2 of T3."), { question: "Kies T1, T2 of T3", summary: "Mockups klaar, kies T1-T3", finished: false });
  assert.equal(calls[0].model, ASK_MODEL);
  const messages = calls[0].input.messages as Array<{ role: string; content: string }>;
  assert.equal(messages[0].role, "system");
  assert.match(messages[1].content, /Kies er een/);
  assert.deepEqual(await readTurn(model, "   "), { question: "", summary: "", finished: false });
  assert.match(messages[0].content, /"finished"/);
  assert.equal(calls.length, 1);
  const broken = { async run(): Promise<unknown> { throw new Error("quota"); } };
  assert.equal(await readTurn(broken, "Merge?"), null);
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
