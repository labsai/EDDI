import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import type { ConversationDescriptor } from "@eddi/app-kit/lib/api/conversations";
import { MAX_CONVERSATION_SEARCH_LENGTH } from "@eddi/app-kit/lib/api/conversations";
import {
  SEARCH_DEBOUNCE_MS,
  normalizeSearchQuery,
  useConversationSearch,
} from "@/hooks/use-conversation-search";

/** T1.3 — the search box's request discipline: debounce, stale cancellation, paging. */

const descriptor = (id: string, title: string): ConversationDescriptor =>
  ({
    resource: `eddi://ai.labs.conversation/conversationstore/conversations/${id}`,
    agentId: "agent1",
    title,
    createdOn: 1,
    lastModifiedOn: 1,
    conversationState: "READY",
  }) as unknown as ConversationDescriptor;

interface Call {
  q: string;
  index: number;
  limit: number;
  signal: AbortSignal | undefined;
  resolve: (page: ConversationDescriptor[]) => void;
  reject: (err: unknown) => void;
}

let calls: Call[];
const search = vi.fn(
  (q: string, index = 0, limit = 20, signal?: AbortSignal) =>
    new Promise<ConversationDescriptor[]>((resolve, reject) => {
      calls.push({ q, index, limit, signal, resolve, reject });
    }),
);

function setup(initial = "", pageSize = 2) {
  return renderHook(({ text }) => useConversationSearch(text, { search, pageSize }), {
    initialProps: { text: initial },
  });
}

/** Let the debounce elapse and the request go out. */
async function settleDebounce() {
  await act(async () => {
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS);
  });
}

async function answer(call: Call, page: ConversationDescriptor[]) {
  await act(async () => {
    call.resolve(page);
    await Promise.resolve();
  });
}

beforeEach(() => {
  vi.useFakeTimers();
  calls = [];
  search.mockClear();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("normalizeSearchQuery", () => {
  it("collapses whitespace, trims and caps at the backend's limit", () => {
    expect(normalizeSearchQuery("  order \n  1042 ")).toBe("order 1042");
    expect(normalizeSearchQuery("   ")).toBe("");
    expect(normalizeSearchQuery("x".repeat(150))).toHaveLength(MAX_CONVERSATION_SEARCH_LENGTH);
  });
});

describe("useConversationSearch", () => {
  it("waits for the typing to pause, then sends one request for the last text", async () => {
    const { result, rerender } = setup();
    expect(result.current.status).toBe("idle");
    rerender({ text: "r" });
    rerender({ text: "ro" });
    await act(async () => {
      vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS - 1);
    });
    rerender({ text: "rom" });
    // Still typing: nothing has gone out, and the box reads as searching.
    expect(search).not.toHaveBeenCalled();
    expect(result.current.status).toBe("loading");
    await act(async () => {
      vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS - 1);
    });
    expect(search).not.toHaveBeenCalled();
    await act(async () => {
      vi.advanceTimersByTime(1);
    });
    expect(search).toHaveBeenCalledTimes(1);
    expect(calls[0]!.q).toBe("rom");
    expect(calls[0]!.index).toBe(0);
  });

  it("aborts the request for an old text and never shows its late answer", async () => {
    const { result, rerender } = setup("rom");
    await settleDebounce();
    const first = calls[0]!;
    rerender({ text: "rome" });
    await settleDebounce();
    const second = calls[1]!;
    expect(first.signal?.aborted).toBe(true);
    expect(second.signal?.aborted).toBe(false);

    await answer(second, [descriptor("c2", "Rome trip")]);
    // The old request answers after the new one: it is ignored.
    await answer(first, [descriptor("c1", "Romania")]);
    expect(result.current.query).toBe("rome");
    expect(result.current.results.map((d) => d.title)).toEqual(["Rome trip"]);
  });

  it("clears at once when the box is emptied, aborting what is out", async () => {
    const { result, rerender } = setup("rome");
    await settleDebounce();
    rerender({ text: "  " });
    expect(result.current.status).toBe("idle");
    expect(result.current.results).toEqual([]);
    await act(async () => {
      await Promise.resolve();
    });
    expect(calls[0]!.signal?.aborted).toBe(true);
    await answer(calls[0]!, [descriptor("c1", "Rome")]);
    expect(result.current.results).toEqual([]);
  });

  it("does not search again for the same text with different spacing", async () => {
    const { rerender } = setup("order 1042");
    await settleDebounce();
    rerender({ text: " order   1042 " });
    await settleDebounce();
    expect(search).toHaveBeenCalledTimes(1);
  });

  it("pages: a full page offers more, the next page is appended without duplicates, a short page ends it", async () => {
    const { result } = setup("trip", 2);
    await settleDebounce();
    await answer(calls[0]!, [descriptor("c1", "Trip 1"), descriptor("c2", "Trip 2")]);
    expect(result.current.status).toBe("done");
    expect(result.current.hasMore).toBe(true);

    act(() => result.current.loadMore());
    expect(result.current.loadingMore).toBe(true);
    expect(calls[1]!.index).toBe(1);
    expect(calls[1]!.limit).toBe(2);
    // A conversation that moved up between the reads comes back on page 2.
    await answer(calls[1]!, [descriptor("c2", "Trip 2"), descriptor("c3", "Trip 3")]);
    expect(result.current.results.map((d) => d.title)).toEqual(["Trip 1", "Trip 2", "Trip 3"]);
    expect(result.current.hasMore).toBe(true);
    expect(result.current.loadingMore).toBe(false);

    act(() => result.current.loadMore());
    expect(calls[2]!.index).toBe(2);
    await answer(calls[2]!, [descriptor("c4", "Trip 4")]);
    expect(result.current.results).toHaveLength(4);
    expect(result.current.hasMore).toBe(false);
  });

  it("reports a failed search and retries it", async () => {
    const { result } = setup("trip");
    await settleDebounce();
    await act(async () => {
      calls[0]!.reject(new Error("boom"));
      await Promise.resolve();
    });
    expect(result.current.status).toBe("error");
    act(() => result.current.retry());
    expect(calls).toHaveLength(2);
    await answer(calls[1]!, []);
    expect(result.current.status).toBe("done");
    expect(result.current.results).toEqual([]);
  });

  it("keeps the results when the next page fails, and retries that page", async () => {
    const { result } = setup("trip", 1);
    await settleDebounce();
    await answer(calls[0]!, [descriptor("c1", "Trip 1")]);
    act(() => result.current.loadMore());
    await act(async () => {
      calls[1]!.reject(new Error("boom"));
      await Promise.resolve();
    });
    expect(result.current.status).toBe("done");
    expect(result.current.moreFailed).toBe(true);
    expect(result.current.results).toHaveLength(1);
    act(() => result.current.retry());
    expect(calls[2]!.index).toBe(1);
  });

  it("aborts what is out when the component goes away", async () => {
    const { unmount } = setup("trip");
    await settleDebounce();
    unmount();
    expect(calls[0]!.signal?.aborted).toBe(true);
  });
});
