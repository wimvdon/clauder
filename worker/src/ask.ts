// Reads what a turn's last reply asks the user, with a small language model (Cloudflare Workers
// AI). Only used when the hook data can't decide: the turn ended while something Claude set up
// (CI on a pull request, a reminder, a watched URL, helpers) will still wake the thread, so the
// thread may be waiting on that or on the user. Plain logic around an injected model, so it runs
// under plain Node tests.

/** Big enough to read Dutch and English replies well, and fast. */
export const ASK_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";

/** The end of a long reply is where it asks; the start is mostly the report. */
export const MAX_REPLY_CHARS = 4000;

export const MAX_QUESTION_CHARS = 200;

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
  "or another process, an optional offer such as \"let me know if you want more\", a question it",
  "answers itself, a question quoted from someone else.",
  'Reply with JSON only: {"waiting": true or false, "question": "what the user must answer,',
  'at most 15 words, in the language of the message, or empty"}.',
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
    max_tokens: 120,
    temperature: 0,
  };
}

/** The model's answer: the question when the reply waits on the user, "" when it doesn't, and
 * null when the answer can't be read (then nothing changes). */
export function parseAsk(output: unknown): string | null {
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
  const { waiting, question } = value as { waiting?: unknown; question?: unknown };
  if (typeof waiting !== "boolean") return null;
  if (!waiting) return "";
  const text = typeof question === "string" ? question.replace(/\s+/g, " ").trim() : "";
  const asked = text || "Claude asks you something";
  return asked.length <= MAX_QUESTION_CHARS ? asked : `${asked.slice(0, MAX_QUESTION_CHARS - 1).trimEnd()}…`;
}

/** What the reply asks ("" for nothing), or null when the model fails or answers unreadably. */
export async function findAsk(model: AskModel, reply: string): Promise<string | null> {
  if (!reply.trim()) return "";
  try {
    return parseAsk(await model.run(ASK_MODEL, askInput(reply)));
  } catch {
    return null;
  }
}
