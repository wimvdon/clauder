// Pure task-list logic: turns raw Claude Code hook payloads into one session's task list.
// No Worker types, so it runs under plain Node tests.

export type TaskStatus = "pending" | "in_progress" | "completed";

export interface Task {
  id: string;
  subject: string;
  status: TaskStatus;
}

/** running: Claude is working. waiting: Claude stopped and waits for the user. done: every task is
 * completed. ended: the session closed. */
export type SessionState = "running" | "waiting" | "done" | "ended";

export interface SessionList {
  key: string;
  title: string;
  url: string | null;
  tasks: Task[];
  state: SessionState;
  /** Bumped on every change, so the phone can drop out-of-order pushes. */
  seq: number;
  updatedAt: string;
}

export const MAX_TASKS = 50;
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

export function emptyList(key: string, url: string | null, now: string): SessionList {
  return { key, title: "Claude Code", url, tasks: [], state: "running", seq: 0, updatedAt: now };
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

function stateFor(tasks: Task[], state: SessionState): SessionState {
  if (state === "ended") return "ended";
  if (tasks.length > 0 && tasks.every((t) => t.status === "completed")) return "done";
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
  let state = list.state;
  const title = list.tasks.length === 0 ? titleFromCwd(str(input.cwd)) : list.title;

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
      } else {
        return null;
      }
      break;
    }
    case "Stop":
    case "Notification":
      if (list.tasks.length === 0) return null;
      state = "waiting";
      break;
    case "UserPromptSubmit":
      state = "running";
      break;
    case "SessionEnd":
      state = "ended";
      break;
    default:
      return null;
  }

  state = stateFor(tasks, state);

  const changed =
    state !== list.state || title !== list.title || JSON.stringify(tasks) !== JSON.stringify(list.tasks);
  if (!changed) return null;
  return { ...list, title, tasks, state, seq: list.seq + 1, updatedAt: now };
}

export interface Progress {
  done: number;
  total: number;
  current: Task | null;
  next: Task | null;
}

/** "6/8 tasks": counts, the task Claude is on now and the one after it. */
export function progress(list: SessionList): Progress {
  const tasks = list.tasks;
  const done = tasks.filter((t) => t.status === "completed").length;
  const currentIndex = tasks.findIndex((t) => t.status === "in_progress");
  const current = currentIndex === -1 ? null : tasks[currentIndex];
  const next = tasks.find((t, i) => i > currentIndex && t.status === "pending") ?? null;
  return { done, total: tasks.length, current, next };
}

/** FCM data messages carry string values only and at most 4 KB. */
export function toPushData(list: SessionList): Record<string, string> {
  let tasks = list.tasks;
  const data = (): Record<string, string> => ({
    type: "tasklist",
    key: list.key,
    title: list.title,
    url: list.url ?? "",
    state: list.state,
    seq: String(list.seq),
    updatedAt: list.updatedAt,
    tasks: JSON.stringify(tasks.map((t) => ({ i: t.id, s: t.subject, t: t.status }))),
  });
  let out = data();
  while (JSON.stringify(out).length > 3800 && tasks.length > 0) {
    tasks = tasks.map((t) => ({ ...t, subject: clip(t.subject, Math.max(20, Math.floor(t.subject.length * 0.7))) }));
    out = data();
    if (tasks.every((t) => t.subject.length <= 20)) break;
  }
  return out;
}
