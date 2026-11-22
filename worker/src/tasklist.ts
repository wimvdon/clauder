// Pure task-list logic: turns raw Claude Code hook payloads into one session's task list.
// No Worker types, so it runs under plain Node tests.

export type TaskStatus = "pending" | "in_progress" | "completed";

export interface Task {
  id: string;
  subject: string;
  status: TaskStatus;
}

/** running: Claude is working. idle: Claude finished its turn and nothing is running. waiting:
 * Claude asked the user something (a decision card or a permission prompt). done: every task is
 * completed. ended: the session closed. */
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
   * question dialog is answered before the turn ends. */
  needKind?: "decision" | "permission";
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

/** Notification types that mean Claude can't go on without the user. "idle_prompt" only says
 * Claude finished a turn, which Stop already covers. */
const ASKING = new Set(["permission_prompt", "elicitation_dialog"]);

/** The repository folder, or null for a home folder (project threads start in /home/user). */
export function repoFromCwd(cwd: string | null): string | null {
  const title = titleFromCwd(cwd);
  return title === "Claude Code" ? null : title;
}

/** True when the Stop payload lists background work (subagents, shells) that is still running. */
function hasRunningWork(input: Json): boolean {
  const tasks = input.background_tasks;
  return Array.isArray(tasks) && tasks.some((t) => obj(t)?.status === "running");
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

function stateFor(tasks: Task[], steps: Task[], state: SessionState): SessionState {
  if (state === "ended") return "ended";
  const main = steps.length > 0 ? steps : tasks;
  if (main.length > 0 && main.every((t) => t.status === "completed")) return "done";
  return state === "done" ? "running" : state;
}

/**
 * Applies one hook payload. Returns the new list, or null when the payload changes nothing that
 * the phone shows (then no push is sent).
 */
export function applyHook(list: SessionList, body: unknown, now: string): SessionList | null {
  const input = obj(body);
  if (!input) return null;
  const event = str(input.hook_event_name);
  let tasks = list.tasks;
  let steps = list.steps ?? [];
  let state = list.state;
  let need = list.need;
  let needKind = list.needKind;
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
      state = "running";
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
        state = "running";
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
        state = "running";
      } else if (tool === CHECKLIST_TOOL) {
        const text = str(toolInput?.text);
        if (!text) return null;
        const checklist = parseChecklist(text);
        steps = checklist.steps;
        if (checklist.title) {
          title = checklist.title;
          titleFromPlan = true;
        }
        state = "running";
      } else if (tool === "TodoWrite") {
        tasks = replaceTodos(tasks, parseTodos(toolInput?.todos));
        state = "running";
      } else if (tool === DECISION_TOOL) {
        state = "waiting";
        need = clip(str(toolInput?.question) ?? "", MAX_NEED) || undefined;
        needKind = "decision";
      } else if (tool === CLOSE_DECISION_TOOL) {
        state = "running";
      } else {
        return null;
      }
      break;
    }
    case "Stop":
      if (list.tasks.length === 0 && steps.length === 0) return null;
      // Stop ends every turn, so on its own it doesn't mean Claude waits for the user. A decision
      // card that is still open keeps the session waiting.
      if (hasRunningWork(input)) state = "running";
      else if (state !== "waiting" || needKind !== "decision") state = "idle";
      break;
    case "Notification": {
      const type = str(input.notification_type);
      const message = str(input.message) ?? "";
      if (type ? !ASKING.has(type) : !/permission/i.test(message)) return null;
      state = "waiting";
      need = clip(message, MAX_NEED) || undefined;
      needKind = "permission";
      break;
    }
    case "UserPromptSubmit": {
      state = "running";
      const info = projectInfo(input);
      project = info.project ?? project;
      projectId = info.projectId ?? projectId;
      thread = info.thread ?? thread;
      if (thread && !titleFromPlan) title = thread;
      break;
    }
    case "SessionEnd":
      state = "ended";
      break;
    default:
      return null;
  }

  state = stateFor(tasks, steps, state);
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
    JSON.stringify(tasks) !== JSON.stringify(list.tasks) ||
    JSON.stringify(steps) !== JSON.stringify(list.steps ?? []);
  if (!changed) return null;
  const { need: _need, needKind: _needKind, ...rest } = list;
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
    seq: list.seq + 1,
    updatedAt: now,
  };
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
    state: list.state,
    seq: String(list.seq),
    updatedAt: list.updatedAt,
    tasks: encode(tasks),
    steps: encode(steps),
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
