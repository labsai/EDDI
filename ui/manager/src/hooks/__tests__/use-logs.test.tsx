import { describe, it, expect, vi, afterEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import {
  useRecentLogs,
  useHistoryLogs,
  useInstanceId,
  useLogStream,
} from "@/hooks/use-logs";
import {
  subscriberCount,
  isStreamOpen,
  useSessionLogStore,
} from "@/hooks/session-log-store";
import { http, HttpResponse } from "msw";

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
  };
}

describe("useRecentLogs", () => {
  it("fetches recent logs", async () => {
    const { result } = renderHook(() => useRecentLogs(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
    expect(result.current.data!.length).toBeGreaterThan(0);
    expect(result.current.data![0]).toHaveProperty("level");
    expect(result.current.data![0]).toHaveProperty("message");
  });

  it("passes filter params", async () => {
    const { result } = renderHook(
      () => useRecentLogs({ level: "ERROR", agentId: "agent1" }),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("passes conversationId filter", async () => {
    let capturedUrl = "";
    server.use(
      http.get("*/administration/logs", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json([
          {
            level: "INFO",
            message: "Filtered by conversation",
            loggerName: "test",
            timestamp: Date.now(),
          },
        ]);
      })
    );

    const { result } = renderHook(
      () => useRecentLogs({ conversationId: "conv-123" }),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(capturedUrl).toContain("conversationId=conv-123");
  });

  it("passes limit filter", async () => {
    let capturedUrl = "";
    server.use(
      http.get("*/administration/logs", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json([]);
      })
    );

    const { result } = renderHook(
      () => useRecentLogs({ limit: 50 }),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(capturedUrl).toContain("limit=50");
  });

  it("handles error response", async () => {
    server.use(
      http.get("*/administration/logs", () => {
        return new HttpResponse(null, { status: 500 });
      })
    );

    const { result } = renderHook(() => useRecentLogs(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("useHistoryLogs", () => {
  it("fetches history logs", async () => {
    server.use(
      http.get("*/logs/history", () => {
        return HttpResponse.json([
          {
            level: "WARN",
            message: "Historical log entry",
            loggerName: "test",
            timestamp: new Date().toISOString(),
          },
        ]);
      }),
    );

    const { result } = renderHook(() => useHistoryLogs(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
  });

  it("passes environment filter", async () => {
    let capturedUrl = "";
    server.use(
      http.get("*/logs/history", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json([]);
      })
    );

    const { result } = renderHook(
      () => useHistoryLogs({ environment: "production" }),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(capturedUrl).toContain("environment=production");
  });

  it("passes agentId and agentVersion filters", async () => {
    let capturedUrl = "";
    server.use(
      http.get("*/logs/history", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json([]);
      })
    );

    const { result } = renderHook(
      () => useHistoryLogs({ agentId: "agent-abc", agentVersion: 3 }),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(capturedUrl).toContain("agentId=agent-abc");
    expect(capturedUrl).toContain("agentVersion=3");
  });

  it("passes userId and instanceId filters", async () => {
    let capturedUrl = "";
    server.use(
      http.get("*/logs/history", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json([]);
      })
    );

    const { result } = renderHook(
      () => useHistoryLogs({ userId: "user-42", instanceId: "node-xyz" }),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(capturedUrl).toContain("userId=user-42");
    expect(capturedUrl).toContain("instanceId=node-xyz");
  });

  it("passes pagination filters skip and limit", async () => {
    let capturedUrl = "";
    server.use(
      http.get("*/logs/history", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json([]);
      })
    );

    const { result } = renderHook(
      () => useHistoryLogs({ skip: 10, limit: 25 }),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(capturedUrl).toContain("skip=10");
    expect(capturedUrl).toContain("limit=25");
  });

  it("handles error response", async () => {
    server.use(
      http.get("*/logs/history", () => {
        return new HttpResponse(null, { status: 500 });
      })
    );

    const { result } = renderHook(() => useHistoryLogs(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("useInstanceId", () => {
  it("fetches the instance ID from /instance-id", async () => {
    server.use(
      http.get("*/logs/instance-id", () => {
        return HttpResponse.json({ instanceId: "node-abc-123" });
      }),
    );

    const { result } = renderHook(() => useInstanceId(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("returns instanceId value", async () => {
    server.use(
      http.get("*/logs/instance-id", () => {
        return HttpResponse.json({ instanceId: "instance-xyz-789" });
      }),
    );

    const { result } = renderHook(() => useInstanceId(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.instanceId).toBe("instance-xyz-789");
  });

  it("handles error response", async () => {
    server.use(
      http.get("*/logs/instance-id", () => {
        return new HttpResponse(null, { status: 500 });
      })
    );

    const { result } = renderHook(() => useInstanceId(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("useLogStream", () => {
  /** A ReadableStream that emits one SSE block then stays open (no reconnect). */
  function openStream(block: string): ReadableStream<Uint8Array> {
    const encoder = new TextEncoder();
    return new ReadableStream({
      start(controller) {
        controller.enqueue(encoder.encode(block));
        // Intentionally do not close — keeps the connection "live" so the
        // stream doesn't hit EOF and trigger a reconnect during the test.
      },
    });
  }

  let fetchSpy: ReturnType<typeof vi.spyOn> | undefined;
  afterEach(() => {
    fetchSpy?.mockRestore();
    fetchSpy = undefined;
  });

  it("handles a single named 'log' SSE event exactly once (no double-dispatch)", async () => {
    const entry = {
      level: "INFO",
      message: "single log line",
      loggerName: "test",
      timestamp: Date.now(),
    };
    const block = `event: log\ndata: ${JSON.stringify(entry)}\n\n`;
    // Bypass MSW: BearerEventSource uses fetch + ReadableStream directly.
    fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(() =>
        Promise.resolve(new Response(openStream(block), { status: 200 })),
      ) as ReturnType<typeof vi.spyOn>;

    // A filter avoids seeding from the session log store, so entries start empty.
    const { result, unmount } = renderHook(
      () => useLogStream({ level: "INFO" }),
      { wrapper: createWrapper() },
    );

    await waitFor(() => expect(result.current.entries.length).toBe(1));
    expect(result.current.entries[0]!.message).toBe("single log line");

    unmount();
  });
});

/**
 * The unfiltered stream is held by this hook, not by the module graph.
 *
 * `session-log-store` used to connect on import and `main.tsx` imported it for
 * that side effect, so every Manager tab kept an /administration/logs/stream
 * SSE connection open on every page. EDDI serves HTTP/1.1 — six concurrent
 * connections per origin across the whole Chrome profile — so a couple of tabs
 * saturated the cap and unrelated pages hung on skeleton loaders forever.
 */
describe("useLogStream — unfiltered stream ownership", () => {
  it("holds the session stream only while mounted", async () => {
    expect(subscriberCount()).toBe(0);

    const { unmount } = renderHook(() => useLogStream(), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(subscriberCount()).toBe(1));
    expect(isStreamOpen()).toBe(true);

    unmount();

    expect(subscriberCount()).toBe(0);
    expect(isStreamOpen()).toBe(false);
  });

  it("does not hold it when filters are set — that path opens its own", async () => {
    const { unmount } = renderHook(() => useLogStream({ level: "ERROR" }), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(subscriberCount()).toBe(0));

    unmount();
  });

  it("two unfiltered consumers share one socket", async () => {
    const first = renderHook(() => useLogStream(), { wrapper: createWrapper() });
    const second = renderHook(() => useLogStream(), { wrapper: createWrapper() });

    await waitFor(() => expect(subscriberCount()).toBe(2));
    expect(isStreamOpen()).toBe(true);

    first.unmount();
    expect(isStreamOpen()).toBe(true);

    second.unmount();
    expect(isStreamOpen()).toBe(false);
  });
});

// ── Regressions ─────────────────────────────────────────────────────────

describe("useLogStream — pause", () => {
  // Pause used to be implemented only on the filtered path. The default
  // unfiltered view reads the shared session store, so the button flipped its
  // label while the list kept moving underneath.
  it("freezes the unfiltered view and shows what arrived meanwhile on resume", async () => {
    const line = (timestamp: number, message: string) => ({
      timestamp,
      level: "INFO",
      loggerName: "t",
      message,
    });
    useSessionLogStore.setState({ entries: [line(1000, "before")] });

    const { result, unmount } = renderHook(() => useLogStream(), {
      wrapper: createWrapper(),
    });
    expect(result.current.entries.map((e) => e.message)).toEqual(["before"]);

    act(() => result.current.setPaused(true));
    act(() => {
      useSessionLogStore.setState((s) => ({
        entries: [line(2000, "during"), ...s.entries],
      }));
    });
    expect(result.current.paused).toBe(true);
    expect(result.current.entries.map((e) => e.message)).toEqual(["before"]);

    act(() => result.current.setPaused(false));
    expect(result.current.entries.map((e) => e.message)).toEqual([
      "during",
      "before",
    ]);

    unmount();
    useSessionLogStore.setState({ entries: [] });
  });
});

describe("useHistoryLogs — paging", () => {
  // History used to be one request for the newest 100 rows with no way to
  // reach anything older.
  it("loads older pages by row offset and de-duplicates rows that shifted between pages", async () => {
    const row = (i: number) => ({
      timestamp: 10_000 - i,
      level: "INFO",
      loggerName: "t",
      message: `row ${i}`,
    });
    const skips: string[] = [];
    server.use(
      http.get("*/logs/history", ({ request }) => {
        const url = new URL(request.url);
        const skip = Number(url.searchParams.get("skip") ?? "0");
        skips.push(String(skip));
        if (skip === 0) {
          return HttpResponse.json(Array.from({ length: 100 }, (_, i) => row(i)));
        }
        // A row written meanwhile pushed row 99 into the second page as well.
        return HttpResponse.json([row(99), row(100), row(101)]);
      })
    );

    const { result } = renderHook(() => useHistoryLogs({ limit: 100 }), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(100);
    expect(result.current.hasMore).toBe(true);

    act(() => result.current.loadMore());
    await waitFor(() => expect(result.current.data).toHaveLength(102));
    expect(skips).toEqual(["0", "100"]);
    // A short page is the last one.
    expect(result.current.hasMore).toBe(false);
  });
});

describe("useHistoryLogs — repeated rows", () => {
  it("keeps identical rows within a page and drops only the rows a page boundary repeats", async () => {
    const same = { timestamp: 5000, level: "WARN", loggerName: "t", message: "retry" };
    server.use(
      http.get("*/logs/history", ({ request }) => {
        const skip = Number(new URL(request.url).searchParams.get("skip") ?? "0");
        return HttpResponse.json(
          skip === 0
            ? [same, { ...same }]
            // One row shifted in from the previous page, plus one older row.
            : [{ ...same }, { timestamp: 4000, level: "INFO", loggerName: "t", message: "older" }]
        );
      })
    );
    const { result } = renderHook(() => useHistoryLogs({ limit: 2 }), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.data).toHaveLength(2));
    act(() => result.current.loadMore());
    await waitFor(() => expect(result.current.data).toHaveLength(3));
    expect(result.current.data!.map((r) => r.message)).toEqual(["retry", "retry", "older"]);
  });
});

