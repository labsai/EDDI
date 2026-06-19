import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import {
  useAuditTrail,
  useAuditTrailByAgent,
  useAuditEntryCount,
} from "@/hooks/use-audit";

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

describe("useAuditTrail", () => {
  it("fetches audit entries for a conversation", async () => {
    const { result } = renderHook(() => useAuditTrail("conv1"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
    expect(result.current.data!.length).toBeGreaterThan(0);
  });

  it("is disabled when conversationId is empty", () => {
    const { result } = renderHook(() => useAuditTrail(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("respects skip and limit defaults", async () => {
    const { result } = renderHook(() => useAuditTrail("conv1", 0, 100), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useAuditTrailByAgent", () => {
  it("fetches audit entries for an agent", async () => {
    const { result } = renderHook(
      () => useAuditTrailByAgent("agent1"),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
  });

  it("fetches with agentVersion parameter", async () => {
    const { result } = renderHook(
      () => useAuditTrailByAgent("agent1", 3),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("handles null agentVersion", async () => {
    const { result } = renderHook(
      () => useAuditTrailByAgent("agent1", null),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("is disabled when agentId is empty", () => {
    const { result } = renderHook(
      () => useAuditTrailByAgent(""),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useAuditEntryCount", () => {
  it("fetches audit entry count", async () => {
    const { result } = renderHook(() => useAuditEntryCount("conv1"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(typeof result.current.data).toBe("number");
  });

  it("is disabled when conversationId is empty", () => {
    const { result } = renderHook(() => useAuditEntryCount(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});
