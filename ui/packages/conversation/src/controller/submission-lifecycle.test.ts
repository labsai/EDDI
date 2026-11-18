/* ──────────────────────────────────────────────
   When a kept Idempotency-Key may be reused, and what a failure without a status means

   A key is kept while a submission's outcome is unsettled so that a retry runs
   the turn once. It must never survive into a NEW message (a resumed or rewound
   conversation, a long wait, an overlapping send), and a request that cannot have
   reached the server is not "still working".
   ────────────────────────────────────────────── */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, type ConversationSnapshot, type SSEEvent } from "@eddi/core";
import { AgentConversation, SUBMISSION_KEY_MAX_AGE_MS, type TurnFailure } from "./agent-conversation";
import { errorBodyOf, STREAM_CODE_CLASSES } from "./turn-failure";
import type { AgentTransport, TurnOptions } from "./transport";

const ev = (type: string, data: unknown = ""): SSEEvent =>
  ({ type, data: typeof data === "string" ? data : JSON.stringify(data) }) as SSEEvent;
const reply = (text: string) => ({ output: [{ type: "text", text }] });
const done = (outputs: unknown[], state = "READY") => ev("done", { conversationState: state, conversationOutputs: outputs });

async function* events(...list: SSEEvent[]): AsyncGenerator<SSEEvent> {
  for (const e of list) yield e;
}
// eslint-disable-next-line require-yield
async function* throwing(err: unknown): AsyncGenerator<SSEEvent> {
  throw err;
}

function setup(streams: Array<() => AsyncIterable<SSEEvent>>, over: Partial<AgentTransport> = {}) {
  const keys: Array<string | undefined> = [];
  let call = 0;
  const transport = {
    mode: "conversation",
    start: vi.fn(async () => "c1"),
    read: vi.fn(async () => ({ conversationState: "READY", conversationOutputs: [] }) as unknown as ConversationSnapshot),
    stream: vi.fn((_id: string | null, _text: string, o: TurnOptions) => {
      keys.push(o.idempotencyKey);
      return (streams[Math.min(call++, streams.length - 1)] ?? (() => events()))();
    }),
    undo: vi.fn(async () => undefined),
    redo: vi.fn(async () => undefined),
    rerun: vi.fn(),
    cancel: vi.fn(),
    ...over,
  } as unknown as AgentTransport & { read: ReturnType<typeof vi.fn> };
  return { transport, keys };
}
async function opened(t: AgentTransport, options: Partial<ConstructorParameters<typeof AgentConversation>[0]> = {}) {
  const c = new AgentConversation({ transport: t, ...options });
  await c.open(false);
  return c;
}
const texts = (c: AgentConversation) => c.getSnapshot().messages.map((m) => m.content);

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("a kept key never names a NEW message (M2)", () => {
  const unknownThenOk = () => [() => throwing(new ApiError(408, "", "t")), () => events(done([reply("ok")]))];

  it("resume, then the same words: a new key", async () => {
    const { transport, keys } = setup(unknownThenOk());
    const c = await opened(transport);
    await c.send("yes");
    transport.read.mockResolvedValue({ conversationState: "READY", conversationSteps: [], conversationOutputs: [] });
    await c.resume("c1");
    await c.send("yes");
    expect(keys[1]).not.toBe(keys[0]);
  });

  it("undo, then the same words: a new key", async () => {
    const { transport, keys } = setup(unknownThenOk());
    const c = await opened(transport);
    await c.send("yes");
    await c.undo();
    await c.send("yes");
    expect(keys[1]).not.toBe(keys[0]);
  });

  it("open (a new start) and a rerun also end the pending submission", async () => {
    for (const act of [(c: AgentConversation) => c.open(true), (c: AgentConversation) => c.retry()]) {
      const { transport, keys } = setup(unknownThenOk());
      const c = await opened(transport);
      await c.send("yes");
      await act(c);
      await c.send("yes");
      expect(keys[1]).not.toBe(keys[0]);
    }
  });

  it("the same words sent straight away still reuse the key (the retry it exists for)", async () => {
    const { transport, keys } = setup(unknownThenOk());
    const c = await opened(transport);
    await c.send("yes");
    await c.send("yes");
    expect(keys[1]).toBe(keys[0]);
  });

  it("past the age limit the key is not reused", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    const { transport, keys } = setup(unknownThenOk());
    const c = await opened(transport);
    await c.send("yes");
    vi.setSystemTime(Date.now() + SUBMISSION_KEY_MAX_AGE_MS + 1);
    await c.send("yes");
    expect(keys[1]).not.toBe(keys[0]);
  });

  it("just inside the age limit it still is, and the limit sits below the backend's 600 s default", async () => {
    expect(SUBMISSION_KEY_MAX_AGE_MS).toBeLessThan(600_000);
    vi.useFakeTimers({ toFake: ["Date"] });
    const { transport, keys } = setup(unknownThenOk());
    const c = await opened(transport);
    await c.send("yes");
    vi.setSystemTime(Date.now() + SUBMISSION_KEY_MAX_AGE_MS - 1000);
    await c.send("yes");
    expect(keys[1]).toBe(keys[0]);
  });

  it("an unknown-outcome notice past the limit makes no safety promise", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    let first = true;
    const { transport } = setup([
      () => {
        if (first) {
          first = false;
          vi.setSystemTime(Date.now() + SUBMISSION_KEY_MAX_AGE_MS + 1000); // a very long turn
        }
        return throwing(new ApiError(504, "", "t"));
      },
    ]);
    const c = await opened(transport);
    await c.send("yes");
    const last = texts(c).slice(-1)[0] ?? "";
    expect(last).toContain("already have answered it");
    expect(last).not.toContain("normally will not");
  });
});

describe("overlapping sends keep their own keys (M3)", () => {
  it("a first send finishing its post-stream read after a second started does not take the second's key", async () => {
    let releaseRead!: () => void;
    const slowRead = new Promise<void>((resolve) => (releaseRead = resolve));
    let reads = 0;
    const { transport, keys } = setup(
      [
        () => events(done([reply("one")])),
        () => throwing(new ApiError(408, "", "t")), // the second turn: outcome unknown
        () => events(done([reply("two")])),
      ],
      {
        // Only the first turn's post-stream read is slow.
        read: vi.fn(async () => {
          reads += 1;
          if (reads === 2) await slowRead;
          return { conversationState: "READY", conversationOutputs: [] } as unknown as ConversationSnapshot;
        }),
      },
    );
    const c = await opened(transport);
    const first = c.send("first");
    // `done` has lowered isProcessing; the read after it is still pending.
    await vi.waitFor(() => expect(c.getSnapshot().isProcessing).toBe(false));
    await c.send("second"); // ends unknown-outcome and keeps ITS key
    releaseRead();
    await first; // the first send's finally must not null the second's submission
    await c.send("second");
    expect(keys).toHaveLength(3);
    expect(keys[2]).toBe(keys[1]);
    expect(keys[1]).not.toBe(keys[0]);
  });
});

describe("a failure without a status", () => {
  const fail = (err: unknown, over: Partial<AgentTransport> = {}) => setup([() => throwing(err)], over);

  it("offline: not sent — the draft comes back", async () => {
    vi.stubGlobal("navigator", { onLine: false });
    const { transport } = fail(new TypeError("Failed to fetch"));
    const c = await opened(transport);
    await c.send("hello");
    expect(c.getSnapshot().restoreDraft).toBe("hello");
    expect(texts(c)).not.toContain("hello");
  });

  it("cross-origin with no answer at all (a refused preflight): not sent", async () => {
    const { transport } = fail(new TypeError("Failed to fetch"), { crossOrigin: () => true });
    const c = await opened(transport);
    const result = await c.send("hello");
    expect(result.outcome).toBe("refused");
    expect(c.getSnapshot().restoreDraft).toBe("hello");
  });

  it("same-origin network failure while online: outcome unknown, the message stays", async () => {
    const { transport } = fail(new TypeError("Failed to fetch"), { crossOrigin: () => false });
    const c = await opened(transport);
    await c.send("hello");
    expect(texts(c)).toContain("hello");
    expect(texts(c).slice(-1)[0]).toContain("No answer has arrived yet");
  });

  it("a plain Error from client code is a bug: failed, not 'still working'", async () => {
    const seen: TurnFailure[] = [];
    const { transport } = fail(new Error("undefined is not a function"));
    const c = await opened(transport, { presentFailure: (f) => (seen.push(f), false) });
    await c.send("hello");
    expect(seen[0]?.class).toBe("failed");
    expect(texts(c).join("|")).not.toContain("No answer has arrived yet");
  });
});

describe("a 409 on a retry whose first attempt still runs (S2, non-streaming too)", () => {
  it("keeps the message instead of withdrawing it", async () => {
    let call = 0;
    const say = vi.fn(async () => {
      call += 1;
      if (call === 1) throw new ApiError(408, "", "t");
      throw new ApiError(409, "busy", "t");
    });
    const { transport } = setup([], {
      stream: undefined,
      say,
      read: vi.fn(async () => ({ conversationState: "IN_PROGRESS", conversationOutputs: [] }) as unknown as ConversationSnapshot),
    });
    const c = await opened(transport, { streaming: false });
    await c.send("hello");
    await c.send("hello");
    expect(texts(c).filter((x) => x === "hello")).toHaveLength(2);
    expect(c.getSnapshot().restoreDraft).toBeNull();
    expect(texts(c).slice(-1)[0]).toContain("No answer has arrived yet");
  });
});

describe("the turn ran but was not saved (S3)", () => {
  it("maps the stream code, in the one table", () => {
    expect(STREAM_CODE_CLASSES.turn_not_stored).toBe("notStored");
  });

  it("reads code, retryable and reason from both error-body shapes", () => {
    expect(errorBodyOf({ body: JSON.stringify({ error: { code: "TURN_NOT_STORED", reason: "too_large", retryable: false } }) })).toEqual({
      code: "TURN_NOT_STORED",
      retryable: false,
      reason: "too_large",
    });
    expect(errorBodyOf({ body: { error: "x", message: "m", code: "turn_not_stored" } }).code).toBe("turn_not_stored");
    expect(errorBodyOf(new Error("no body"))).toEqual({ code: null, retryable: null, reason: null });
  });

  it.each([409, 422, 503, 507])("HTTP %i with TURN_NOT_STORED: the message stays, the notice says the reply was not saved", async (status) => {
    const err = Object.assign(new ApiError(status, "", "t"), {
      body: JSON.stringify({ error: { code: "TURN_NOT_STORED", retryable: status !== 507 } }),
    });
    const seen: TurnFailure[] = [];
    const { transport } = setup([() => throwing(err)]);
    const c = await opened(transport, { presentFailure: (f) => (seen.push(f), false) });
    await c.send("hello");
    expect(seen[0]?.class).toBe("notStored");
    expect(c.getSnapshot().restoreDraft).toBeNull();
    expect(texts(c)).toContain("hello");
    const notice = texts(c).slice(-1)[0] ?? "";
    expect(notice).toContain("could not be saved");
    expect(notice.includes("new conversation")).toBe(status === 507);
    expect(notice.includes("runs the turn again")).toBe(status !== 507);
  });

  it("stream error `turn_not_stored`: the same, and an unknown code stays what it was", async () => {
    const frame = (code: string, retryable = true) => ev("error", { message: "m", code, reason: "x", retryable });
    const { transport } = setup([() => events(frame("turn_not_stored"))]);
    const c = await opened(transport);
    await c.send("hello");
    expect(texts(c)).toContain("hello");
    expect(texts(c).slice(-1)[0]).toContain("could not be saved");

    const seen: TurnFailure[] = [];
    const { transport: t2 } = setup([() => events(frame("a_future_code"))]);
    const d = await opened(t2, { presentFailure: (f) => (seen.push(f), true) });
    await d.send("q");
    expect(seen[0]?.class).toBe("failed");
  });
});

describe("no notice for a turn that did produce something (S4)", () => {
  it.each([
    ["quick replies only", [{ output: [], quickReplies: [{ value: "Yes" }] }]],
    ["a button", [{ output: [{ type: "button", label: "Go", onPress: "x" }] }]],
    ["an image", [{ output: [{ type: "image", uri: "https://example.test/a.png" }] }]],
  ])("%s is a reply, not 'no answer'", async (_name, outputs) => {
    const { transport } = setup([() => events(done(outputs))]);
    const c = await opened(transport);
    await c.send("q");
    expect(texts(c).join("|")).not.toContain("without giving an answer");
  });

  it("a truly empty turn still says so", async () => {
    const { transport } = setup([() => events(done([]))]);
    const c = await opened(transport);
    await c.send("q");
    expect(texts(c).slice(-1)[0]).toContain("without giving an answer");
  });

  it("a final text that is the streamed one without its reasoning block is not 'revised'", async () => {
    const { transport } = setup([
      () => events(ev("token", "<think>working it out</think>The answer is 42, as asked."), done([reply("The answer is 42")])),
    ]);
    const c = await opened(transport);
    await c.send("q");
    expect(texts(c).join("|")).not.toContain("differs from the preview");
  });

  it("a different, shorter final text is noted neutrally", async () => {
    const { transport } = setup([() => events(ev("token", "A long answer that the validator replaced"), done([reply("Fallback")]))]);
    const c = await opened(transport);
    await c.send("q");
    expect(texts(c).slice(-1)[0]).toContain("differs from the preview");
  });
});
