import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import {
  useConversationCosts,
  useToolRateLimit,
  useCacheStats,
  useToolHistory,
} from "@/hooks/use-tool-metrics";

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

describe("useConversationCosts", () => {
  it("fetches conversation costs", async () => {
    const { result } = renderHook(
      () => useConversationCosts("conv-123"),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("totalCost");
    expect(result.current.data).toHaveProperty("toolCallCount");
  });

  it("is disabled when conversationId is null", () => {
    const { result } = renderHook(
      () => useConversationCosts(null),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("is disabled when enabled is false", () => {
    const { result } = renderHook(
      () => useConversationCosts("conv-123", false),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useToolRateLimit", () => {
  it("fetches rate limit for a tool", async () => {
    const { result } = renderHook(
      () => useToolRateLimit("fetch_weather"),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("limit");
    expect(result.current.data).toHaveProperty("remaining");
  });

  it("is disabled when toolName is null", () => {
    const { result } = renderHook(
      () => useToolRateLimit(null),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("is disabled when enabled is false", () => {
    const { result } = renderHook(
      () => useToolRateLimit("test", false),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useCacheStats", () => {
  it("is disabled by default", () => {
    const { result } = renderHook(() => useCacheStats(), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("fetches cache stats when enabled", async () => {
    const { result } = renderHook(() => useCacheStats(true), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("size");
    expect(result.current.data).toHaveProperty("hits");
    expect(result.current.data).toHaveProperty("hitRate");
  });
});

describe("useToolHistory", () => {
  it("is disabled by default", () => {
    const { result } = renderHook(() => useToolHistory("conv-123"), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("fetches tool history when enabled", async () => {
    const { result } = renderHook(
      () => useToolHistory("conv-123", true),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
    expect(result.current.data![0]).toHaveProperty("toolName");
    expect(result.current.data![0]).toHaveProperty("durationMs");
  });

  it("is disabled when conversationId is null even if enabled", () => {
    const { result } = renderHook(
      () => useToolHistory(null, true),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});
