import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import {
  useCoordinatorStatus,
  useDeadLetters,
  useReplayDeadLetter,
  useDiscardDeadLetter,
  usePurgeDeadLetters,
} from "@/hooks/use-coordinator";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterAll(() => server.close());
afterEach(() => server.resetHandlers());

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
  return { Wrapper: function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
  }, queryClient };
}

describe("useCoordinatorStatus", () => {
  it("fetches coordinator status", async () => {
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useCoordinatorStatus(), {
      wrapper: Wrapper,
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("coordinatorType");
    expect(result.current.data).toHaveProperty("connected");
    expect(result.current.data).toHaveProperty("activeConversations");
  });

  it("returns all expected status fields", async () => {
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useCoordinatorStatus(), {
      wrapper: Wrapper,
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const data = result.current.data!;
    expect(data).toHaveProperty("totalProcessed");
    expect(data).toHaveProperty("totalDeadLettered");
    expect(data).toHaveProperty("connectionStatus");
    expect(data).toHaveProperty("queueDepths");
  });

  it("handles status fetch error gracefully", async () => {
    server.use(
      http.get("*/administration/coordinator/status", () => {
        return new HttpResponse(null, { status: 500 });
      })
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useCoordinatorStatus(), {
      wrapper: Wrapper,
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("useDeadLetters", () => {
  it("fetches dead letters list", async () => {
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useDeadLetters(), {
      wrapper: Wrapper,
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
    expect(result.current.data!.length).toBeGreaterThan(0);
    expect(result.current.data![0]).toHaveProperty("conversationId");
    expect(result.current.data![0]).toHaveProperty("error");
  });

  it("returns dead letter entries with all required fields", async () => {
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useDeadLetters(), {
      wrapper: Wrapper,
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const entry = result.current.data![0];
    expect(entry).toHaveProperty("id");
    expect(entry).toHaveProperty("timestamp");
    expect(entry).toHaveProperty("payload");
  });

  it("handles dead letters fetch error", async () => {
    server.use(
      http.get("*/administration/coordinator/dead-letters", () => {
        return new HttpResponse(null, { status: 500 });
      })
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useDeadLetters(), {
      wrapper: Wrapper,
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("useReplayDeadLetter", () => {
  it("replays a dead letter and verifies API call", async () => {
    let replayCalled = false;
    server.use(
      http.post("*/administration/coordinator/dead-letters/:entryId/replay", () => {
        replayCalled = true;
        return new HttpResponse(null, { status: 200 });
      })
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useReplayDeadLetter(), {
      wrapper: Wrapper,
    });

    result.current.mutate("dl-001");
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(replayCalled).toBe(true);
  });

  it("handles replay error", async () => {
    server.use(
      http.post("*/administration/coordinator/dead-letters/:entryId/replay", () => {
        return new HttpResponse(null, { status: 500 });
      })
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useReplayDeadLetter(), {
      wrapper: Wrapper,
    });

    result.current.mutate("dl-bad");
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("useDiscardDeadLetter", () => {
  it("discards a dead letter and verifies API call", async () => {
    let discardCalled = false;
    server.use(
      http.delete("*/administration/coordinator/dead-letters/:entryId", () => {
        discardCalled = true;
        return new HttpResponse(null, { status: 204 });
      })
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useDiscardDeadLetter(), {
      wrapper: Wrapper,
    });

    result.current.mutate("dl-001");
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(discardCalled).toBe(true);
  });

  it("handles discard error", async () => {
    server.use(
      http.delete("*/administration/coordinator/dead-letters/:entryId", () => {
        return new HttpResponse(null, { status: 500 });
      })
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useDiscardDeadLetter(), {
      wrapper: Wrapper,
    });

    result.current.mutate("dl-bad");
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("usePurgeDeadLetters", () => {
  it("purges all dead letters and verifies API call", async () => {
    let purgeCalled = false;
    server.use(
      http.delete("*/administration/coordinator/dead-letters", () => {
        purgeCalled = true;
        return HttpResponse.json(3);
      })
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => usePurgeDeadLetters(), {
      wrapper: Wrapper,
    });

    result.current.mutate();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(purgeCalled).toBe(true);
  });

  it("handles purge error", async () => {
    server.use(
      http.delete("*/administration/coordinator/dead-letters", () => {
        return new HttpResponse(null, { status: 500 });
      })
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => usePurgeDeadLetters(), {
      wrapper: Wrapper,
    });

    result.current.mutate();
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});
