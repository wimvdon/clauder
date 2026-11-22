// Finds what Claude asks the user at the end of a turn, in the text of its last reply.
// Plain text logic, so it runs under plain Node tests.

/** Words that show a question is put to the user, not asked and answered in passing
 * ("Why did it fail? The token expired."). English and Dutch, the languages Wim writes in. */
const ADDRESSED = new RegExp(
  [
    String.raw`\byou(r|rs|'d|'re|'ll|'ve)?\b`,
    String.raw`\b(shall|should|can|may|do) I\b`,
    String.raw`\bwant me\b`,
    String.raw`\b(ok|okay|good to go|sound good|go ahead)\b`,
    String.raw`\b(je|jij|jou|jouw|u|uw)\b`,
    String.raw`\b(zal|mag|moet|kan) ik\b`,
    String.raw`\b(oké|ok|akkoord|goed zo)\b`,
  ].join("|"),
  "i",
);

/** Sentences that ask for an answer or approval without a question mark. "Let me know if…" is
 * left out on purpose: replies that finish the work often end with it. */
const ASKS_FOR_APPROVAL = new RegExp(
  [
    String.raw`\blet me know (which|what|whether|how)\b`,
    String.raw`\btell me (which|what|whether|how)\b`,
    String.raw`\bonce you (approve|confirm|decide|pick|choose|say|give)\b`,
    String.raw`\bwaiting (for|on) (you|your)\b`,
    String.raw`\blaat (me |het |maar )*weten (welke|wat|of|hoe)\b`,
    String.raw`\bzodra (je|jij|u) (goedkeurt|kiest|akkoord|beslist|bevestigt)\b`,
    String.raw`\bwacht op (je|jouw|uw)\b`,
    String.raw`\b(je|jouw|uw) (akkoord|goedkeuring|keuze)\b`,
  ].join("|"),
  "i",
);

/** Pending checklist steps that wait on the user, such as "After approval: build it". */
const WAITS_ON_USER = /^(after|once|when) (your |you |the )?(approv|ok|go-ahead|confirm|decid|pick|choos)|^(na|zodra|als) (je |jouw |uw |de )?(goedkeuring|akkoord|keuze|bevestiging|goedkeurt|kiest)/i;

/** Markdown that hides or decorates text: code, links, emphasis, headings and quotes. */
function plain(text: string): string[] {
  return text
    .replace(/```[\s\S]*?```/g, "\n")
    .replace(/`[^`\n]*`/g, "…")
    .replace(/!?\[([^\]\n]*)\]\([^)\n]*\)/g, "$1")
    .replace(/[*_~]{1,3}/g, "")
    .split("\n")
    .map((line) => line.trim())
    // Headings and quoted text (a quote of what someone else asked) are not the ask.
    .filter((line) => line && !line.startsWith("#") && !line.startsWith(">"));
}

function sentences(line: string): string[] {
  return line
    .replace(/^([-*+]|\d+[.)])\s+/, "")
    .split(/(?<=[.?!])\s+(?=[^\s])/)
    .map((s) => s.trim())
    .filter(Boolean);
}

/** True for a question that sits inside quotation marks, a question someone else asked. */
function quoted(sentence: string): boolean {
  return /["“„][^"”]*\?["”]/.test(sentence);
}

/** The last thing the reply asks the user, or null when it asks nothing. Prefers a question to
 * the user; falls back to a request for approval phrased as a statement. */
export function findAsk(text: string): string | null {
  const all = plain(text).flatMap(sentences);
  for (let i = all.length - 1; i >= 0; i--) {
    const s = all[i];
    if (/\?["”')\]]*$/.test(s) && !quoted(s) && ADDRESSED.test(s) && s.length >= 8) return s;
  }
  for (let i = all.length - 1; i >= 0; i--) {
    if (ASKS_FOR_APPROVAL.test(all[i])) return all[i];
  }
  return null;
}

/** True for a step that can only start once the user answers, such as "After approval: …". */
export function waitsOnUser(subject: string): boolean {
  return WAITS_ON_USER.test(subject.trim());
}
