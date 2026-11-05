/* ──────────────────────────────────────────────
   AgentConversation, without React

   The union of the turn rules the 1:1 loops pinned in their own suites — the
   Chat UI's ChatWidget and chat-store tests and the Manager's use-chat tests —
   driven through a fake transport. Each case names the rule; the protocol
   behind it is in @eddi/core's README.
   ────────────────────────────────────────────── */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, type ConversationSnapshot, type SSEEvent } from "@eddi/core";
import { AgentConversation, SECRET_BUBBLE } from "./agent-conversation";
import { initialConversationState } from "./state";
import type { AgentTransport } from "./transport";

type Snap = Partial<ConversationSnapshot>;

const ev = (type: SSEEvent["type"], data: unknown = ""): SSEEvent => ({
  type,
  data: typeof data === "string" ? data : JSON.stringify(data),
});

const done = (snapshot: Snap) => ev("done", snapshot);

const output = (text: string, extra: Record<string, unknown> = {}) => ({
  output: [{ type: "text", text }],
  ...extra,
});

async function* events(...list: SSEEvent[]): AsyncGenerator<SSEEvent> {
  for (const e of list) yield e;
}

function fakeTransport(over: Partial<AgentTransport> = {}): AgentTransport & {
  read: ReturnType<typeof vi.fn>;
} {
  return {
    mode: "conversation",
    start: vi.fn(async () => "c1"),
    read: vi.fn(async () => ({ conversationState: "READY", conversationOutputs: [] }) as unknown as ConversationSnapshot),
    stream: vi.fn(() => events(done({ conversationState: "READY", conversationOutputs: [output("hi")] }))),
    say: vi.fn(async () => ({ conversationState: "READY", conversationOutputs: [output("hi")] })),
    undo: vi.fn(async () => undefined),
    redo: vi.fn(async () => undefined),
    rerun: vi.fn(async () => undefined),
    cancel: vi.fn(async () => undefined),
    ...over,
  } as AgentTransport & { read: ReturnType<typeof vi.fn> };
}

async function opened(transport: AgentTransport, options: Partial<ConstructorParameters<typeof AgentConversation>[0]> = {}) {
  const c = new AgentConversation({ transport, ...options });
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

describe("store", () => {
  it("notifies subscribers on change only, and stops after unsubscribing", () => {
    const c = new AgentConversation({ transport: fakeTransport() });
    const listener = vi.fn();
    const off = c.subscribe(listener);
    c.dispatch({ type: "SET_THINKING", value: false }); // unchanged
    expect(listener).not.toHaveBeenCalled();
    c.dispatch({ type: "SET_THINKING", value: true });
    expect(listener).toHaveBeenCalledTimes(1);
    off();
    c.dispatch({ type: "SET_THINKING", value: false });
    expect(listener).toHaveBeenCalledTimes(1);
  });
});

describe("open", () => {
  it("starts, reads the welcome and offers its quick replies and requested field", async () => {
    const transport = fakeTransport({
      read: vi.fn(async () => ({
        conversationState: "READY",
        undoAvailable: false,
        conversationOutputs: [
          {
            output: [{ type: "text", text: "Welcome" }, { type: "inputField", subType: "password", label: "Key" }],
            quickReplies: [{ value: "Go" }],
          },
        ],
      })) as never,
    });
    const c = await opened(transport);
    const s = c.getSnapshot();
    expect(s.conversationId).toBe("c1");
    expect(texts(c)).toEqual(["Welcome"]);
    expect(s.messages[0]?.stepIndex).toBe(0);
    expect(s.quickReplies).toEqual([{ value: "Go" }]);
    expect(s.activeInputField?.subType).toBe("password");
  });

  it("says why a start failed and offers a retry", async () => {
    const c = await opened(fakeTransport({ start: vi.fn(async () => Promise.reject(new ApiError(404, "nope", "test"))) }), {
      environment: "test",
    });
    expect(c.getSnapshot().startFailed).toBe(true);
    expect(texts(c)[0]).toContain('"test" environment');
    expect(c.getSnapshot().messages[0]?.kind).toBe("notice");
  });

  it("drops an open that New conversation overtook", async () => {
    let release!: () => void;
    const transport = fakeTransport({
      start: vi.fn(() => new Promise<string>((r) => (release = () => r("old")))),
    });
    const c = new AgentConversation({ transport });
    const first = c.open(false);
    c.reset();
    release();
    await first;
    expect(c.getSnapshot().conversationId).toBeNull();
  });

  it("opens a managed conversation from its snapshot and learns its agent", async () => {
    const transport: AgentTransport = {
      ...fakeTransport(),
      mode: "managed",
      openManaged: vi.fn(async () => ({
        conversationId: "m1",
        agentId: "agent-9",
        conversationState: "READY",
        conversationSteps: [],
        conversationOutputs: [output("Hello", { quickReplies: [{ value: "A" }] })],
      })) as never,
    };
    const c = await opened(transport);
    expect(c.getSnapshot().conversationId).toBe("m1");
    expect(c.getSnapshot().openedAgentId).toBe("agent-9");
    expect(c.getSnapshot().quickReplies).toEqual([{ value: "A" }]);
  });
});

describe("resume", () => {
  // The Manager's ChatPanel lost the last reply's quick replies on a resumed
  // conversation: they appeared only after the next turn.
  it("restores the last output's quick replies when a conversation is resumed", async () => {
    const transport = fakeTransport({
      read: vi.fn(async () => ({
        conversationState: "READY",
        conversationSteps: [{}, {}],
        conversationOutputs: [output("Hi"), output("Pick one", { quickReplies: [{ value: "Red" }, { value: "Blue" }] })],
      })) as never,
    });
    const c = new AgentConversation({ transport });
    expect(await c.resume("c7")).toBe(true);
    expect(c.getSnapshot().conversationId).toBe("c7");
    expect(c.getSnapshot().quickReplies).toEqual([{ value: "Red" }, { value: "Blue" }]);
  });

  it("restores a pause and the open input-field request on resume", async () => {
    const transport = fakeTransport({
      read: vi.fn(async () => ({
        conversationState: "AWAITING_HUMAN",
        conversationOutputs: [{ output: [{ type: "inputField", subType: "password" }] }],
      })) as never,
    });
    const c = new AgentConversation({ transport });
    await c.resume("c7");
    expect(c.getSnapshot().conversationState).toBe("AWAITING_HUMAN");
    expect(c.getSnapshot().activeInputField?.subType).toBe("password");
  });

  it("keeps the transcript on screen when the resume read fails", async () => {
    const c = await opened(fakeTransport());
    c.dispatch({ type: "ADD_MESSAGE", message: { id: "x", role: "user", content: "kept", timestamp: 0 } });
    c.setOptions({ transport: fakeTransport({ read: vi.fn(async () => Promise.reject(new Error("down"))) as never }) });
    expect(await c.resume("c9")).toBe(false);
    expect(texts(c)).toContain("kept");
  });
});

describe("send — streaming", () => {
  it("streams tokens into one bubble and reconciles with the snapshot", async () => {
    const transport = fakeTransport({
      stream: vi.fn(() =>
        events(
          ev("task_start", { taskId: "llm" }),
          ev("token", "Hel"),
          ev("token", "lo"),
          done({ conversationState: "READY", conversationOutputs: [output("Hello!", { quickReplies: [{ value: "Q" }] })] }),
        ),
      ),
      read: vi.fn(async () => ({ conversationState: "READY", undoAvailable: true, redoAvailable: false })) as never,
    });
    const c = await opened(transport);
    await c.send("hi");
    const s = c.getSnapshot();
    expect(texts(c)).toEqual(["hi", "Hello!"]);
    expect(s.messages[1]?.isStreaming).toBe(false);
    expect(s.quickReplies).toEqual([{ value: "Q" }]);
    expect(s.undoAvailable).toBe(true);
    expect(s.isProcessing).toBe(false);
    expect(s.isThinking).toBe(false);
  });

  it("applies an inputField that arrives on `done`", async () => {
    const transport = fakeTransport({
      stream: vi.fn(() =>
        events(done({ conversationState: "READY", conversationOutputs: [{ output: [{ type: "inputField", subType: "password" }] }] })),
      ),
    });
    const c = await opened(transport);
    await c.send("my key?");
    expect(c.getSnapshot().activeInputField?.subType).toBe("password");
  });

  it("shows the tool in use until the next token, and escalation until text flows", async () => {
    const seen: Array<[string | null, boolean]> = [];
    const transport = fakeTransport({
      stream: vi.fn(() =>
        events(ev("tool_call", { tool: "calculator" }), ev("cascade_escalation", {}), ev("token", "x"), done({ conversationState: "READY", conversationOutputs: [output("x")] })),
      ),
    });
    const c = await opened(transport);
    c.subscribe(() => seen.push([c.getSnapshot().activeTool, c.getSnapshot().isEscalating]));
    await c.send("calc");
    expect(seen).toContainEqual(["calculator", false]);
    expect(seen).toContainEqual([null, true]);
    expect(c.getSnapshot().activeTool).toBeNull();
    expect(c.getSnapshot().isEscalating).toBe(false);
  });

  it("records a guardrail on the reply it explains", async () => {
    const transport = fakeTransport({
      stream: vi.fn(() =>
        events(ev("guardrail_triggered", { type: "pii", phase: "output", action: "block" }), done({ conversationState: "READY", conversationOutputs: [output("Blocked.")] })),
      ),
    });
    const c = await opened(transport);
    await c.send("x");
    expect(c.getSnapshot().messages.slice(-1)[0]?.guardrails).toEqual([{ type: "pii", phase: "output", action: "block" }]);
  });

  it("withdraws a turn the server dropped into a paused conversation and hands the text back", async () => {
    const transport = fakeTransport({
      read: vi.fn(async () => ({ conversationState: "AWAITING_HUMAN", conversationOutputs: [output("Wait")] })) as never,
      stream: vi.fn(() => events(done({ conversationState: "AWAITING_HUMAN", conversationOutputs: [output("Wait")] }))),
    });
    const c = await opened(transport);
    await c.send("are you there?");
    const s = c.getSnapshot();
    expect(texts(c)).not.toContain("are you there?");
    expect(s.restoreDraft).toBe("are you there?");
    expect(s.messages.slice(-1)[0]?.kind).toBe("notice");
  });

  it("keeps a turn that PAUSED and shows its placeholder, without stale quick replies", async () => {
    const transport = fakeTransport({
      stream: vi.fn(() =>
        events(done({ conversationState: "AWAITING_HUMAN", conversationOutputs: [{ output: ["Awaiting approval"] as never, quickReplies: [{ value: "old" }] }] })),
      ),
      read: vi.fn(async () => ({ conversationState: "READY" })) as never,
    });
    const c = await opened(transport);
    await c.send("delete it");
    const s = c.getSnapshot();
    expect(texts(c)).toEqual(["delete it", "Awaiting approval"]);
    expect(s.quickReplies).toEqual([]);
    // The READY read straight after the pause was announced does not take it back.
    expect(s.conversationState).toBe("AWAITING_HUMAN");
  });

  it("withdraws a turn refused by an unconsumed stream error code", async () => {
    const transport = fakeTransport({
      stream: vi.fn(() => events(ev("error", { message: "ended", code: "conversation_ended" }))),
    });
    const c = await opened(transport);
    await c.send("hello");
    expect(c.getSnapshot().restoreDraft).toBe("hello");
    expect(texts(c).slice(-1)[0]).toContain("ended");
  });

  it("keeps a partial reply and appends the error of a turn that failed mid-way", async () => {
    const transport = fakeTransport({
      stream: vi.fn(() => events(ev("token", "Part"), ev("error", { message: "boom" }))),
    });
    const c = await opened(transport);
    await c.send("go");
    expect(texts(c).slice(-1)[0]).toBe("Part\n\n⚠️ boom");
  });

  it("re-reads the conversation when `done` is unreadable and applies that instead", async () => {
    const transport = fakeTransport({ stream: vi.fn(() => events(ev("done", "{not json"))) });
    const c = await opened(transport);
    transport.read.mockResolvedValue({
      conversationState: "READY",
      conversationOutputs: [output("From the read", { quickReplies: [{ value: "Q2" }] })],
    });
    await c.send("x");
    expect(texts(c)).toEqual(["x", "From the read"]);
    expect(c.getSnapshot().quickReplies).toEqual([{ value: "Q2" }]);
  });

  it("sends secret input masked, flagged and never in clear", async () => {
    const stream = vi.fn(() => events(done({ conversationState: "READY", conversationOutputs: [output("ok")] })));
    const c = await opened(fakeTransport({ stream }));
    await c.send("s3cret", true);
    expect(texts(c)[0]).toBe(SECRET_BUBBLE);
    expect(stream).toHaveBeenCalledWith("c1", "s3cret", expect.objectContaining({ context: { secretInput: { type: "string", value: "true" } } }));
  });

  it("forwards staged attachments as attachment_N context and names them in the bubble", async () => {
    const stream = vi.fn(() => events(done({ conversationState: "READY", conversationOutputs: [output("ok")] })));
    const c = await opened(fakeTransport({ stream }));
    c.dispatch({
      type: "ADD_ATTACHMENT",
      attachment: { storageRef: "ref-1", fileName: "a.pdf", mimeType: "application/pdf", sizeBytes: 3, forwardableInline: true } as never,
    });
    await c.send("read this");
    const call = stream.mock.calls[0] as unknown as [string, string, { context: Record<string, unknown> }];
    expect(Object.keys(call[2].context)).toEqual(["attachment_0"]);
    expect(texts(c)[0]).toBe("📎 a.pdf\n\nread this");
    expect(c.getSnapshot().pendingAttachments).toEqual([]);
  });

  it("ignores a second send while one is in flight", async () => {
    let finish!: () => void;
    const stream = vi.fn(async function* () {
      await new Promise<void>((r) => (finish = r));
      yield done({ conversationState: "READY", conversationOutputs: [output("ok")] });
    });
    const c = await opened(fakeTransport({ stream }));
    const first = c.send("one");
    await c.send("two");
    finish();
    await first;
    expect(stream).toHaveBeenCalledTimes(1);
  });

  it("sends nothing while the host holds sends (canSend)", async () => {
    const stream = vi.fn();
    const c = await opened(fakeTransport({ stream }), { canSend: () => false });
    await c.send("x");
    expect(stream).not.toHaveBeenCalled();
    expect(c.getSnapshot().messages).toEqual([]);
  });

  it("writes nothing into a conversation that replaced the one a stream belonged to", async () => {
    let finish!: () => void;
    let started!: () => void;
    const streaming = new Promise<void>((r) => (started = r));
    const stream = vi.fn(async function* () {
      yield ev("token", "old ");
      started();
      await new Promise<void>((r) => (finish = r));
      yield ev("token", "reply");
    });
    const c = await opened(fakeTransport({ stream }));
    const turn = c.send("x");
    await streaming;
    c.reset();
    finish();
    await turn;
    expect(c.getSnapshot().messages).toEqual([]);
    expect(c.getSnapshot().isProcessing).toBe(false);
  });
});

describe("send — refusals and failures", () => {
  const failing = (err: unknown) =>
    fakeTransport({
      stream: vi.fn(async function* () {
        yield* [];
        throw err;
      }),
    });

  it("409: withdraws the bubble, restores the draft and re-reads the paused state", async () => {
    const transport = failing(new ApiError(409, "busy", "test"));
    const c = await opened(transport);
    transport.read.mockResolvedValue({ conversationState: "AWAITING_HUMAN" });
    await c.send("draft text");
    const s = c.getSnapshot();
    expect(texts(c)).not.toContain("draft text");
    expect(s.restoreDraft).toBe("draft text");
    expect(s.conversationState).toBe("AWAITING_HUMAN");
  });

  it("409 on a secret turn puts it back masked, with secret mode on", async () => {
    const c = await opened(failing(new ApiError(409, "busy", "test")));
    await c.send("key", true);
    expect(c.getSnapshot().restoreDraft).toBe("key");
    expect(c.getSnapshot().isSecretMode).toBe(true);
  });

  it("409 hands staged attachments back", async () => {
    const c = await opened(failing(new ApiError(409, "busy", "test")));
    const attachment = { storageRef: "r", fileName: "f.txt", forwardableInline: true } as never;
    c.dispatch({ type: "ADD_ATTACHMENT", attachment });
    await c.send("x");
    expect(c.getSnapshot().pendingAttachments).toEqual([attachment]);
  });

  it("410: marks the conversation ended with the reason the read gives", async () => {
    const transport = failing(new ApiError(410, "gone", "test"));
    const c = await opened(transport);
    transport.read.mockResolvedValue({ endReason: "AGENT_VERSION_RETIRED" });
    await c.send("x");
    expect(c.getSnapshot().conversationState).toBe("ENDED");
    expect(c.getSnapshot().endReason).toBe("AGENT_VERSION_RETIRED");
  });

  it.each([
    [401, "session has expired"],
    [403, "not allowed"],
    [413, "too large"],
    [429, "usage limit"],
  ])("%i: withdraws with its own copy", async (status, phrase) => {
    const c = await opened(failing(new ApiError(status, "x", "test")));
    await c.send("hello");
    expect(c.getSnapshot().restoreDraft).toBe("hello");
    expect(texts(c).slice(-1)[0]).toContain(phrase);
  });

  it("a dropped connection before any token withdraws; after one it keeps the partial reply", async () => {
    const c = await opened(failing(new TypeError("network")));
    await c.send("one");
    expect(c.getSnapshot().restoreDraft).toBe("one");

    const partial = fakeTransport({
      stream: vi.fn(async function* () {
        yield ev("token", "half");
        throw new TypeError("network");
      }),
    });
    const d = await opened(partial);
    await d.send("two");
    expect(texts(d).slice(-1)[0]).toContain("half\n\n⚠️ Connection lost");
    expect(d.getSnapshot().restoreDraft).toBeNull();
  });
});

describe("send — reconnecting after a dropped stream", () => {
  const dropping = () =>
    fakeTransport({
      stream: vi.fn(async function* () {
        yield ev("token", "Part of");
        throw new TypeError("network");
      }),
    });

  it("follows the turn until it settles and shows its reply in place of the partial one", async () => {
    const transport = dropping();
    const c = await opened(transport, { reconnectDelaysMs: [0, 0, 0, 0] });
    transport.read
      .mockReset()
      .mockRejectedValueOnce(new TypeError("still offline"))
      .mockResolvedValueOnce({ conversationState: "IN_PROGRESS" })
      .mockResolvedValueOnce({ conversationState: "READY", conversationOutputs: [output("Part of the answer", { quickReplies: [{ value: "More" }] })] });
    const seen: boolean[] = [];
    c.subscribe(() => seen.push(c.getSnapshot().reconnecting));
    const result = await c.send("question");
    expect(result).toEqual({ outcome: "sent" });
    expect(seen).toContain(true);
    expect(c.getSnapshot().reconnecting).toBe(false);
    expect(transport.read).toHaveBeenCalledTimes(3);
    expect(texts(c)).toEqual(["question", "Part of the answer"]);
    expect(c.getSnapshot().quickReplies).toEqual([{ value: "More" }]);
    expect(c.getSnapshot().isProcessing).toBe(false);
  });

  it("calls the connection lost when the turn never settles", async () => {
    const transport = dropping();
    const c = await opened(transport, { reconnectDelaysMs: [0, 0] });
    transport.read.mockResolvedValue({ conversationState: "IN_PROGRESS" });
    await c.send("question");
    expect(texts(c).slice(-1)[0]).toContain("Part of\n\n⚠️ Connection lost");
    expect(c.getSnapshot().reconnecting).toBe(false);
  });

  it("does not reconnect without the option, or before any of the reply arrived", async () => {
    const transport = dropping();
    const c = await opened(transport);
    transport.read.mockClear();
    transport.read.mockResolvedValue({ conversationState: "IN_PROGRESS" });
    await c.send("question");
    expect(c.getSnapshot().reconnecting).toBe(false);
    expect(transport.read).toHaveBeenCalledTimes(1); // the existing re-read for the state
  });
});

describe("send — snapshot transports", () => {
  it("non-streaming: applies the reply snapshot", async () => {
    const say = vi.fn(async () => ({ conversationState: "READY" as const, conversationOutputs: [output("Plain", { quickReplies: [{ value: "Q" }] })] }));
    const c = await opened(fakeTransport({ say }), { streaming: false });
    await c.send("hi");
    expect(texts(c)).toEqual(["hi", "Plain"]);
    expect(c.getSnapshot().quickReplies).toEqual([{ value: "Q" }]);
  });

  it("local: streams scripted events and ends with its scripted quick replies", async () => {
    const transport: AgentTransport = {
      mode: "local",
      welcome: async () => ({ conversationId: "demo", message: { id: "w", role: "agent", content: "Hi", timestamp: 0 }, quickReplies: [] }),
      stream: () => events(ev("token", "Demo"), done({ conversationState: "READY", conversationOutputs: [output("Demo")] })),
      quickRepliesAfter: () => [{ value: "More" }],
      read: vi.fn(),
      undo: vi.fn(),
      redo: vi.fn(),
      rerun: vi.fn(),
      cancel: vi.fn(),
    };
    const c = await opened(transport);
    await c.send("x");
    expect(texts(c)).toEqual(["Hi", "x", "Demo"]);
    expect(c.getSnapshot().quickReplies).toEqual([{ value: "More" }]);
    await c.undo();
    expect(transport.undo).not.toHaveBeenCalled();
  });
});

describe("undo, redo, retry", () => {
  const history = {
    conversationState: "READY",
    undoAvailable: false,
    redoAvailable: true,
    conversationSteps: [{ conversationStep: [{ key: "output:text:0", value: "Welcome" }] }],
    conversationOutputs: [output("Welcome", { quickReplies: [{ value: "W" }] })],
  };

  it("undo rebuilds the transcript, the flags and the quick replies from a re-read", async () => {
    const transport = fakeTransport();
    const c = await opened(transport);
    c.dispatch({ type: "ADD_MESSAGE", message: { id: "u", role: "user", content: "gone", timestamp: 0 } });
    transport.read.mockResolvedValue(history);
    await c.undo();
    expect(transport.undo).toHaveBeenCalledWith("c1");
    expect(texts(c)).toEqual(["Welcome"]);
    expect(c.getSnapshot().redoAvailable).toBe(true);
    expect(c.getSnapshot().quickReplies).toEqual([{ value: "W" }]);
    expect(c.getSnapshot().historyBusy).toBe(false);
  });

  it("409 on redo says it is not possible right now", async () => {
    const c = await opened(fakeTransport({ redo: vi.fn(async () => Promise.reject(new ApiError(409, "no", "test"))) }));
    await c.redo();
    expect(texts(c).slice(-1)[0]).toContain("Redo is not possible right now");
  });

  it("retry reruns, re-reads without duplicating the shown reply, and forgets the old rating", async () => {
    const onStepRerun = vi.fn();
    const transport = fakeTransport();
    const c = await opened(transport, { onStepRerun });
    c.dispatch({ type: "ADD_MESSAGE", message: { id: "a", role: "agent", content: "Same", timestamp: 0, stepIndex: 1 } });
    transport.read.mockResolvedValue({ conversationState: "READY", conversationOutputs: [output("Same")] });
    await c.retry();
    expect(transport.rerun).toHaveBeenCalledWith("c1");
    expect(onStepRerun).toHaveBeenCalledWith(1);
    expect(texts(c).filter((t) => t === "Same")).toHaveLength(1);
  });
});

describe("pause, cancel, stop", () => {
  it("cancel interrupts the paused turn and says so", async () => {
    const c = await opened(fakeTransport());
    c.setApprovalStatus({ conversationId: "c1" } as never);
    await c.cancel();
    expect(c.getSnapshot().conversationState).toBe("EXECUTION_INTERRUPTED");
    expect(c.getSnapshot().approvalStatus).toBeNull();
    expect(texts(c).slice(-1)[0]).toBe("This request was cancelled.");
  });

  it("stop aborts the read, drops an empty bubble and marks the conversation interrupted", async () => {
    let signal!: AbortSignal;
    const stream = vi.fn((_id: string | null, _t: string, o: { signal: AbortSignal }) => {
      signal = o.signal;
      return (async function* () {
        await new Promise((_, reject) => o.signal.addEventListener("abort", () => reject(new DOMException("a", "AbortError"))));
        yield ev("token", "never");
      })();
    });
    const transport = fakeTransport({ stream });
    const c = await opened(transport);
    const turn = c.send("long");
    await Promise.resolve();
    await c.stop();
    await turn;
    expect(signal.aborted).toBe(true);
    expect(transport.cancel).toHaveBeenCalledWith("c1");
    expect(c.getSnapshot().messages.map((m) => m.content)).toEqual(["long"]);
    expect(c.getSnapshot().conversationState).toBe("EXECUTION_INTERRUPTED");
  });

  describe("whether Stop took (a cluster can run the turn on another pod)", () => {
    /** A turn that streams one token and then waits until it is aborted. */
    function stoppable(over: Partial<AgentTransport> = {}) {
      const stream = vi.fn((_id: string | null, _t: string, o: { signal: AbortSignal }) =>
        (async function* () {
          yield ev("token", "Half a re");
          await new Promise((_, reject) => o.signal.addEventListener("abort", () => reject(new DOMException("a", "AbortError"))));
        })(),
      );
      return fakeTransport({ stream, ...over });
    }

    async function stopMidTurn(transport: ReturnType<typeof stoppable>, reads: (read: ReturnType<typeof vi.fn>) => void = () => {}) {
      const c = await opened(transport, { stopVerifyDelaysMs: [0, 0] });
      transport.read.mockReset();
      reads(transport.read);
      const turn = c.send("long");
      await vi.waitFor(() => expect(texts(c).slice(-1)[0]).toBe("Half a re"));
      await c.stop();
      await turn;
      return c;
    }

    it("says nothing when the conversation stays interrupted", async () => {
      const transport = stoppable();
      const c = await stopMidTurn(transport, (read) =>
        read.mockResolvedValue({ conversationState: "EXECUTION_INTERRUPTED", conversationOutputs: [] }),
      );
      // The turn's own re-read after its stream, then the two checks.
      expect(transport.read).toHaveBeenCalledTimes(3);
      expect(c.getSnapshot().messages.some((m) => m.kind === "notice")).toBe(false);
      expect(c.getSnapshot().conversationState).toBe("EXECUTION_INTERRUPTED");
    });

    it("says the turn finished anyway and shows its whole reply in place of the part Stop left", async () => {
      const transport = stoppable();
      const c = await stopMidTurn(transport, (read) =>
        read.mockResolvedValue({ conversationState: "READY", conversationOutputs: [output("Half a reply, finished on pod B")] }),
      );
      expect(texts(c)).toEqual([
        "long",
        "Stop came too late: the reply had already finished on the server, and it counts. Here it is in full.",
        "Half a reply, finished on pod B",
      ]);
      expect(c.getSnapshot().conversationState).toBe("READY");
    });

    it("says the turn is still running when a later read finds it in progress", async () => {
      const transport = stoppable();
      const c = await stopMidTurn(transport, (read) =>
        read
          .mockResolvedValueOnce({ conversationState: "EXECUTION_INTERRUPTED" })
          .mockResolvedValueOnce({ conversationState: "IN_PROGRESS" }),
      );
      const last = c.getSnapshot().messages.slice(-1)[0];
      expect(last?.kind).toBe("notice");
      expect(last?.content).toContain("still running on another server");
      expect(texts(c)).toContain("Half a re");
    });

    it("a 409 means the turn had already finished: shown, not swallowed", async () => {
      const transport = stoppable({ cancel: vi.fn(async () => Promise.reject(new ApiError(409, "nothing", "test"))) });
      const c = await stopMidTurn(transport, (read) =>
        read.mockResolvedValue({ conversationState: "READY", conversationOutputs: [output("Complete")] }),
      );
      expect(transport.read).toHaveBeenCalledTimes(2); // the turn's re-read, then one check
      expect(texts(c).slice(-2)).toEqual([expect.stringContaining("Stop came too late"), "Complete"]);
    });

    it("says so when the cancel could not reach the server", async () => {
      const transport = stoppable({ cancel: vi.fn(async () => Promise.reject(new TypeError("offline"))) });
      const c = await stopMidTurn(transport);
      expect(transport.read).toHaveBeenCalledTimes(1); // only the turn's own re-read
      expect(texts(c).slice(-1)[0]).toContain("Stop could not reach the server");
    });

    it("stops checking once the user sends the next turn", async () => {
      const transport = stoppable();
      const c = await opened(transport, { stopVerifyDelaysMs: [30] });
      transport.read.mockReset().mockResolvedValue({ conversationState: "READY", conversationOutputs: [output("next")] });
      const turn = c.send("long");
      await vi.waitFor(() => expect(texts(c).slice(-1)[0]).toBe("Half a re"));
      const stopping = c.stop();
      await turn;
      transport.stream = vi.fn(() => events(done({ conversationState: "READY", conversationOutputs: [output("next")] })));
      transport.read.mockResolvedValue({ conversationState: "READY", conversationOutputs: [output("next")] });
      await c.send("again");
      await stopping;
      expect(texts(c).some((t) => t.includes("Stop came too late"))).toBe(false);
    });
  });

  it("the refresh after a pause re-reads without duplicating the shown placeholder", async () => {
    const transport = fakeTransport();
    const c = await opened(transport);
    c.dispatch({ type: "ADD_MESSAGE", message: { id: "a", role: "agent", content: "Awaiting approval", timestamp: 0 } });
    transport.read.mockResolvedValue({ conversationState: "READY", conversationOutputs: [output("Awaiting approval"), output("Done")] });
    expect(await c.refreshAfterPause(() => false)).toBe(true);
    expect(texts(c)).toEqual(["Awaiting approval", "Done"]);
    expect(c.getSnapshot().conversationState).toBe("READY");
  });
});

describe("host hooks (a host with its own store and its own copy)", () => {
  it("keeps the state in the host's store and notifies through it", async () => {
    let held = { ...initialConversationState };
    const listeners = new Set<() => void>();
    const store = {
      get: () => held,
      set: (next: typeof held) => {
        held = next;
        listeners.forEach((l) => l());
      },
      subscribe: (l: () => void) => {
        listeners.add(l);
        return () => {
          listeners.delete(l);
        };
      },
    };
    const c = await opened(fakeTransport(), { store });
    const listener = vi.fn();
    c.subscribe(listener);
    await c.send("hello");
    expect(held.messages.map((m) => m.content)).toEqual(["hello", "hi"]);
    expect(c.getSnapshot()).toBe(held);
    expect(listener).toHaveBeenCalled();
  });

  it("follows the host's generation: a turn it moved past writes nothing", async () => {
    let gen = 0;
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const transport = fakeTransport({
      stream: vi.fn(async function* () {
        await gate;
        yield ev("token", "late");
        yield done({ conversationState: "READY", conversationOutputs: [output("late")] });
      }),
    });
    const c = await opened(transport, { generation: () => gen });
    const sending = c.send("hi");
    gen += 1;
    c.dispatch({ type: "CLEAR_MESSAGES" });
    release();
    await sending;
    expect(texts(c)).toEqual([]);
  });

  it("drains a detached stream for the grace period, then aborts it", async () => {
    let aborted = false;
    let reached!: () => void;
    const atGate = new Promise<void>((r) => (reached = r));
    const transport = fakeTransport({
      stream: vi.fn((_id: string | null, _text: string, { signal }: { signal: AbortSignal }) =>
        (async function* () {
          yield ev("token", "A");
          await new Promise<void>((_, reject) => {
            signal.addEventListener("abort", () => {
              aborted = true;
              reject(new DOMException("aborted", "AbortError"));
            });
            reached();
          });
        })(),
      ),
    });
    const c = await opened(transport, { detachedGraceMs: 1000 });
    const sending = c.send("hi");
    await atGate;
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    try {
      c.reset();
      vi.advanceTimersByTime(999);
      expect(aborted).toBe(false);
      vi.advanceTimersByTime(1);
      expect(aborted).toBe(true);
    } finally {
      vi.useRealTimers();
    }
    await sending;
    expect(texts(c)).toEqual([]);
  });

  it("hands a refusal to the host, which presents it; the turn still ends", async () => {
    const presentFailure = vi.fn((_f: unknown, ctx: { withdraw(): void }) => {
      ctx.withdraw();
      return true;
    });
    const transport = fakeTransport({
      stream: vi.fn(() => events(ev("error", { code: "agent_mismatch", message: "Agent version mismatch" }))),
    });
    const c = await opened(transport, { presentFailure, refreshAfterStream: false });
    c.dispatch({ type: "SET_INPUT_FIELD", field: { subType: "password" } });
    const result = await c.send("key", true);
    expect(result.outcome).toBe("refused");
    expect(presentFailure).toHaveBeenCalledWith(
      expect.objectContaining({ kind: "refused", code: "agent_mismatch", message: "Agent version mismatch" }),
      expect.objectContaining({ text: "key", isSecret: true, answeredField: { subType: "password" } }),
    );
    // No notice of the controller's own: the host said it.
    expect(texts(c)).toEqual([]);
    expect(c.getSnapshot().isProcessing).toBe(false);
    expect(transport.read).toHaveBeenCalledTimes(1); // the open only
  });

  it("hands a failed request to the host and reports it to the caller", async () => {
    const transport = fakeTransport({
      say: vi.fn(async () => {
        throw new ApiError(500, "boom", "Send failed");
      }),
    });
    const presentFailure = vi.fn(() => true);
    const c = await opened(transport, { streaming: false, presentFailure });
    const result = await c.send("hi");
    expect(result).toEqual(expect.objectContaining({ outcome: "failed" }));
    expect(presentFailure).toHaveBeenCalledWith(expect.objectContaining({ kind: "failed", status: 500 }), expect.anything());
    // The host kept the bubble (it did not withdraw).
    expect(texts(c)).toEqual(["hi"]);
  });

  it("feeds every event of the turn on screen to onStreamEvent and brackets the turn", async () => {
    const seen: string[] = [];
    const c = await opened(fakeTransport(), {
      onStreamEvent: (e) => seen.push(e.type),
      onTurnStarting: () => seen.push("start"),
      onTurnEnd: () => seen.push("end"),
    });
    await c.send("hello");
    expect(seen).toEqual(["start", "done", "end"]);
  });

  it("shows why a finished turn failed in its reply, after the reconcile", async () => {
    const transport = fakeTransport({
      stream: vi.fn(() =>
        events(ev("token", "Part"), done({ conversationState: "ERROR", conversationOutputs: [output("Partial")] })),
      ),
    });
    const onTurnSnapshot = vi.fn();
    const c = await opened(transport, {
      turnFailure: (s) => (s.conversationState === "ERROR" ? "Task failed" : null),
      onTurnSnapshot,
    });
    await c.send("go");
    expect(texts(c)).toEqual(["go", "Partial\n\n⚠️ Task failed"]);
    expect(onTurnSnapshot).toHaveBeenCalledWith(expect.objectContaining({ conversationState: "ERROR" }));
  });

  it("shows files as chips, and never forwards one with a secret turn", async () => {
    const transport = fakeTransport();
    const c = await opened(transport, { attachmentDisplay: "chips" });
    const file = { storageRef: "s1", fileName: "a.png", mimeType: "image/png", previewUrl: "blob:x" };
    await c.send("look", false, { attachments: [file] });
    const user = c.getSnapshot().messages.find((m) => m.role === "user");
    expect(user?.content).toBe("look");
    expect(user?.attachments).toEqual([{ fileName: "a.png", mimeType: "image/png", previewUrl: "blob:x" }]);
    expect(transport.stream).toHaveBeenLastCalledWith(
      "c1",
      "look",
      expect.objectContaining({ context: { attachment_0: { type: "object", value: { storageRef: "s1", fileName: "a.png" } } } }),
    );
    await c.send("pw", true, { attachments: [file] });
    expect(transport.stream).toHaveBeenLastCalledWith(
      "c1",
      "pw",
      expect.objectContaining({ context: { secretInput: { type: "string", value: "true" } } }),
    );
  });

  it("reads output texts the host's way", async () => {
    const transport = fakeTransport({
      say: vi.fn(async () => ({ conversationOutputs: [{ "output:text:x": "legacy" }] }) as unknown as Snap),
    });
    const c = await opened(transport, {
      streaming: false,
      outputTexts: (o) =>
        Object.entries(o)
          .filter(([k]) => k.startsWith("output:text:"))
          .map(([, v]) => String(v)),
    });
    await c.send("hi");
    expect(texts(c)).toEqual(["hi", "legacy"]);
  });
});
