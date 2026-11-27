/* ──────────────────────────────────────────────
   @eddi/core/user-events — GET /me/events client
   ────────────────────────────────────────────── */

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpClient } from "./http";
import { sseStream } from "./test-utils/sse";
import {
  USER_EVENT_KINDS,
  createUserEventStream,
  getUserEventStream,
  parseUserEvent,
  reconnectDelay,
  resetUserEventStreamForTests,
  type UserEvent,
  type UserEventStreamStatus,
  type UserEventTimers,
} from "./user-events";

/** Timers a test fires by hand. */
function manualTimers() {
  const pending: Array<{ callback: () => void; ms: number; id: number }> = [];
  let next = 1;
  const timers: UserEventTimers = {
    setTimeout(callback, ms) {
      const id = next++;
      pending.push({ callback, ms, id });
      return id;
    },
    clearTimeout(handle) {
      const index = pending.findIndex((p) => p.id === handle);
      if (index >= 0) pending.splice(index, 1);
    },
  };
  return {
    timers,
    pending,
    fireNext() {
      const timer = pending.shift();
      timer?.callback();
      return timer;
    },
  };
}

const ready = 'event: ready\ndata: {"v":1,"heartbeatSeconds":20}\n\n';

function frame(event: Partial<UserEvent> & { kind: string }): string {
  return `event: ${event.kind}\ndata: ${JSON.stringify({ id: "e-1", ts: 1, ...event })}\n\n`;
}

/** A fetch that answers each call with the next response in line. */
function fetchSequence(responses: Array<() => Response>) {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const fetch = vi.fn((url: string, init?: RequestInit) => {
    calls.push({ url, init });
    const next = responses.shift();
    return Promise.resolve(next ? next() : new Response(null, { status: 503 }));
  });
  return { fetch, calls };
}

function streamResponse(chunks: string[]): () => Response {
  return () => new Response(sseStream(chunks), { status: 200, headers: { "Content-Type": "text/event-stream" } });
}

async function settle() {
  for (let i = 0; i < 10; i++) await Promise.resolve();
  await new Promise((resolve) => setTimeout(resolve, 0));
}

afterEach(() => {
  resetUserEventStreamForTests();
});

describe("parseUserEvent", () => {
  it("accepts a known kind whose frame name matches", () => {
    const event = parseUserEvent("approval.pending", '{"id":"x","kind":"approval.pending","ts":5,"conversationId":"c1"}');
    expect(event).toEqual({ id: "x", kind: "approval.pending", ts: 5, conversationId: "c1" });
  });

  it.each([
    ["not JSON", "approval.pending", "{nope"],
    ["no id", "approval.pending", '{"kind":"approval.pending","ts":1}'],
    ["an unknown kind", "chat.message", '{"id":"x","kind":"chat.message","ts":1}'],
    ["a name that disagrees with the kind", "team.ended", '{"id":"x","kind":"approval.pending","ts":1}'],
    ["no timestamp", "approval.pending", '{"id":"x","kind":"approval.pending"}'],
  ])("rejects %s", (_label, name, data) => {
    expect(parseUserEvent(name, data)).toBeNull();
  });

  it("knows every kind the backend sends", () => {
    // UserEvent.KINDS in the backend; a new kind there needs a line here.
    expect([...USER_EVENT_KINDS].sort()).toEqual(
      [
        "approval.decided",
        "approval.pending",
        "job.failed",
        "job.finished",
        "job.run_completed",
        "notification.created",
        "team.ended",
        "team.started",
        "team.waiting_for_human",
      ].sort(),
    );
  });
});

describe("reconnectDelay", () => {
  it("doubles from the initial delay, capped, with jitter between half and all of it", () => {
    expect(reconnectDelay(0, 1000, 30_000, () => 0)).toBe(500);
    expect(reconnectDelay(0, 1000, 30_000, () => 0.999999)).toBe(1000);
    expect(reconnectDelay(3, 1000, 30_000, () => 0.999999)).toBe(8000);
    expect(reconnectDelay(20, 1000, 30_000, () => 0.999999)).toBe(30_000);
  });
});

describe("createUserEventStream", () => {
  it("sends the app's token, reports open on ready, and dispatches typed events by kind", async () => {
    const { fetch, calls } = fetchSequence([
      streamResponse([ready, frame({ kind: "approval.pending", conversationId: "c1" }), ": keepalive\n\n", frame({ kind: "team.ended", state: "COMPLETED" })]),
    ]);
    const http = new HttpClient({ baseUrl: "https://eddi.example", auth: { getToken: () => "tok" }, fetch });
    const timers = manualTimers();
    const stream = createUserEventStream({ http, timers: timers.timers });
    const statuses: UserEventStreamStatus[] = [];
    stream.onStatus((s) => statuses.push(s));
    const approvals: UserEvent[] = [];
    const all: UserEvent[] = [];
    stream.on("approval.pending", (e) => approvals.push(e));
    stream.on("*", (e) => all.push(e));

    stream.start();
    await settle();

    expect(calls[0]?.url).toBe("https://eddi.example/me/events");
    expect(new Headers(calls[0]?.init?.headers).get("Authorization")).toBe("Bearer tok");
    expect(approvals.map((e) => e.conversationId)).toEqual(["c1"]);
    expect(all.map((e) => e.kind)).toEqual(["approval.pending", "team.ended"]);
    expect(statuses.slice(0, 2)).toEqual(["connecting", "open"]);
    // The stream ended: a reconnect is scheduled, not attempted at once.
    expect(stream.status).toBe("waiting");
    expect(timers.pending).toHaveLength(1);
  });

  it("reconnects after the stream ends, and backs off while connects keep failing", async () => {
    const { fetch, calls } = fetchSequence([
      streamResponse([ready]),
      () => new Response(null, { status: 503 }),
      () => new Response(null, { status: 503 }),
      streamResponse([ready, frame({ kind: "job.finished", conversationId: "c9" })]),
    ]);
    const http = new HttpClient({ fetch });
    const timers = manualTimers();
    const stream = createUserEventStream({ http, timers: timers.timers, random: () => 0.999999 });
    const seen: string[] = [];
    stream.on("job.finished", (e) => seen.push(e.conversationId ?? ""));

    stream.start();
    await settle();
    const delays: number[] = [];
    for (let i = 0; i < 3; i++) {
      delays.push(timers.fireNext()?.ms ?? -1);
      await settle();
    }

    expect(calls).toHaveLength(4);
    expect(delays).toEqual([1000, 2000, 4000]);
    expect(seen).toEqual(["c9"]);
  });

  it("starts over at the initial delay once a connection got through", async () => {
    const { fetch } = fetchSequence([
      () => new Response(null, { status: 503 }),
      streamResponse([ready]),
    ]);
    const timers = manualTimers();
    const stream = createUserEventStream({ http: new HttpClient({ fetch }), timers: timers.timers, random: () => 0.999999 });

    stream.start();
    await settle();
    expect(timers.fireNext()?.ms).toBe(1000);
    await settle();

    expect(timers.pending[0]?.ms).toBe(1000);
  });

  it.each([401, 403, 404])("stops for good on %i", async (status) => {
    const { fetch, calls } = fetchSequence([() => new Response(null, { status })]);
    const timers = manualTimers();
    const stream = createUserEventStream({ http: new HttpClient({ fetch }), timers: timers.timers });

    stream.start();
    await settle();

    expect(stream.status).toBe("stopped");
    expect(timers.pending).toHaveLength(0);
    expect(calls).toHaveLength(1);
  });

  it("refreshes the token once on 401 before giving up", async () => {
    const { fetch, calls } = fetchSequence([() => new Response(null, { status: 401 }), streamResponse([ready])]);
    let token = "old";
    const http = new HttpClient({
      fetch,
      auth: {
        getToken: () => token,
        onUnauthorized: async () => {
          token = "new";
          return true;
        },
      },
    });
    const stream = createUserEventStream({ http, timers: manualTimers().timers });

    stream.start();
    await settle();

    expect(calls).toHaveLength(2);
    expect(new Headers(calls[1]?.init?.headers).get("Authorization")).toBe("Bearer new");
  });

  it("waits at least the server's Retry-After when it is over its connection cap", async () => {
    const { fetch } = fetchSequence([
      () => new Response(JSON.stringify({ error: "too many", code: "user_limit", retryAfterSeconds: 30 }), { status: 429 }),
    ]);
    const timers = manualTimers();
    const stream = createUserEventStream({ http: new HttpClient({ fetch }), timers: timers.timers });

    stream.start();
    await settle();

    expect(stream.status).toBe("waiting");
    expect(timers.pending[0]?.ms).toBe(30_000);
  });

  it("stop() aborts the request, cancels the reconnect and goes idle", async () => {
    const { fetch, calls } = fetchSequence([() => new Response(null, { status: 503 })]);
    const timers = manualTimers();
    const stream = createUserEventStream({ http: new HttpClient({ fetch }), timers: timers.timers });

    stream.start();
    await settle();
    expect(timers.pending).toHaveLength(1);
    stream.stop();

    expect(stream.status).toBe("idle");
    expect(timers.pending).toHaveLength(0);
    expect(calls[0]?.init?.signal?.aborted).toBe(true);
  });

  it("start() on a running stream opens no second connection", async () => {
    const { fetch, calls } = fetchSequence([() => new Response(null, { status: 503 })]);
    const timers = manualTimers();
    const stream = createUserEventStream({ http: new HttpClient({ fetch }), timers: timers.timers });

    stream.start();
    stream.start();
    await settle();
    stream.start();

    expect(calls).toHaveLength(1);
  });

  it("a throwing subscriber neither stops the stream nor the other subscribers", async () => {
    const { fetch } = fetchSequence([streamResponse([ready, frame({ kind: "team.started" }), frame({ kind: "team.ended" })])]);
    const stream = createUserEventStream({ http: new HttpClient({ fetch }), timers: manualTimers().timers });
    const seen: string[] = [];
    stream.on("*", () => {
      throw new Error("boom");
    });
    stream.on("*", (e) => seen.push(e.kind));

    stream.start();
    await settle();

    expect(seen).toEqual(["team.started", "team.ended"]);
  });

  it("an unsubscribed handler hears nothing more", async () => {
    const { fetch } = fetchSequence([streamResponse([ready, frame({ kind: "team.started" })])]);
    const stream = createUserEventStream({ http: new HttpClient({ fetch }), timers: manualTimers().timers });
    const seen: string[] = [];
    const off = stream.on("team.started", (e) => seen.push(e.kind));
    off();

    stream.start();
    await settle();

    expect(seen).toEqual([]);
  });
});

describe("getUserEventStream", () => {
  it("is one stream per tab for the same client", () => {
    const http = new HttpClient({ fetch: vi.fn() });
    expect(getUserEventStream({ http })).toBe(getUserEventStream({ http }));
  });

  it("replaces (and stops) the stream when the app's client changes", () => {
    const first = getUserEventStream({ http: new HttpClient({ fetch: vi.fn() }) });
    const second = getUserEventStream({ http: new HttpClient({ fetch: vi.fn() }) });
    expect(second).not.toBe(first);
    expect(first.status).toBe("idle");
  });
});

describe("the Chat UI widget", () => {
  // It has no signed-in user to notify and is at its bundle budget: nothing of
  // this entry may reach it. Vitest runs from ui/packages/core.
  const chatSrc = join(process.cwd(), "..", "..", "chat", "src");
  const files = (dir: string): string[] =>
    readdirSync(dir).flatMap((name) => {
      const full = join(dir, name);
      return statSync(full).isDirectory() ? files(full) : /\.(ts|tsx)$/.test(name) ? [full] : [];
    });

  it("never imports @eddi/core/user-events", () => {
    const all = files(chatSrc);
    expect(all.length).toBeGreaterThan(10);
    expect(all.filter((f) => /@eddi\/core\/user-events|user-events/.test(readFileSync(f, "utf8")))).toEqual([]);
  });

  it("is not re-exported from the package root", () => {
    const index = readFileSync(join(process.cwd(), "src", "index.ts"), "utf8");
    expect(index).not.toMatch(/user-events/);
  });
});
