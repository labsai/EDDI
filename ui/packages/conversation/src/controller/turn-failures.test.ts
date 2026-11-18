/* ──────────────────────────────────────────────
   The failure taxonomy, shared by the three apps

   One classification in the controller (`turn-failure`): not accepted /
   accepted-but-outcome-unknown / ended / paused / failed / empty. Table-driven
   over the statuses, the stream codes and the stream endings the review named.
   ────────────────────────────────────────────── */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, type ConversationSnapshot, type SSEEvent } from "@eddi/core";
import { AgentConversation, type TurnFailure } from "./agent-conversation";
import { classifyTurnFailure, STREAM_CODE_CLASSES, type TurnFailureClass } from "./turn-failure";
import type { AgentTransport } from "./transport";

const ev = (type: string, data: unknown = ""): SSEEvent =>
  ({ type, data: typeof data === "string" ? data : JSON.stringify(data) }) as SSEEvent;
const reply = (text: string) => ({ output: [{ type: "text", text }] });
const done = (outputs: unknown[], state = "READY", extra: Record<string, unknown> = {}) =>
  ev("done", { conversationState: state, conversationOutputs: outputs, ...extra });

async function* events(...list: SSEEvent[]): AsyncGenerator<SSEEvent> {
  for (const e of list) yield e;
}
// eslint-disable-next-line require-yield
async function* throwing(err: unknown): AsyncGenerator<SSEEvent> {
  throw err;
}

function transport(stream: () => AsyncIterable<SSEEvent>, read?: () => Promise<unknown>): AgentTransport & { read: ReturnType<typeof vi.fn> } {
  return {
    mode: "conversation",
    start: vi.fn(async () => "c1"),
    read: vi.fn(read ?? (async () => ({ conversationState: "READY", conversationOutputs: [] }))),
    stream: vi.fn(stream),
    undo: vi.fn(),
    redo: vi.fn(),
    rerun: vi.fn(),
    cancel: vi.fn(),
  } as unknown as AgentTransport & { read: ReturnType<typeof vi.fn> };
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
  vi.restoreAllMocks();
});

describe("classifyTurnFailure", () => {
  const cases: Array<[string, { status: number | null; code: string | null; partial: boolean }, TurnFailureClass]> = [
    ["413", { status: 413, code: null, partial: false }, "notAccepted"],
    ["404", { status: 404, code: null, partial: false }, "notAccepted"],
    ["403", { status: 403, code: null, partial: false }, "notAccepted"],
    ["401", { status: 401, code: null, partial: false }, "notAccepted"],
    ["429", { status: 429, code: null, partial: false }, "notAccepted"],
    ["400", { status: 400, code: null, partial: false }, "notAccepted"],
    ["409 (refined by a read)", { status: 409, code: null, partial: false }, "notAccepted"],
    ["503", { status: 503, code: null, partial: false }, "notAccepted"],
    ["408", { status: 408, code: null, partial: false }, "unknownOutcome"],
    ["502", { status: 502, code: null, partial: false }, "unknownOutcome"],
    ["504", { status: 504, code: null, partial: false }, "unknownOutcome"],
    ["a dropped connection (no status)", { status: null, code: null, partial: false }, "unknownOutcome"],
    ["status 0 (the client's network error)", { status: 0, code: null, partial: false }, "unknownOutcome"],
    ["410", { status: 410, code: null, partial: false }, "ended"],
    ["500", { status: 500, code: null, partial: false }, "failed"],
    ["501", { status: 501, code: null, partial: false }, "failed"],
    ["code conversation_ended", { status: null, code: "conversation_ended", partial: false }, "ended"],
    ["code awaiting_approval", { status: null, code: "awaiting_approval", partial: false }, "paused"],
    ["code input_too_large", { status: null, code: "input_too_large", partial: false }, "notAccepted"],
    ["code quota_exceeded", { status: null, code: "quota_exceeded", partial: false }, "notAccepted"],
    ["a refusing code after part of the reply", { status: null, code: "input_too_large", partial: true }, "failed"],
    ["an unknown code (a future backend)", { status: null, code: "turn_not_stored", partial: false }, "failed"],
  ];
  it.each(cases)("%s", (_name, signal, expected) => {
    expect(classifyTurnFailure(signal)).toBe(expected);
  });

  it("maps the ended and paused codes in one table, so a new code is added in one place", () => {
    expect(STREAM_CODE_CLASSES).toMatchObject({ conversation_ended: "ended", awaiting_approval: "paused" });
  });
});

describe("a request that fails, by status", () => {
  it.each([401, 403, 404, 413, 429, 503])("%i: not accepted — the message leaves, the draft comes back", async (status) => {
    const c = await opened(transport(() => throwing(new ApiError(status, "x", "t"))));
    const result = await c.send("hello");
    expect(result.outcome).toBe("refused");
    expect(c.getSnapshot().restoreDraft).toBe("hello");
    expect(texts(c)).not.toContain("hello");
  });

  it.each([408, 502, 504])("%i: outcome unknown — the message stays and the notice says it may still be working", async (status) => {
    const c = await opened(transport(() => throwing(new ApiError(status, "x", "t"))));
    const result = await c.send("hello");
    expect(result.outcome).toBe("failed");
    expect(c.getSnapshot().restoreDraft).toBeNull();
    expect(texts(c)).toContain("hello");
    expect(texts(c).slice(-1)[0]).toContain("No answer has arrived yet");
    expect(c.getSnapshot().isProcessing).toBe(false);
  });

  it("410: ended everywhere — the state is ENDED even when a host presents failures", async () => {
    const seen: TurnFailure[] = [];
    const t = transport(() => throwing(new ApiError(410, "gone", "t")), async () => ({ endReason: "AGENT_VERSION_RETIRED" }));
    const c = await opened(t, {
      presentFailure: (failure) => {
        seen.push(failure);
        return false; // the host declines: the controller's own handling
      },
    });
    await c.send("x");
    expect(seen[0]?.class).toBe("ended");
    expect(seen[0]?.endReason).toBe("AGENT_VERSION_RETIRED");
    expect(c.getSnapshot().conversationState).toBe("ENDED");
    expect(c.getSnapshot().endReason).toBe("AGENT_VERSION_RETIRED");
  });

  it("409 is a pause only when the conversation reads AWAITING_HUMAN", async () => {
    const classes: TurnFailureClass[] = [];
    for (const state of ["AWAITING_HUMAN", "IN_PROGRESS", "READY"]) {
      const t = transport(() => throwing(new ApiError(409, "x", "t")), async () => ({ conversationState: state }));
      const c = await opened(t, {
        presentFailure: (failure) => {
          classes.push(failure.class);
          return false;
        },
      });
      await c.send("hello");
    }
    expect(classes).toEqual(["paused", "notAccepted", "notAccepted"]);
  });

  it("500 with nothing arrived: failed — a translated notice and the draft back for a retry of this turn", async () => {
    const seen: TurnFailure[] = [];
    const c = await opened(transport(() => throwing(new ApiError(500, "boom", "t"))), {
      presentFailure: (f) => {
        seen.push(f);
        return false;
      },
    });
    await c.send("hello");
    expect(seen[0]?.class).toBe("failed");
    expect(c.getSnapshot().restoreDraft).toBe("hello");
    expect(texts(c).slice(-1)[0]).toContain("could not be sent");
  });
});

describe("a stream error frame, by code", () => {
  const frame = (code: string | null, message = "x") => ev("error", code ? { message, code } : { message });

  it.each([
    ["conversation_ended", "ended"],
    ["awaiting_approval", "paused"],
    ["input_too_large", "notAccepted"],
    ["quota_exceeded", "notAccepted"],
    [null, "failed"],
    ["a_code_from_a_newer_backend", "failed"],
  ] as const)("code %s is %s, and the host gets that class", async (code, expected) => {
    const seen: TurnFailure[] = [];
    const c = await opened(transport(() => events(frame(code))), {
      presentFailure: (f) => {
        seen.push(f);
        return true;
      },
    });
    await c.send("q");
    expect(seen[0]?.class).toBe(expected);
  });

  it("keeps the whole payload for the host, so a field added later needs no change here", async () => {
    const seen: TurnFailure[] = [];
    const c = await opened(
      transport(() => events(ev("error", { message: "m", code: "x", errorClass: "MODEL", retryable: true }))),
      { presentFailure: (f) => (seen.push(f), true) },
    );
    await c.send("q");
    expect(seen[0]?.payload).toMatchObject({ errorClass: "MODEL", retryable: true });
  });

  it("without a host: an ended code ends the conversation; a refusal withdraws", async () => {
    // The read after the stream agrees: the conversation is over.
    const ended = await opened(transport(() => events(frame("conversation_ended")), async () => ({ conversationState: "READY" })));
    ended.setOptions({ refreshAfterStream: false });
    await ended.send("q");
    expect(ended.getSnapshot().conversationState).toBe("ENDED");
    expect(ended.getSnapshot().restoreDraft).toBe("q");

    const refused = await opened(transport(() => events(frame("input_too_large", "too long"))));
    await refused.send("q");
    expect(refused.getSnapshot().restoreDraft).toBe("q");
    expect(texts(refused).slice(-1)[0]).toContain("too long");
  });
});

describe("a turn that ends without a reply is never silent", () => {
  it("a stream that closes with nothing: a notice that the outcome is unknown, the message stays", async () => {
    const c = await opened(transport(() => events()));
    await c.send("question");
    expect(texts(c)).toContain("question");
    expect(texts(c).slice(-1)[0]).toContain("No answer has arrived yet");
    expect(c.getSnapshot().isProcessing).toBe(false);
  });

  it("`done` with an empty output: says the agent gave no answer", async () => {
    const c = await opened(transport(() => events(done([]))));
    await c.send("question");
    expect(texts(c).slice(-1)[0]).toContain("without giving an answer");
  });

  it("`done` with an empty output after a task_failed: says the turn failed", async () => {
    const c = await opened(transport(() => events(ev("task_failed", { taskId: "t" }), done([]))));
    await c.send("question");
    expect(texts(c).slice(-1)[0]).toContain("could not complete this turn");
  });

  it("`done` that pauses, or ends the conversation, is not 'no answer'", async () => {
    const paused = await opened(transport(() => events(done([], "AWAITING_HUMAN"))));
    await paused.send("q");
    expect(texts(paused).join("|")).not.toContain("without giving an answer");
    const ended = await opened(transport(() => events(done([], "ENDED"))));
    await ended.send("q");
    expect(texts(ended).join("|")).not.toContain("without giving an answer");
  });

  it("a `done` text shorter than what streamed replaces it, and says so", async () => {
    const c = await opened(
      transport(() => events(ev("token", "A long answer that the validator then replaced"), done([reply("Fallback")]))),
    );
    await c.send("q");
    const last = c.getSnapshot().messages.map((m) => m.content);
    expect(last).toContain("Fallback");
    expect(last.slice(-1)[0]).toContain("revised after it streamed");
  });

  it("a `done` text that merely finishes the streamed text adds no notice", async () => {
    const c = await opened(transport(() => events(ev("token", "Hello"), done([reply("Hello world")]))));
    await c.send("q");
    expect(texts(c).join("|")).not.toContain("revised");
  });
});

describe("tolerance for a streaming contract that is still moving", () => {
  it("ignores unknown events and unknown fields on done", async () => {
    const c = await opened(
      transport(() =>
        events(
          ev("a_future_event", { anything: 1 }),
          ev("token", "Hi"),
          done([reply("Hi")], "READY", { finishReason: "STOP", somethingNew: { a: 1 } }),
        ),
      ),
    );
    const result = await c.send("q");
    expect(result.outcome).toBe("sent");
    expect(texts(c)).toContain("Hi");
  });

  it("a stream that ends with an unknown terminal event and nothing else is the unknown-outcome case, not a crash", async () => {
    const c = await opened(transport(() => events(ev("cancelled", { by: "server" }))));
    await c.send("q");
    expect(texts(c).slice(-1)[0]).toContain("No answer has arrived yet");
  });
});

describe("a retry of an unsettled submission", () => {
  it("a replayed busy answer for the same key keeps the message instead of withdrawing it", async () => {
    let call = 0;
    const t = transport(() => {
      call += 1;
      return call === 1 ? throwing(new ApiError(408, "", "t")) : events(done([reply("old")], "IN_PROGRESS"));
    });
    const c = await opened(t);
    await c.send("hello");
    await c.send("hello");
    expect(texts(c).filter((x) => x === "hello")).toHaveLength(2);
    expect(c.getSnapshot().restoreDraft).toBeNull();
    expect(texts(c).slice(-1)[0]).toContain("No answer has arrived yet");
  });
});

// Type-level check that the snapshot type is what the transport helper hands out.
void (null as unknown as ConversationSnapshot);
