/* ──────────────────────────────────────────────
   One user submission, one Idempotency-Key

   The backend runs a turn once per key and conversation, so a retry — the Retry
   button, a draft sent again after a 408 — is safe only when it carries the key
   of the submission it repeats. A new submission gets a new key.
   ────────────────────────────────────────────── */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, type ConversationSnapshot, type SSEEvent } from "@eddi/core";
import { AgentConversation } from "./agent-conversation";
import { createConversationTransport, createManagedTransport, type AgentTransport, type TurnOptions } from "./transport";

const ev = (type: SSEEvent["type"], data: unknown = ""): SSEEvent => ({
  type,
  data: typeof data === "string" ? data : JSON.stringify(data),
});
const replyDone = (text: string) =>
  ev("done", { conversationState: "READY", conversationOutputs: [{ output: [{ type: "text", text }] }] });

async function* events(...list: SSEEvent[]): AsyncGenerator<SSEEvent> {
  for (const e of list) yield e;
}

// eslint-disable-next-line require-yield
async function* failing(status: number): AsyncGenerator<SSEEvent> {
  throw new ApiError(status, "", "x");
}

function transportWith(streamImpl: (call: number) => AsyncIterable<SSEEvent>) {
  const keys: (string | undefined)[] = [];
  let call = 0;
  const transport: AgentTransport = {
    mode: "conversation",
    start: vi.fn(async () => "c1"),
    read: vi.fn(async () => ({ conversationState: "READY", conversationOutputs: [] }) as unknown as ConversationSnapshot),
    stream: vi.fn((_id: string | null, _text: string, options: TurnOptions) => {
      keys.push(options.idempotencyKey);
      return streamImpl(call++);
    }),
    undo: vi.fn(),
    redo: vi.fn(),
    rerun: vi.fn(),
    cancel: vi.fn(),
  };
  return { transport, keys };
}

async function opened(transport: AgentTransport) {
  const c = new AgentConversation({ transport });
  await c.open(false);
  return c;
}

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe("Idempotency-Key per submission", () => {
  it("sends a key on every streamed turn, a different one for the next submission", async () => {
    const { transport, keys } = transportWith(() => events(replyDone("ok")));
    const c = await opened(transport);
    await c.send("one");
    await c.send("two");
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBeTruthy();
    expect(keys[0]).not.toBe(keys[1]);
  });

  it("reuses the key when a failed submission is sent again unchanged (the Retry button)", async () => {
    const { transport, keys } = transportWith((n) => (n === 0 ? failing(500) : events(replyDone("ok"))));
    const c = await opened(transport);
    expect((await c.send("hello")).outcome).toBe("failed");
    await c.send("hello");
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it.each([408, 413, 503])("reuses the key after a %s", async (status) => {
    const { transport, keys } = transportWith((n) => (n === 0 ? failing(status) : events(replyDone("ok"))));
    const c = await opened(transport);
    await c.send("hello");
    await c.send("hello");
    expect(keys[1]).toBe(keys[0]);
  });

  it("gives an edited draft a new key", async () => {
    const { transport, keys } = transportWith((n) => (n === 0 ? failing(500) : events(replyDone("ok"))));
    const c = await opened(transport);
    await c.send("hello");
    await c.send("hello again");
    expect(keys[1]).not.toBe(keys[0]);
  });

  it("forgets the key once the turn was answered, so the same words later are a new turn", async () => {
    const { transport, keys } = transportWith(() => events(replyDone("ok")));
    const c = await opened(transport);
    await c.send("same");
    await c.send("same");
    expect(keys[1]).not.toBe(keys[0]);
  });

  it("does not carry a key into another conversation", async () => {
    const { transport, keys } = transportWith((n) => (n === 0 ? failing(500) : events(replyDone("ok"))));
    const c = await opened(transport);
    await c.send("hello");
    await c.restart();
    await c.send("hello");
    expect(keys[1]).not.toBe(keys[0]);
  });

  it("shows the stored answer of a duplicate (a replayed done) as the reply, not as an error", async () => {
    const { transport } = transportWith(() => events(replyDone("the stored answer")));
    const c = await opened(transport);
    const result = await c.send("hello");
    expect(result.outcome).toBe("sent");
    const messages = c.getSnapshot().messages;
    expect(messages.map((m) => m.content)).toContain("the stored answer");
    expect(messages.some((m) => m.kind === "notice")).toBe(false);
  });

  it("sends the key on a non-streamed say too", async () => {
    const seen: (string | undefined)[] = [];
    const base = transportWith(() => events()).transport;
    const transport: AgentTransport = {
      ...base,
      stream: undefined,
      say: vi.fn(async (_id: string | null, _text: string, o: TurnOptions) => {
        seen.push(o.idempotencyKey);
        return { conversationState: "READY", conversationOutputs: [{ output: [{ type: "text", text: "hi" }] }] } as Partial<ConversationSnapshot>;
      }),
    };
    const c = await opened(transport);
    await c.send("x");
    expect(seen[0]).toBeTruthy();
  });
});

describe("transports pass the key on", () => {
  it("the conversation transport hands it to the stream and the say", async () => {
    const calls = {
      sendMessageStreaming: vi.fn(() => events()),
      sendMessage: vi.fn(async () => ({})),
    };
    const t = createConversationTransport({ environment: "production", agentId: "a" }, () => calls as never);
    const signal = new AbortController().signal;
    for await (const e of t.stream!("c", "hi", { signal, idempotencyKey: "k-1" })) void e;
    await t.say!("c", "hi", { signal, idempotencyKey: "k-1" });
    expect(calls.sendMessageStreaming).toHaveBeenCalledWith("c", "hi", expect.objectContaining({ idempotencyKey: "k-1" }));
    expect(calls.sendMessage).toHaveBeenCalledWith("c", "hi", expect.objectContaining({ idempotencyKey: "k-1" }));
  });

  it("the managed transport hands it to the managed say", async () => {
    const calls = { sendManagedAgentMessage: vi.fn(async () => ({})) };
    const t = createManagedTransport({ intent: "i", userId: "u" }, () => calls as never);
    await t.say!(null, "hi", { signal: new AbortController().signal, idempotencyKey: "k-2" });
    expect(calls.sendManagedAgentMessage).toHaveBeenCalledWith("i", "u", "hi", undefined, expect.anything(), "k-2");
  });
});
