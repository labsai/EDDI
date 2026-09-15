import { describe, it, expect } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import {
  useQuotas,
  useQuota,
  useQuotaUsage,
  useUpdateQuota,
  useResetUsage,
} from "@/hooks/use-quotas";

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

// ─── useQuotas (list) ──────────────────────────────────────────

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

// ─── useQuota (single tenant) ──────────────────────────────────

describe("useQuota", () => {
  it("fetches a single tenant quota", async () => {
    const { result } = renderHook(() => useQuota("default"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("tenantId");
    expect(result.current.data).toHaveProperty("maxConversationsPerDay");
    expect(result.current.data).toHaveProperty("enabled");
  });

  it("is disabled when tenantId is empty", () => {
    const { result } = renderHook(() => useQuota(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("returns default quota when backend returns 404", async () => {
    server.use(
      http.get("*/administration/quotas/:tenantId", ({ params }) => {
        // Let /quotas/count fall through to the global handler — :tenantId also matches "count"
        if (params.tenantId === "count") return;
        return new HttpResponse(null, { status: 404 });
      }),
    );

    const { result } = renderHook(() => useQuota("fresh-tenant"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual({
      tenantId: "fresh-tenant",
      maxConversationsPerDay: -1,
      maxAgentsPerTenant: -1,
      maxApiCallsPerMinute: -1,
      maxMonthlyCostUsd: -1,
      enabled: false,
    });
  });

  it("propagates non-404 errors", async () => {
    server.use(
      http.get("*/administration/quotas/:tenantId", ({ params }) => {
        // Let /quotas/count fall through to the global handler — :tenantId also matches "count"
        if (params.tenantId === "count") return;
        return new HttpResponse(null, { status: 500 });
      }),
    );

    const { result } = renderHook(() => useQuota("default"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

// ─── useQuotaUsage ─────────────────────────────────────────────

describe("useQuotaUsage", () => {
  it("fetches usage for a tenant", async () => {
    const { result } = renderHook(() => useQuotaUsage("default"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("conversationsToday");
    expect(result.current.data).toHaveProperty("monthlyCostUsd");
    expect(result.current.data).toHaveProperty("tenantId");
  });

  it("is disabled when tenantId is empty", () => {
    const { result } = renderHook(() => useQuotaUsage(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("returns zeroed usage when backend returns 404", async () => {
    server.use(
      http.get("*/administration/quotas/:tenantId/usage", () => {
        return new HttpResponse(null, { status: 404 });
      }),
    );

    const { result } = renderHook(() => useQuotaUsage("fresh-tenant"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.tenantId).toBe("fresh-tenant");
    expect(result.current.data!.conversationsToday).toBe(0);
    expect(result.current.data!.apiCallsThisMinute).toBe(0);
    expect(result.current.data!.monthlyCostUsd).toBe(0);
  });

  it("propagates non-404 errors for usage", async () => {
    server.use(
      http.get("*/administration/quotas/:tenantId/usage", () => {
        return new HttpResponse(null, { status: 500 });
      }),
    );

    const { result } = renderHook(() => useQuotaUsage("default"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

// ─── useUpdateQuota ────────────────────────────────────────────

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
    expect(result.current.data).toHaveProperty("tenantId", "default");
    expect(result.current.data).toHaveProperty("maxConversationsPerDay", 10000);
  });
});

// ─── useResetUsage ─────────────────────────────────────────────

describe("useResetUsage", () => {
  it("performs reset mutation successfully", async () => {
    const { result } = renderHook(() => useResetUsage(), {
      wrapper: createWrapper(),
    });

    result.current.mutate("default");
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
