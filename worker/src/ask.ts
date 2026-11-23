// Reads a turn's last reply with a small language model (Cloudflare Workers AI), once per turn:
// a one-line summary of where the work stands, for the phone's notifications, what the reply
// asks the user, and whether it says the work is finished with nothing left for the user (then a
// thread that only the user could wake is marked Done). The question only decides the state when the hook data can't: the turn ended
// while something Claude set up (CI on a pull request, a reminder, a watched URL, helpers) will
// still wake the thread, so the thread may be waiting on that or on the user. Plain logic around
// an injected model, so it runs under plain Node tests.

/** Big enough to read Dutch and English replies well, and fast. */
export const ASK_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";

/** The end of a long reply is where it asks; the start is mostly the report. */
export const MAX_REPLY_CHARS = 3000;

export const MAX_QUESTION_CHARS = 200;
export const MAX_SUMMARY_CHARS = 120;

/** What the model read in a reply: the question it asks the user ("" for none), a one-line
 * summary ("" when it gave none), and whether the work is finished with nothing left for the user
 * (never together with a question). */
export interface TurnRead {
  question: string;
  summary: string;
  finished: boolean;
}

/** The part of the Workers AI binding this uses. */
export interface AskModel {
  run(model: string, input: Record<string, unknown>): Promise<unknown>;
}

const SYSTEM = [
  "You read the last message an AI assistant sent to its user at the end of its turn.",
  "Decide whether the assistant now needs the user to answer a question, pick an option or",
  "approve a step before it can go on with that work.",
  "Count: a direct question to the user, a list of options to pick from, a request for approval",
  "or a go-ahead, a request for information only the user has.",
  "Don't count: a report of finished or ongoing work, a note that it waits for CI, a reminder",
  "or another process, a next step the assistant will take itself (such as \"I'll merge it once",
  "CI passes\"), test steps or instructions the user can follow later (such as \"the PR lists",
  "the steps to try it on your phone\"), an optional offer such as \"let me know if you want",
  "more\", a question it answers itself, a question quoted from someone else.",
  "When waiting is true, the question field must say what the user must answer; if you can't",
  "name it, waiting is false.",
  "Also write a summary: one line of at most 12 words, in the language of the message, saying",
  "where the work stands for the user, such as \"Snoozer CI running; mockups ready, pick T1-T3\".",
  "No greeting, no \"Claude\", no full stop at the end.",
  "Also decide whether the work is finished: true only when the message says what the user asked",
  "for is completely done and nothing is left for the user to do, such as \"PR merged\" or",
  "\"0.2.11 is released\". False when work goes on or waits for CI, a review, a merge, a release",
  "or another process, when the user still has to review, approve, test or pick something (a",
  "document, a draft, mockups, a pull request), when the user must take an action, or when you",
  "are unsure. Test steps for later or an optional offer don't make it unfinished.",
  'Reply with JSON only: {"waiting": true or false, "question": "what the user must answer,',
  'at most 15 words, in the language of the message, or empty", "summary": "the line",',
  '"finished": true or false}.',
].join(" ");

/** The tail of a reply, cut at a line break when it is long. */
export function replyTail(text: string): string {
  if (text.length <= MAX_REPLY_CHARS) return text;
  const tail = text.slice(-MAX_REPLY_CHARS);
  const nl = tail.indexOf("\n");
  return nl > 0 && nl < 500 ? tail.slice(nl + 1) : tail;
}

export function askInput(reply: string): Record<string, unknown> {
  return {
    messages: [
      { role: "system", content: SYSTEM },
      { role: "user", content: `<message>\n${replyTail(reply)}\n</message>` },
    ],
    max_tokens: 180,
    temperature: 0,
  };
}

function oneLine(value: unknown, max: number): string {
  const text = typeof value === "string" ? value.replace(/\s+/g, " ").trim().replace(/[.。]$/, "") : "";
  return text.length <= max ? text : `${text.slice(0, max - 1).trimEnd()}…`;
}

/** The model's answer, or null when it can't be read (then nothing changes). */
export function parseTurn(output: unknown): TurnRead | null {
  const response = typeof output === "object" && output !== null ? (output as { response?: unknown }).response : output;
  let value: unknown = response;
  if (typeof response === "string") {
    const match = response.match(/\{[\s\S]*\}/);
    if (!match) return null;
    try {
      value = JSON.parse(match[0]);
    } catch {
      return null;
    }
  }
  if (typeof value !== "object" || value === null) return null;
  const { waiting, question, summary, finished } = value as {
    waiting?: unknown; question?: unknown; summary?: unknown; finished?: unknown;
  };
  if (typeof waiting !== "boolean") return null;
  // A question the model can't name isn't one: it said "waiting" for test steps or a CI wait.
  const asked = waiting ? oneLine(question, MAX_QUESTION_CHARS) : "";
  // Only a clear true counts, and a reply that asks something isn't finished.
  return { question: asked, summary: oneLine(summary, MAX_SUMMARY_CHARS), finished: finished === true && !waiting };
}

/** Reads the reply, or null when the model fails or answers unreadably. */
export async function readTurn(model: AskModel, reply: string): Promise<TurnRead | null> {
  if (!reply.trim()) return { question: "", summary: "", finished: false };
  try {
    return parseTurn(await model.run(ASK_MODEL, askInput(reply)));
  } catch {
    return null;
  }
}
