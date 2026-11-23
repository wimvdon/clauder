// Pure task-list logic: turns raw Claude Code hook payloads into one session's task list.
// No Worker types, so it runs under plain Node tests.

import { MAX_REPLY_CHARS, type TurnRead } from "./ask.ts";
import {
  afterPush,
  applyPrTool,
  linkFromOutputs,
  PR_TOOLS,
  prWakes,
  REMINDER_TOOL,
  UNWATCH_TOOL,
  WATCH_TOOL,
  watchIdFrom,
  usageFrom,
  wakeAtFrom,
  type Link,
  type PullRequest,
  type Usage,
} from "./extras.ts";
import { applyPrChange, type PrChange } from "./github.ts";

export type { Link, PullRequest, Usage } from "./extras.ts";

export type TaskStatus = "pending" | "in_progress" | "completed";

export interface Task {
  id: string;
  subject: string;
  status: TaskStatus;
}

/** A turn that ends can only go on when something wakes the session: something Claude set up
 * itself, or the user. So after Stop: running while a wake source is armed (a reminder still
 * ahead, an in-session cron, a PR subscription whose CI still runs, a watched URL, or background
 * helpers and shells). done: the thread was resolved, or, during a turn, every step is completed.
 * waiting: nothing Claude set up will wake it, so only the user can (also an open decision card,
 * a permission prompt, or a turn whose last reply a language model read as asking the user; see
 * ask.ts). idle: nothing known is armed, but the session runs older hook settings
 * that don't report every wake source, so it can't be told apart; the phone also shows a session
 * silent for hours as idle.
 * ended: the session closed. */
export type SessionState = "running" | "idle" | "waiting" | "done" | "ended";

export interface SessionList {
  key: string;
  title: string;
  url: string | null;
  /** Claude Code's task list (TaskCreate/TaskUpdate) and to-dos (TodoWrite). */
  tasks: Task[];
  /** A project thread's progress checklist, the plan the person follows. Empty elsewhere. */
  steps?: Task[];
  state: SessionState;
  /** True once a project thread's checklist named the list; the folder name no longer applies. */
  titleFromPlan?: boolean;
  /** The claude.ai project the session belongs to, when it is a project thread. */
  project?: string;
  projectId?: string;
  /** A project thread's own title, used until its checklist names the list. */
  thread?: string;
  /** The folder Claude works in, when it is a repository rather than the home folder. */
  repo?: string;
  /** While waiting: what Claude asks, such as a decision card's question or the permission it needs. */
  need?: string;
  /** What the wait is for. A decision card stays open across turns; a permission prompt or a
   * question form (Notification) is answered before the turn ends. */
  needKind?: "decision" | "permission" | "question";
  /** The pull request the session works on (the newest it opened or follows), with its CI. */
  pr?: PullRequest;
  /** A link the last reply attached that the phone can open, such as a mockup or a page. */
  link?: Link;
  /** When a reminder Claude set (send_later) brings it back, if that is still ahead. */
  wakeAt?: string;
  /** Inbound webhooks the session watches (watch_url trigger ids). They end with the session. */
  watches?: string[];
  /** True when the session runs the current hook settings, so every wake source reaches the
   * Worker. Settings load at session start; a session started with older ones may set up a wake
   * the Worker never hears of. */
  hooksCurrent?: boolean;
  /** Background helpers (subagents, shells) still running when the turn ended. */
  helpers?: number;
  /** Tokens used so far and what they would cost on the API (from the Stop hook script). */
  usage?: Usage;
  /** The thread was marked resolved: finished, even with steps left open. */
  resolved?: boolean;
  /** The turn's last reply to the user (a project thread's post_message), kept only until the turn
   * ends. Not sent to the phone. */
  reply?: string;
  /** Set when a turn ended with a reply: the text the model reads next (see ask.ts and
   * [applyAsk]). Not sent to the phone. */
  askCheck?: string;
  /** The model's one-line summary of the last turn, until the person writes again or a later turn
   * writes a new one. */
  summary?: string;
  /** Set when the person wrote while every step was already completed: that old checklist doesn't
   * make the thread Done until it changes. Not sent to the phone. */
  staleFinish?: boolean;
  /** Bumped on every change, so the phone can drop out-of-order pushes. */
  seq: number;
  updatedAt: string;
}

export const MAX_TASKS = 50;
export const MAX_NEED = 200;
export const MAX_SUBJECT = 120;

type Json = Record<string, unknown>;

function str(value: unknown): string | null {
  return typeof value === "string" && value.length > 0 ? value : null;
}

function obj(value: unknown): Json | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Json) : null;
}

export function clip(text: string, max = MAX_SUBJECT): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length <= max ? flat : `${flat.slice(0, max - 1).trimEnd()}…`;
}

/** A readable list title. Claude Code task lists have no title, so we use the folder name, unless
 * the session runs in a home folder (project threads start in /home/user). */
export function titleFromCwd(cwd: string | null): string {
  const parts = (cwd ?? "").split("/").filter(Boolean);
  const inHome = (parts[0] === "home" || parts[0] === "Users") && parts.length <= 2;
  if (parts.length === 0 || inHome || (parts[0] === "root" && parts.length === 1)) return "Claude Code";
  return parts[parts.length - 1];
}

/** The key that identifies a session: the cloud session id when there is one, else Claude Code's
 * local session id. */
export function sessionKey(remoteSessionId: string | null, body: unknown): string | null {
  const remote = remoteSessionId?.trim();
  if (remote && /^cse_[A-Za-z0-9]+$/.test(remote)) return remote;
  const local = str(obj(body)?.session_id);
  return local && /^[A-Za-z0-9_-]{1,100}$/.test(local) ? local : null;
}

export const CHECKLIST_TOOL = "mcp__hearthbot__update_progress_checklist";
/** A project thread asks the user to choose; close_decision withdraws or settles the card. */
export const DECISION_TOOL = "mcp__hearthbot__request_decision";
export const CLOSE_DECISION_TOOL = "mcp__hearthbot__close_decision";
/** A project thread's reply to the person; only its attached link is used. */
export const REPLY_TOOL = "mcp__hearthbot__post_message";
export const RESOLVE_TOOL = "mcp__hearthbot__set_thread_resolved";
const PUSH_FILES_TOOL = "mcp__github__push_files";

/** Notification types that mean Claude can't go on without the user. "idle_prompt" only says
 * Claude finished a turn, which Stop already covers. */
const ASKING = new Set(["permission_prompt", "elicitation_dialog"]);

/** The repository folder, or null for a home folder (project threads start in /home/user). */
export function repoFromCwd(cwd: string | null): string | null {
  const title = titleFromCwd(cwd);
  return title === "Claude Code" ? null : title;
}

/** How much background work (subagents, shells) the Stop payload lists as still running. */
function runningWork(input: Json): number {
  const tasks = input.background_tasks;
  return Array.isArray(tasks) ? tasks.filter((t) => obj(t)?.status === "running").length : 0;
}

/** Whether a prompt from this source reopens a resolved thread (see the prompt hook script). */
export function reopens(source: unknown): boolean {
  if (typeof source !== "string") return true;
  return source === "relay" || source === "wake:mention" || source === "wake:decision-chosen";
}

/** Whether a prompt from this source answers what the session waits on. An open decision card
 * stays open when the person writes about something else, or a coordinator message arrives: only
 * the person's pick, or Claude closing the card, settles it. Older hook scripts don't say the
 * source, so any prompt counts. A question read in the reply is answered by any message from the
 * person. */
export function answers(needKind: SessionList["needKind"], source: unknown): boolean {
  if (needKind === "decision") return typeof source !== "string" || source === "wake:decision-chosen";
  return reopens(source);
}

/** Project details the UserPromptSubmit hook script reads from the session's transcript. */
function projectInfo(input: Json): { project?: string; projectId?: string; thread?: string } {
  const info = obj(input.clauder);
  if (!info) return {};
  const project = str(info.project);
  const projectId = str(info.projectId);
  const thread = str(info.thread);
  return {
    ...(project ? { project: clip(project, 80) } : {}),
    ...(projectId && /^chan_[A-Za-z0-9]+$/.test(projectId) ? { projectId } : {}),
    ...(thread ? { thread: clip(thread, 80) } : {}),
  };
}

/** A project thread's progress checklist: a header, a blank line, then one step per line that
 * starts with ✓ (done), ✱ (in progress) or ○ (not started). */
export function parseChecklist(text: string): { title: string | null; steps: Task[] } {
  const marks: Record<string, TaskStatus> = { "✓": "completed", "✱": "in_progress", "○": "pending" };
  const steps: Task[] = [];
  let title: string | null = null;
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    if (!line) continue;
    const status = marks[line[0]];
    if (status) {
      const subject = clip(line.slice(1));
      if (subject && steps.length < MAX_TASKS) steps.push({ id: `plan:${steps.length + 1}`, subject, status });
    } else if (title === null && steps.length === 0) {
      title = clip(line, 80);
    }
  }
  return { title, steps };
}

/** Claude Code's own to-do list (TodoWrite). */
export function parseTodos(todos: unknown): Task[] {
  if (!Array.isArray(todos)) return [];
  return todos.slice(0, MAX_TASKS).flatMap((item, i) => {
    const todo = obj(item);
    const subject = str(todo?.content);
    if (!subject || !isStatus(todo?.status)) return [];
    return [{ id: `todo:${i + 1}`, subject: clip(subject), status: todo.status }];
  });
}

/** Swaps the to-dos (ids starting with "todo:") for a new set, keeping Claude Code's tasks. */
function replaceTodos(tasks: Task[], next: Task[]): Task[] {
  return [...tasks.filter((t) => !t.id.startsWith("todo:")), ...next];
}

/** What the phone counts and shows as progress: the steps when the session has a checklist,
 * else the tasks. */
export function primary(list: SessionList): Task[] {
  return (list.steps ?? []).length > 0 ? (list.steps ?? []) : list.tasks;
}

export function emptyList(key: string, url: string | null, now: string): SessionList {
  return { key, title: "Claude Code", url, tasks: [], steps: [], state: "running", seq: 0, updatedAt: now };
}

function isStatus(value: unknown): value is TaskStatus {
  return value === "pending" || value === "in_progress" || value === "completed";
}

function upsert(tasks: Task[], id: string, change: Partial<Task>): Task[] {
  const index = tasks.findIndex((t) => t.id === id);
  if (index === -1) {
    if (tasks.length >= MAX_TASKS) return tasks;
    return [...tasks, { id, subject: change.subject ?? `Task ${id}`, status: change.status ?? "pending" }];
  }
  const next = tasks.slice();
  next[index] = { ...next[index], ...change };
  return next;
}

/** The next seq: one more than [list]'s, and never less than the time in milliseconds. The phone
 * drops a push whose seq isn't higher than the last it saw, so when the Worker forgets a quiet
 * session and it starts again, its new seq must still be higher. */
export function nextSeq(list: SessionList, now: string): number {
  const ms = Date.parse(now);
  return Math.max(list.seq + 1, Number.isNaN(ms) ? 0 : ms);
}

function allDone(items: Task[]): boolean {
  return items.length > 0 && items.every((t) => t.status === "completed");
}

function stateFor(tasks: Task[], steps: Task[], state: SessionState, resolved: boolean, staleFinish = false): SessionState {
  if (state === "ended") return state;
  if (resolved) return "done";
  // Waiting wins over a finished checklist: an unresolved thread still needs the user.
  if (state === "waiting") return state;
  if (!staleFinish && allDone(steps.length > 0 ? steps : tasks)) return "done";
  return state === "done" ? "running" : state;
}

/**
 * Applies one hook payload. Returns the new list, or null when the payload changes nothing that
 * the phone shows (then no push is sent).
 */
export function applyHook(list: SessionList, body: unknown, now: string, hooks?: boolean): SessionList | null {
  const input = obj(body);
  if (!input) return null;
  const event = str(input.hook_event_name);
  let tasks = list.tasks;
  let steps = list.steps ?? [];
  let state = list.state;
  let need = list.need;
  let needKind = list.needKind;
  let pr = list.pr;
  let link = list.link;
  let wakeAt = list.wakeAt && list.wakeAt > now ? list.wakeAt : undefined;
  let watches = list.watches ?? [];
  const hooksCurrent = hooks ?? list.hooksCurrent ?? false;
  let helpers = list.helpers;
  let usage = list.usage;
  let resolved = list.resolved ?? false;
  let reply = list.reply;
  let summary = list.summary;
  // The reply the model still has to read stays until a new prompt starts the next turn.
  let askCheck = list.askCheck;
  let staleFinish = list.staleFinish ?? false;
  // An open decision card, or a question the person hasn't answered yet, keeps the session
  // waiting while Claude goes on with the work, also through wakes the person didn't cause (CI
  // results, a reminder). Only the person's answer (see [reopens]) or a closed card ends it.
  const deciding = state === "waiting" && (needKind === "decision" || needKind === "question");
  const working: SessionState = deciding ? "waiting" : "running";
  // Title: the checklist heading, else the thread's title, else the folder name.
  let thread = list.thread;
  let title = list.titleFromPlan
    ? list.title
    : (thread ?? (list.tasks.length === 0 && steps.length === 0 ? titleFromCwd(str(input.cwd)) : list.title));
  let titleFromPlan = list.titleFromPlan ?? false;
  let { project, projectId } = list;
  const repo = repoFromCwd(str(input.cwd)) ?? list.repo;

  switch (event) {
    case "TaskCreated": {
      const id = str(input.task_id);
      if (!id) return null;
      tasks = upsert(tasks, id, { subject: clip(str(input.task_subject) ?? `Task ${id}`) });
      state = working;
      break;
    }
    case "TaskCompleted": {
      const id = str(input.task_id);
      if (!id) return null;
      const subject = str(input.task_subject);
      tasks = upsert(tasks, id, { status: "completed", ...(subject ? { subject: clip(subject) } : {}) });
      break;
    }
    case "PostToolUse": {
      const tool = str(input.tool_name);
      const toolInput = obj(input.tool_input);
      const response = obj(input.tool_response);
      if (tool === "TaskCreate") {
        const id = str(obj(response?.task)?.id);
        const subject = str(toolInput?.subject);
        if (!id) return null;
        tasks = upsert(tasks, id, subject ? { subject: clip(subject) } : {});
        state = working;
      } else if (tool === "TaskUpdate") {
        const id = str(toolInput?.taskId) ?? str(response?.taskId);
        if (!id || response?.success === false) return null;
        if (toolInput?.status === "deleted") {
          tasks = tasks.filter((t) => t.id !== id);
        } else {
          const change: Partial<Task> = {};
          const to = obj(response?.statusChange)?.to ?? toolInput?.status;
          if (isStatus(to)) change.status = to;
          const subject = str(toolInput?.subject);
          if (subject) change.subject = clip(subject);
          tasks = upsert(tasks, id, change);
        }
        state = working;
      } else if (tool === CHECKLIST_TOOL) {
        const text = str(toolInput?.text);
        if (!text) return null;
        const checklist = parseChecklist(text);
        steps = checklist.steps;
        if (checklist.title) {
          title = checklist.title;
          titleFromPlan = true;
        }
        state = working;
      } else if (tool === "TodoWrite") {
        tasks = replaceTodos(tasks, parseTodos(toolInput?.todos));
        state = working;
      } else if (tool === DECISION_TOOL) {
        state = "waiting";
        need = clip(str(toolInput?.question) ?? "", MAX_NEED) || undefined;
        needKind = "decision";
      } else if (tool === CLOSE_DECISION_TOOL) {
        if (needKind === "decision") state = "running";
      } else if (tool === REPLY_TOOL) {
        const attached = linkFromOutputs(toolInput?.attached_outputs);
        if (attached) link = attached;
        const text = str(toolInput?.text);
        if (text) reply = text.slice(-MAX_REPLY_CHARS);
      } else if (tool === RESOLVE_TOOL) {
        resolved = toolInput?.resolved !== false;
      } else if (tool === REMINDER_TOOL) {
        wakeAt = wakeAtFrom(toolInput, now, input.tool_response) ?? wakeAt;
      } else if (tool === WATCH_TOOL) {
        watches = [...watches, watchIdFrom(input.tool_response)];
      } else if (tool === UNWATCH_TOOL) {
        const id = str(toolInput?.trigger_id);
        const at = id && watches.includes(id) ? watches.indexOf(id) : watches.length - 1;
        if (at < 0) return null;
        watches = watches.filter((_, i) => i !== at);
      } else if (tool === "Bash" || tool === PUSH_FILES_TOOL) {
        const next = afterPush(pr, tool, toolInput);
        if (next === pr) return null;
        pr = next;
      } else if (tool && PR_TOOLS.includes(tool)) {
        const next = applyPrTool(pr, tool, toolInput, input.tool_response);
        if (next === pr) return null;
        pr = next;
      } else {
        return null;
      }
      break;
    }
    case "Stop": {
      if (list.tasks.length === 0 && steps.length === 0) return null;
      usage = usageFrom(obj(input.clauder)?.usage) ?? usage;
      helpers = runningWork(input) || undefined;
      // A model reads the turn's last reply next: a summary line for the phone, and whether it
      // asks the user something (which decides the state only when the hook data can't).
      const said = reply ?? str(input.last_assistant_message)?.slice(-MAX_REPLY_CHARS);
      askCheck = said ?? undefined;
      reply = undefined;
      // Stop ends every turn. An open decision card or question keeps the session waiting. Otherwise it is
      // running while something Claude set up will wake it, and waiting on the user when
      // nothing will.
      if (deciding) break;
      const crons = Array.isArray(input.session_crons) && input.session_crons.length > 0;
      const armed = helpers !== undefined || crons || prWakes(pr) || wakeAt !== undefined || watches.length > 0;
      state = armed ? "running" : hooksCurrent ? "waiting" : "idle";
      break;
    }
    case "Notification": {
      const type = str(input.notification_type);
      const message = str(input.message) ?? "";
      if (!type || !ASKING.has(type)) return null;
      state = "waiting";
      need = clip(message, MAX_NEED) || undefined;
      needKind = "permission";
      break;
    }
    case "UserPromptSubmit": {
      // A person's message, a decision tap or a coordinator message reopens a resolved thread and
      // answers what Claude asked; background events such as CI results don't. Older hook scripts
      // don't say, so any prompt counts as the person's.
      const source = obj(input.clauder)?.source;
      const fromPerson = reopens(source);
      state = deciding && !answers(needKind, source) ? "waiting" : "running";
      // A background event (a CI result, a finished helper) is often injected into a running turn,
      // so the reply Claude sent before it, and the check of the last turn's reply, still count.
      // The person's message answers them.
      if (fromPerson) {
        reply = undefined;
        askCheck = undefined;
      }
      // The summary says where the work stands until the person writes; a CI event or a reminder
      // keeps it, so a wait on CI still shows the line about it.
      if (fromPerson) {
        summary = undefined;
        resolved = false;
        // A checklist that was already complete is the last request's; the new one is underway.
        staleFinish = allDone(steps.length > 0 ? steps : tasks);
      }
      const info = projectInfo(input);
      project = info.project ?? project;
      projectId = info.projectId ?? projectId;
      thread = info.thread ?? thread;
      if (thread && !titleFromPlan) title = thread;
      break;
    }
    case "SessionEnd":
      // A project thread's session stops between wakes (reason "other") and resumes on the next
      // wake. Watches, crons and helpers end with the session; a reminder and a PR subscription
      // live on the server and still wake it. With neither, only the user can.
      watches = [];
      if (project && input?.reason === "other") {
        if (state === "running" && wakeAt === undefined && !prWakes(pr)) state = hooksCurrent ? "waiting" : "idle";
      } else {
        state = "ended";
      }
      break;
    default:
      return null;
  }

  // A new checklist or task change is the current work again.
  if (JSON.stringify(steps) !== JSON.stringify(list.steps ?? []) || JSON.stringify(tasks) !== JSON.stringify(list.tasks)) staleFinish = false;
  state = stateFor(tasks, steps, state, resolved, staleFinish);
  if (state !== "waiting") {
    need = undefined;
    needKind = undefined;
  }

  const changed =
    state !== list.state ||
    title !== list.title ||
    project !== list.project ||
    projectId !== list.projectId ||
    thread !== list.thread ||
    repo !== list.repo ||
    need !== list.need ||
    summary !== list.summary ||
    resolved !== (list.resolved ?? false) ||
    wakeAt !== list.wakeAt ||
    JSON.stringify(watches) !== JSON.stringify(list.watches ?? []) ||
    hooksCurrent !== (list.hooksCurrent ?? false) ||
    helpers !== list.helpers ||
    JSON.stringify(pr) !== JSON.stringify(list.pr) ||
    JSON.stringify(link) !== JSON.stringify(list.link) ||
    JSON.stringify(usage) !== JSON.stringify(list.usage) ||
    JSON.stringify(tasks) !== JSON.stringify(list.tasks) ||
    JSON.stringify(steps) !== JSON.stringify(list.steps ?? []);
  const {
    need: _need, needKind: _needKind, pr: _pr, link: _link, wakeAt: _wakeAt, watches: _watches, hooksCurrent: _hooksCurrent,
    helpers: _helpers, usage: _usage, resolved: _resolved, reply: _reply, askCheck: _askCheck, summary: _summary,
    staleFinish: _staleFinish, ...rest
  } = list;
  if (!changed) {
    // Nothing the phone shows changed; keep the reply or the check without a push (same seq).
    if (reply === list.reply && askCheck === list.askCheck && staleFinish === (list.staleFinish ?? false)) return null;
    return {
      ...rest, ...pick(list), ...(reply ? { reply } : {}), ...(askCheck ? { askCheck } : {}), ...(summary ? { summary } : {}),
      ...(staleFinish ? { staleFinish } : {}),
    };
  }
  return {
    ...rest,
    title,
    titleFromPlan,
    tasks,
    steps,
    state,
    ...(project ? { project } : {}),
    ...(projectId ? { projectId } : {}),
    ...(thread ? { thread } : {}),
    ...(repo ? { repo } : {}),
    ...(need ? { need } : {}),
    ...(needKind ? { needKind } : {}),
    ...(pr ? { pr } : {}),
    ...(link ? { link } : {}),
    ...(wakeAt ? { wakeAt } : {}),
    ...(watches.length ? { watches } : {}),
    ...(hooksCurrent ? { hooksCurrent } : {}),
    ...(helpers ? { helpers } : {}),
    ...(usage ? { usage } : {}),
    ...(resolved ? { resolved } : {}),
    ...(reply ? { reply } : {}),
    ...(askCheck ? { askCheck } : {}),
    ...(summary ? { summary } : {}),
    ...(staleFinish ? { staleFinish } : {}),
    seq: nextSeq(list, now),
    updatedAt: now,
  };
}

/** The optional fields of a list that stay as they are. */
function pick(list: SessionList): Partial<SessionList> {
  const { need, needKind, pr, link, wakeAt, watches, hooksCurrent, helpers, usage, resolved } = list;
  return Object.fromEntries(
    Object.entries({ need, needKind, pr, link, wakeAt, watches, hooksCurrent, helpers, usage, resolved })
      .filter(([, v]) => v !== undefined),
  ) as Partial<SessionList>;
}

/**
 * Applies what the model read in the reply of the turn that ended at [seq]. The summary line is
 * always kept. A question makes a working or idle thread wait on the user, and names what a
 * thread that already waits (with nothing else to show) asks. A finished turn marks a thread that
 * waits on nothing but the person resolved. Ignored when [reply] is no longer
 * the one waiting to be read: a new prompt started the next turn, or the check was already done.
 * Events in between (a session pause, a late tool result) don't drop it. Returns null when
 * nothing changes.
 */
export function applyAsk(list: SessionList, reply: string, read: TurnRead | null, now: string): SessionList | null {
  if (list.askCheck === undefined || list.askCheck !== reply) return null;
  const { askCheck: _askCheck, ...rest } = list;
  if (!read) return rest;
  const next: SessionList = { ...rest };
  if (read.summary) next.summary = clip(read.summary, MAX_NEED);
  // A resolved or closed thread doesn't wait, whatever its last reply says. A finished checklist
  // doesn't end an unresolved thread that asks something.
  if (read.question && !list.resolved && list.state !== "ended") {
    if (list.state !== "waiting") {
      next.state = "waiting";
      next.need = clip(read.question, MAX_NEED);
      next.needKind = "question";
    } else if (!list.need) {
      next.need = clip(read.question, MAX_NEED);
      next.needKind = "question";
    }
  } else if (read.finished && list.state === "waiting" && list.needKind === undefined && list.hooksCurrent && !list.resolved) {
    // The reply says the work is finished, and only the person could wake the thread (nothing
    // armed, no open card or question, current hooks): resolved, as Mark resolved does. Only in
    // Clauder; the Worker can't reach the Claude app. The person's next message reopens it.
    next.state = "done";
    next.resolved = true;
    delete next.need;
  }
  const shown = next.state !== list.state || next.need !== list.need || next.summary !== list.summary;
  return shown ? { ...next, seq: nextSeq(list, now), updatedAt: now } : next;
}

/**
 * The person marked the thread resolved on the phone. Resolving a thread by hand in the Claude app
 * sends no hook event, so this is the only way the Worker learns of it. Like set_thread_resolved,
 * the next message from the person reopens the thread. Returns null when nothing changes.
 */
export function resolveByUser(list: SessionList, now: string): SessionList | null {
  if (list.resolved || list.state === "ended") return null;
  const { need: _need, needKind: _needKind, askCheck: _askCheck, ...rest } = list;
  return { ...rest, state: "done", resolved: true, seq: nextSeq(list, now), updatedAt: now };
}

export interface Progress {
  done: number;
  total: number;
  current: Task | null;
  next: Task | null;
}

/** "6/8": counts, the step or task Claude is on now and the one after it. */
export function progress(list: SessionList): Progress {
  const tasks = primary(list);
  const done = tasks.filter((t) => t.status === "completed").length;
  const currentIndex = tasks.findIndex((t) => t.status === "in_progress");
  const current = currentIndex === -1 ? null : tasks[currentIndex];
  const next = tasks.find((t, i) => i > currentIndex && t.status === "pending") ?? null;
  return { done, total: tasks.length, current, next };
}

/** FCM data messages carry string values only and at most 4 KB. */
export function toPushData(list: SessionList): Record<string, string> {
  let tasks = list.tasks;
  let steps = list.steps ?? [];
  const encode = (items: Task[]) => JSON.stringify(items.map((t) => ({ i: t.id, s: t.subject, t: t.status })));
  const data = (): Record<string, string> => ({
    type: "tasklist",
    key: list.key,
    title: list.title,
    url: list.url ?? "",
    project: list.project ?? "",
    projectId: list.projectId ?? "",
    repo: list.repo ?? "",
    need: list.need ?? "",
    summary: list.summary ?? "",
    state: list.state,
    seq: String(list.seq),
    updatedAt: list.updatedAt,
    tasks: encode(tasks),
    steps: encode(steps),
    pr: list.pr ? JSON.stringify({ n: list.pr.number, u: list.pr.url, s: list.pr.state, c: list.pr.ci ?? "", ...(list.pr.review ? { r: list.pr.review } : {}) }) : "",
    link: list.link?.url ?? "",
    linkTitle: list.link?.title ?? "",
    wakeAt: list.wakeAt ?? "",
    helpers: String(list.helpers ?? 0),
    tokens: String(list.usage?.tokens ?? 0),
    cost: String(list.usage?.cost ?? 0),
    // "old" when the session runs hook settings from before the installer reported every wake
    // source: it can't update them itself, so the phone shows how to.
    hooks: list.hooksCurrent ? "" : "old",
  });
  const shorten = (items: Task[]) =>
    items.map((t) => ({ ...t, subject: clip(t.subject, Math.max(20, Math.floor(t.subject.length * 0.7))) }));
  let out = data();
  while (JSON.stringify(out).length > 3800) {
    const before = JSON.stringify(out).length;
    tasks = shorten(tasks);
    steps = shorten(steps);
    out = data();
    if (JSON.stringify(out).length === before) break;
  }
  return out;
}

/** A GitHub webhook changed the PR the session follows: its CI, state or review. The session's
 * own state doesn't change here; a PR subscription wakes Claude, and that turn's hooks do. Returns
 * null when it isn't the session's PR or nothing changes. */
export function applyPrUpdate(list: SessionList, change: PrChange, now: string): SessionList | null {
  const pr = applyPrChange(list.pr, change);
  if (!pr || pr === list.pr) return null;
  return { ...list, pr, seq: nextSeq(list, now), updatedAt: now };
}
