import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import {
  useRecentLogs,
  useHistoryLogs,
  useInstanceId,
  useLogStream,
} from "@/hooks/use-logs";
import { http, HttpResponse } from "msw";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterAll(() => server.close());
afterEach(() => server.resetHandlers());

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
