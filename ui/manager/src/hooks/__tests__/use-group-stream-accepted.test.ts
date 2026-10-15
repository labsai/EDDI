import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { act, renderHook } from "@testing-library/react";
import {
  streamErrorOf,
  useGroupDiscussionStream,
  useGroupStreamStore,
  whenStreamAccepted,
} from "@/hooks/use-group-discussion-stream";
import type { GroupSSEEvent } from "@/lib/api/groups";

/**
 * "Streaming live" and a composer's draft both hinge on one question: did the
 * server accept the request? `connected` is false until the first frame, and
 * `whenStreamAccepted` answers it.
 */

const mockStream = vi.fn();

vi.mock("@/lib/api/groups", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/api/groups")>();
  return { ...original, streamGroupDiscussion: (...args: unknown[]) => mockStream(...args) };
});

const ev = (type: string, data: unknown): GroupSSEEvent =>
  ({ type, data: JSON.stringify(data) }) as GroupSSEEvent;

describe("whenStreamAccepted", () => {
  beforeEach(() => mockStream.mockReset());
  afterEach(() => useGroupStreamStore.setState({ streams: {} }));

  it("resolves true on the first frame and marks the stream connected", async () => {
    async function* gen() {
      yield ev("group_start", { groupConversationId: "gc-1", question: "Q?" });
    }
    mockStream.mockReturnValue(gen());
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));

    let accepted: Promise<boolean> | undefined;
    await act(async () => {
      const run = result.current.startStream("g1", "Q?");
      accepted = whenStreamAccepted("g1");
      await run;
    });
    await expect(accepted).resolves.toBe(true);
    expect(useGroupStreamStore.getState().streams.g1?.connected).toBe(true);
  });

  it("resolves false — with the error available — when the request is refused before any frame", async () => {
    // eslint-disable-next-line require-yield
    async function* gen(): AsyncGenerator<GroupSSEEvent> {
      throw new Error("A discussion needs at least two members");
    }
    mockStream.mockReturnValue(gen());
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));

    let accepted: Promise<boolean> | undefined;
    await act(async () => {
      const run = result.current.startStream("g1", "Q?");
      accepted = whenStreamAccepted("g1");
      await run;
    });
    await expect(accepted).resolves.toBe(false);
    const state = useGroupStreamStore.getState().streams.g1;
    expect(state?.connected).toBe(false);
    expect(state?.state).toBe("FAILED");
    expect(streamErrorOf("g1")).toBe("A discussion needs at least two members");
  });

  it("is not reported connected again when a continuation starts", async () => {
    useGroupStreamStore.getState().update("g1", (s) => ({ ...s, connected: true, isStreaming: false }));
    // eslint-disable-next-line require-yield
    async function* gen() {
      // Never yields — the request is still pending.
      await new Promise(() => {});
    }
    mockStream.mockReturnValue(gen());
    void useGroupStreamStore.getState().startStream("g1", "again");
    expect(useGroupStreamStore.getState().streams.g1?.connected).toBe(false);
    useGroupStreamStore.getState().abortStream("g1");
  });
});
