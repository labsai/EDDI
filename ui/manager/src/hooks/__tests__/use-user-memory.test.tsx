import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import {
  useUserMemories,
  useSearchMemories,
  useCountMemories,
  useDeleteMemory,
  useDeleteAllMemories,
} from "@/hooks/use-user-memory";

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
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
  };
}

describe("useUserMemories", () => {
  it("fetches all memories for a user", async () => {
    const { result } = renderHook(() => useUserMemories("user-123"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
    expect(result.current.data!.length).toBeGreaterThan(0);
    expect(result.current.data![0]).toHaveProperty("key");
    expect(result.current.data![0]).toHaveProperty("value");
  });

  it("is disabled when userId is empty", () => {
    const { result } = renderHook(() => useUserMemories(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("is disabled when userId is whitespace", () => {
    const { result } = renderHook(() => useUserMemories("   "), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useSearchMemories", () => {
  it("searches memories with a query", async () => {
    const { result } = renderHook(
      () => useSearchMemories("user-123", "language"),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
  });

  it("is disabled when userId is empty", () => {
    const { result } = renderHook(
      () => useSearchMemories("", "test"),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("is disabled when query is empty", () => {
    const { result } = renderHook(
      () => useSearchMemories("user-123", ""),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("is disabled when both are whitespace", () => {
    const { result } = renderHook(
      () => useSearchMemories("   ", "   "),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useCountMemories", () => {
  it("counts memories for a user", async () => {
    const { result } = renderHook(() => useCountMemories("user-123"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(typeof result.current.data).toBe("number");
  });

  it("is disabled when userId is empty", () => {
    const { result } = renderHook(() => useCountMemories(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useDeleteMemory", () => {
  it("deletes a single memory entry", async () => {
    const { result } = renderHook(() => useDeleteMemory(), {
      wrapper: createWrapper(),
    });
    result.current.mutate("mem-1");
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDeleteAllMemories", () => {
  it("deletes all memories for a user", async () => {
    const { result } = renderHook(() => useDeleteAllMemories(), {
      wrapper: createWrapper(),
    });
    result.current.mutate("user-123");
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
