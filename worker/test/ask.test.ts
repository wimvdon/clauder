import { test } from "node:test";
import assert from "node:assert/strict";
import { ASK_MODEL, askInput, findAsk, MAX_REPLY_CHARS, parseAsk, replyTail } from "../src/ask.ts";

test("the model's JSON answer is read, as text or as an object", () => {
  assert.equal(parseAsk({ response: '{"waiting": true, "question": "Pick T1, T2 or T3"}' }), "Pick T1, T2 or T3");
  assert.equal(parseAsk({ response: 'Sure: {"waiting": false, "question": ""}' }), "");
  assert.equal(parseAsk({ response: { waiting: true, question: "  Merge   it? " } }), "Merge it?");
  assert.equal(parseAsk({ response: { waiting: true } }), "Claude asks you something");
  assert.equal(parseAsk({ response: "yes" }), null);
  assert.equal(parseAsk({ response: '{"waiting": "maybe"}' }), null);
  assert.equal(parseAsk(null), null);
  assert.equal(parseAsk({ response: { waiting: true, question: "x".repeat(300) } })?.length, 200);
});

test("findAsk sends the reply's tail to the model and survives failures", async () => {
  const calls: Array<{ model: string; input: Record<string, unknown> }> = [];
  const model = {
    async run(m: string, input: Record<string, unknown>) {
      calls.push({ model: m, input });
      return { response: '{"waiting": true, "question": "Kies T1, T2 of T3"}' };
    },
  };
  assert.equal(await findAsk(model, "Kies er een: T1, T2 of T3."), "Kies T1, T2 of T3");
  assert.equal(calls[0].model, ASK_MODEL);
  const messages = calls[0].input.messages as Array<{ role: string; content: string }>;
  assert.equal(messages[0].role, "system");
  assert.match(messages[1].content, /Kies er een/);
  assert.equal(await findAsk(model, "   "), "");
  assert.equal(calls.length, 1);
  const broken = { async run(): Promise<unknown> { throw new Error("quota"); } };
  assert.equal(await findAsk(broken, "Merge?"), null);
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
