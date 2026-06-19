import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import {
  useDashboardStats,
  useRecentAgents,
  useRecentConversations,
  useCoordinatorStatusLight,
} from "@/hooks/use-dashboard";

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

describe("useDashboardStats", () => {
  it("fetches aggregated dashboard stats", async () => {
    const { result } = renderHook(() => useDashboardStats(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("agentCount");
    expect(result.current.data).toHaveProperty("workflowCount");
    expect(result.current.data).toHaveProperty("conversationCount");
  });
});

describe("useRecentAgents", () => {
  it("fetches 4 recent agents", async () => {
    const { result } = renderHook(() => useRecentAgents(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
  });
});

describe("useRecentConversations", () => {
  it("fetches recent conversations with default limit", async () => {
    const { result } = renderHook(() => useRecentConversations(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
  });

  it("accepts a custom limit", async () => {
    const { result } = renderHook(() => useRecentConversations(3), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useCoordinatorStatusLight", () => {
  it("fetches coordinator status", async () => {
    const { result } = renderHook(() => useCoordinatorStatusLight(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("connected");
    expect(result.current.data).toHaveProperty("coordinatorType");
  });
});
