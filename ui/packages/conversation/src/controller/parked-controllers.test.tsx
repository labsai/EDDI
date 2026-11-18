/* ──────────────────────────────────────────────
   Leaving the page: grace for a running turn, a warning on tab close
   ────────────────────────────────────────────── */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";
import type { ConversationSnapshot, SSEEvent } from "@eddi/core";
import { AgentConversation } from "./agent-conversation";
import { DETACHED_TURN_GRACE_MS, ParkedControllers } from "./parked-controllers";
import type { AgentTransport } from "./transport";
import { useLeaveGuard } from "../react/hooks";

/** A turn that runs until `finish()`; its signal is the one the controller aborts. */
function slowTransport() {
  let finish!: () => void;
  const signals: AbortSignal[] = [];
  const transport: AgentTransport = {
    mode: "conversation",
    start: vi.fn(async () => "c1"),
    read: vi.fn(async () => ({ conversationState: "READY", conversationOutputs: [] }) as unknown as ConversationSnapshot),
    stream: vi.fn((_id, _text, { signal }) => {
      signals.push(signal);
      return (async function* (): AsyncGenerator<SSEEvent> {
        yield { type: "token", data: "Working" } as SSEEvent;
        await new Promise<void>((resolve) => {
          finish = resolve;
          signal.addEventListener("abort", () => resolve());
        });
        if (signal.aborted) return;
        yield {
          type: "done",
          data: JSON.stringify({ conversationState: "READY", conversationOutputs: [{ output: [{ type: "text", text: "Working done" }] }] }),
        } as SSEEvent;
      })();
    }),
    undo: vi.fn(),
    redo: vi.fn(),
    rerun: vi.fn(),
    cancel: vi.fn(),
  };
  return { transport, signals, finish: () => finish() };
}

async function entryWithRunningTurn(registry: ParkedControllers<{ controller: AgentConversation }>, key: string) {
  const s = slowTransport();
  const { entry } = registry.acquire(key, () => ({ controller: new AgentConversation({ transport: s.transport }) }));
  await entry.controller.open(false);
  const turn = entry.controller.send("question");
  await vi.waitFor(() => expect(entry.controller.getSnapshot().isProcessing).toBe(true));
  await vi.waitFor(() => expect(s.signals.length).toBe(1));
  return { ...s, entry, turn };
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("ParkedControllers", () => {
  it("shares the Manager's grace period", () => {
    expect(DETACHED_TURN_GRACE_MS).toBe(120_000);
  });

  it("an idle controller is reset and forgotten at once", async () => {
    const registry = new ParkedControllers<{ controller: AgentConversation }>();
    const { entry } = registry.acquire("k", () => ({ controller: new AgentConversation({ transport: slowTransport().transport }) }));
    await entry.controller.open(false);
    registry.release("k");
    expect(entry.controller.getSnapshot().conversationId).toBeNull();
    expect(registry.isParked("k")).toBe(false);
    expect(registry.acquire("k", () => ({ controller: new AgentConversation({ transport: slowTransport().transport }) })).entry).not.toBe(entry);
  });

  it("a running turn is not aborted when the page leaves", async () => {
    const registry = new ParkedControllers<{ controller: AgentConversation }>();
    const { entry, signals, finish, turn } = await entryWithRunningTurn(registry, "k");
    registry.release("k");
    expect(registry.isParked("k")).toBe(true);
    expect(signals[0]?.aborted).toBe(false);
    expect(entry.controller.getSnapshot().messages.length).toBeGreaterThan(0);
    finish();
    await turn;
    // Finished on the server and in the controller, while nobody was looking.
    expect(entry.controller.getSnapshot().messages.map((m) => m.content)).toContain("Working done");
  });

  it("coming back within the grace hands the running turn back", async () => {
    const registry = new ParkedControllers<{ controller: AgentConversation }>();
    const { entry, signals, turn, finish } = await entryWithRunningTurn(registry, "k");
    registry.release("k");
    vi.advanceTimersByTime(DETACHED_TURN_GRACE_MS - 1);
    const back = registry.acquire("k", () => {
      throw new Error("must not create a new one");
    });
    expect(back.resumed).toBe(true);
    expect(back.entry).toBe(entry);
    expect(back.entry.controller.getSnapshot().isProcessing).toBe(true);
    // The grace timer is gone: the turn is no longer on a deadline.
    vi.advanceTimersByTime(DETACHED_TURN_GRACE_MS * 2);
    expect(signals[0]?.aborted).toBe(false);
    finish();
    await turn;
  });

  it("aborts the turn when the grace runs out", async () => {
    const registry = new ParkedControllers<{ controller: AgentConversation }>();
    const { signals } = await entryWithRunningTurn(registry, "k");
    registry.release("k");
    vi.advanceTimersByTime(DETACHED_TURN_GRACE_MS - 1);
    expect(signals[0]?.aborted).toBe(false);
    vi.advanceTimersByTime(2);
    expect(signals[0]?.aborted).toBe(true);
    expect(registry.isParked("k")).toBe(false);
  });
});

describe("useLeaveGuard", () => {
  const fire = () => {
    const event = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(event);
    return event.defaultPrevented;
  };

  it("asks before the tab closes only while active", () => {
    const { rerender, unmount } = renderHook(({ active }) => useLeaveGuard(active), { initialProps: { active: false } });
    expect(fire()).toBe(false);
    rerender({ active: true });
    expect(fire()).toBe(true);
    rerender({ active: false });
    expect(fire()).toBe(false);
    rerender({ active: true });
    unmount();
    expect(fire()).toBe(false);
  });
});
