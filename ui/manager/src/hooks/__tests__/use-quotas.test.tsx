import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import {
  useQuotas,
  useQuota,
  useQuotaUsage,
  useUpdateQuota,
  useResetUsage,
} from "@/hooks/use-quotas";

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

describe("useQuotas", () => {
  it("fetches quota list", async () => {
    const { result } = renderHook(() => useQuotas(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
    expect(result.current.data!.length).toBeGreaterThan(0);
  });
});

describe("useQuota", () => {
  it("fetches a single tenant quota", async () => {
    const { result } = renderHook(() => useQuota("default"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("tenantId");
    expect(result.current.data).toHaveProperty("maxConversationsPerDay");
  });

  it("is disabled when tenantId is empty", () => {
    const { result } = renderHook(() => useQuota(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useQuotaUsage", () => {
  it("fetches usage for a tenant", async () => {
    const { result } = renderHook(() => useQuotaUsage("default"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("conversationsToday");
    expect(result.current.data).toHaveProperty("monthlyCostUsd");
  });

  it("is disabled when tenantId is empty", () => {
    const { result } = renderHook(() => useQuotaUsage(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useUpdateQuota", () => {
  it("performs mutation successfully", async () => {
    const { result } = renderHook(() => useUpdateQuota(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      tenantId: "default",
      quota: {
        tenantId: "default",
        maxConversationsPerDay: 10000,
        maxAgentsPerTenant: 200,
        maxApiCallsPerMinute: 1000,
        maxMonthlyCostUsd: 5000,
        enabled: true,
      },
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useResetUsage", () => {
  it("performs reset mutation successfully", async () => {
    const { result } = renderHook(() => useResetUsage(), {
      wrapper: createWrapper(),
    });

    result.current.mutate("default");
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
