/* ──────────────────────────────────────────────
   @eddi/core/user-events — the per-user event stream (B12)
   `GET /me/events`: what concerns the signed-in user, for every tab they have
   open — an approval waiting for them, a team discussion that ended, a long
   job that finished, a new notification. Ids and kinds only, never content:
   a client fetches details through the endpoints it would use anyway.

   A separate entry, not re-exported from the package root: the Chat UI widget
   does not use it and is at its bundle budget. The Manager and Workforce
   import it explicitly. Backend: docs/user-events.md.
   ────────────────────────────────────────────── */

import { ApiError, type HttpClient } from "./http";
import { readSseStream } from "./sse";

/** Every kind the backend sends (`UserEvent.KINDS`). */
export const USER_EVENT_KINDS = [
  "approval.pending",
  "approval.decided",
  "team.started",
  "team.ended",
  "team.waiting_for_human",
  "job.finished",
  "job.run_completed",
  "job.failed",
  "notification.created",
] as const;

export type UserEventKind = (typeof USER_EVENT_KINDS)[number];

/**
 * One event. Only `id`, `kind` and `ts` are always present; the rest are set
 * where they apply (see docs/user-events.md for which kind carries what).
 */
export interface UserEvent {
  id: string;
  kind: UserEventKind;
  /** When it was raised, epoch milliseconds. */
  ts: number;
  /** `regular` (a 1:1 conversation) or `group`. */
  surface?: "regular" | "group";
  conversationId?: string;
  agentId?: string;
  groupId?: string;
  groupConversationId?: string;
  /**
   * A short enum value: a decision's outcome (`APPROVED`, `REJECTED`, `ABORTED`,
   * `MIXED`, …), a discussion's state (`COMPLETED`, `FAILED`, …), a gate
   * (`PHASE`, `TASK`), why a job stopped (`done`, `limit`, …), a fire's status.
   */
  state?: string;
  /** For a long job: `continuation` or `schedule`. */
  jobKind?: "continuation" | "schedule";
  scheduleId?: string;
  notificationId?: string;
  /** `SHARED_WITH_YOU`, `ACCESS_REQUESTED`, `UPDATE_AVAILABLE`. */
  notificationType?: string;
  /** On `team.waiting_for_human`: true for the person whose turn it is. */
  yourTurn?: boolean;
}

/**
 * - `idle`: not started, or stopped by `stop()`.
 * - `connecting`: a request is out (first connect or a reconnect).
 * - `open`: the server's `ready` frame arrived. **Refetch what you show now**:
 *   there is no replay, so anything that happened while disconnected is only
 *   visible through a refetch.
 * - `waiting`: disconnected, a reconnect is scheduled.
 * - `stopped`: the server refused for good (401 after a token refresh, 403,
 *   404 — switched off). Not retried; `start()` tries again.
 */
export type UserEventStreamStatus = "idle" | "connecting" | "open" | "waiting" | "stopped";

export type UserEventHandler = (event: UserEvent) => void;
export type UserEventStatusHandler = (status: UserEventStreamStatus) => void;

/** Timers, injectable for tests. */
export interface UserEventTimers {
  setTimeout(callback: () => void, ms: number): unknown;
  clearTimeout(handle: unknown): void;
}

export interface UserEventStreamOptions {
  /** The app's client: base URL, bearer token, one retry after a 401. */
  http: HttpClient;
  /** Default `/me/events`. */
  path?: string;
  /** First reconnect delay. Default 1 s; doubles per failed attempt. */
  initialDelayMs?: number;
  /** Longest reconnect delay. Default 30 s. */
  maxDelayMs?: number;
  /** 0 ≤ x < 1, for jitter. Default `Math.random`. */
  random?: () => number;
  timers?: UserEventTimers;
}

export interface UserEventStream {
  /** Connects (idempotent: a running stream is left alone). */
  start(): void;
  /** Disconnects and cancels any pending reconnect. */
  stop(): void;
  /** Subscribes to one kind, or `"*"` for all. Returns the unsubscribe. */
  on(kind: UserEventKind | "*", handler: UserEventHandler): () => void;
  /** Subscribes to status changes. Returns the unsubscribe. */
  onStatus(handler: UserEventStatusHandler): () => void;
  readonly status: UserEventStreamStatus;
}

const KNOWN = new Set<string>(USER_EVENT_KINDS);

/**
 * Parse one event frame's data. Null for anything unusable — not JSON, no id,
 * a kind this client does not know (a newer server's kind is ignored, not
 * passed on untyped), or a frame whose name disagrees with its kind.
 */
export function parseUserEvent(name: string, data: string): UserEvent | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(data);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== "object") return null;
  const event = parsed as Record<string, unknown>;
  if (typeof event.id !== "string" || typeof event.kind !== "string" || !KNOWN.has(event.kind)) return null;
  if (event.kind !== name) return null;
  if (typeof event.ts !== "number") return null;
  return event as unknown as UserEvent;
}

/**
 * The delay before reconnect attempt `attempt` (0-based): exponential from
 * `initialMs`, capped at `maxMs`, with jitter in [50 %, 100 %] so a server
 * restart is not met by every tab at the same instant.
 */
export function reconnectDelay(attempt: number, initialMs: number, maxMs: number, random: () => number): number {
  const base = Math.min(maxMs, initialMs * 2 ** Math.min(attempt, 30));
  return Math.round(base * (0.5 + random() * 0.5));
}

const defaultTimers: UserEventTimers = {
  setTimeout: (callback, ms) => globalThis.setTimeout(callback, ms),
  clearTimeout: (handle) => globalThis.clearTimeout(handle as ReturnType<typeof globalThis.setTimeout>),
};

/** Statuses after which the server will not change its mind on a retry. */
const FATAL = new Set([401, 403, 404]);

/**
 * A stream over `GET /me/events`. Prefer {@link getUserEventStream}, which
 * keeps it to one per tab; this is the building block (and what tests use).
 *
 * It reads the stream with `fetch` through the app's `HttpClient` — an
 * `EventSource` cannot send the bearer token — so the token, the base URL and
 * the one refresh-and-retry on a 401 are the app's. It reconnects after the
 * stream ends or fails, with backoff, and also when the server closes it on
 * purpose (token expiry, maximum age, a slow consumer).
 */
export function createUserEventStream(options: UserEventStreamOptions): UserEventStream {
  const path = options.path ?? "/me/events";
  const initialMs = options.initialDelayMs ?? 1000;
  const maxMs = options.maxDelayMs ?? 30_000;
  const random = options.random ?? Math.random;
  const timers = options.timers ?? defaultTimers;

  const handlers = new Map<string, Set<UserEventHandler>>();
  const statusHandlers = new Set<UserEventStatusHandler>();
  let status: UserEventStreamStatus = "idle";
  let attempt = 0;
  let controller: AbortController | null = null;
  let timer: unknown = null;
  /** Bumped by start()/stop(), so a superseded loop ends quietly. */
  let generation = 0;

  function setStatus(next: UserEventStreamStatus) {
    if (status === next) return;
    status = next;
    for (const handler of [...statusHandlers]) {
      try {
        handler(next);
      } catch {
        // one subscriber's mistake must not stop the stream
      }
    }
  }

  function emit(event: UserEvent) {
    for (const key of [event.kind, "*"]) {
      for (const handler of [...(handlers.get(key) ?? [])]) {
        try {
          handler(event);
        } catch {
          // as above
        }
      }
    }
  }

  function schedule(myGeneration: number, minimumMs = 0) {
    if (myGeneration !== generation) return;
    setStatus("waiting");
    const delay = Math.max(minimumMs, reconnectDelay(attempt, initialMs, maxMs, random));
    attempt += 1;
    timer = timers.setTimeout(() => {
      timer = null;
      void connect(myGeneration);
    }, delay);
  }

  async function connect(myGeneration: number) {
    if (myGeneration !== generation) return;
    setStatus("connecting");
    const abort = new AbortController();
    controller = abort;
    let response: Response;
    try {
      response = await options.http.request(path, { headers: { Accept: "text/event-stream" }, signal: abort.signal }, "User events");
    } catch (err) {
      if (myGeneration !== generation) return;
      if (err instanceof ApiError && FATAL.has(err.status)) {
        setStatus("stopped");
        return;
      }
      const retryAfter = err instanceof ApiError && err.status === 429 ? retryAfterMs(err) : 0;
      schedule(myGeneration, retryAfter);
      return;
    }
    try {
      for await (const frame of readSseStream(response.body, { defaultEventType: null })) {
        if (myGeneration !== generation) return;
        if (frame.type === "ready") {
          attempt = 0;
          setStatus("open");
          continue;
        }
        const event = parseUserEvent(frame.type, frame.data);
        if (event) emit(event);
      }
    } catch {
      // the stream broke; reconnect below
    }
    if (myGeneration !== generation) return;
    schedule(myGeneration);
  }

  return {
    start() {
      if (status === "connecting" || status === "open" || status === "waiting") return;
      generation += 1;
      attempt = 0;
      void connect(generation);
    },
    stop() {
      generation += 1;
      if (timer !== null) {
        timers.clearTimeout(timer);
        timer = null;
      }
      controller?.abort();
      controller = null;
      setStatus("idle");
    },
    on(kind, handler) {
      let set = handlers.get(kind);
      if (!set) {
        set = new Set();
        handlers.set(kind, set);
      }
      set.add(handler);
      return () => {
        handlers.get(kind)?.delete(handler);
      };
    },
    onStatus(handler) {
      statusHandlers.add(handler);
      return () => {
        statusHandlers.delete(handler);
      };
    },
    get status() {
      return status;
    },
  };
}

/** `Retry-After` is not exposed on ApiError; the backend asks for 30 s. */
function retryAfterMs(err: ApiError): number {
  try {
    const parsed = JSON.parse(err.body) as { retryAfterSeconds?: unknown };
    if (typeof parsed.retryAfterSeconds === "number") return parsed.retryAfterSeconds * 1000;
  } catch {
    // not JSON
  }
  return 30_000;
}

let shared: { http: HttpClient; stream: UserEventStream } | null = null;

/**
 * The tab's one stream. Every caller in a tab gets the same instance (for the
 * same `HttpClient`), so a page with five widgets that want events still opens
 * one connection — the server caps connections per user. Call `start()` once
 * the user is signed in; `stop()` on sign-out.
 */
export function getUserEventStream(options: UserEventStreamOptions): UserEventStream {
  if (shared && shared.http === options.http) return shared.stream;
  shared?.stream.stop();
  shared = { http: options.http, stream: createUserEventStream(options) };
  return shared.stream;
}

/** Test seam: forget the tab's stream (stopping it). */
export function resetUserEventStreamForTests(): void {
  shared?.stream.stop();
  shared = null;
}
