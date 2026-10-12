import { describe, expect, it } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { type ReactNode } from "react";
import {
  useQuota,
  useQuotaUsage,
  useUpdateQuota,
  useResetUsage,
} from "@/hooks/use-quotas";
import {
  useAuditTrail,
  useAuditTrailByAgent,
} from "@/hooks/use-audit";
import {
  useOrphanScan,
  usePurgeOrphans,
} from "@/hooks/use-orphans";
import {
  useConversationCosts,
} from "@/hooks/use-tool-metrics";

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <MemoryRouter>
        <QueryClientProvider client={queryClient}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
            {children}
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>
    );
  };
}

// ─── Quotas ─────────────────────────────────────────────────────────

describe("useQuota", () => {
  it("fetches single tenant quota", async () => {
    const { result } = renderHook(() => useQuota("default"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("tenantId");
  });

  it("is disabled when tenantId is empty", () => {
    const { result } = renderHook(() => useQuota(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useQuotaUsage", () => {
  it("fetches quota usage", async () => {
    const { result } = renderHook(() => useQuotaUsage("default"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("conversationsToday");
  });

  it("is disabled when tenantId is empty", () => {
    const { result } = renderHook(() => useQuotaUsage(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useUpdateQuota", () => {
  it("updates a quota", async () => {
    const { result } = renderHook(() => useUpdateQuota(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        tenantId: "default",
        quota: { maxConversationsPerDay: 10000 } as never,
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useResetUsage", () => {
  it("resets usage counters", async () => {
    const { result } = renderHook(() => useResetUsage(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate("default");
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

// ─── Audit ──────────────────────────────────────────────────────────

describe("useAuditTrail", () => {
  it("fetches audit trail for a conversation", async () => {
    const { result } = renderHook(() => useAuditTrail("conv1"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("is disabled when conversationId is empty", () => {
    const { result } = renderHook(() => useAuditTrail(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("supports pagination", async () => {
    const { result } = renderHook(() => useAuditTrail("conv1", 10, 50), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useAuditTrailByAgent", () => {
  it("fetches audit trail by agent", async () => {
    const { result } = renderHook(() => useAuditTrailByAgent("agent1", 3), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("is disabled when agentId is empty", () => {
    const { result } = renderHook(() => useAuditTrailByAgent(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("works without version", async () => {
    const { result } = renderHook(() => useAuditTrailByAgent("agent1", null), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

// ─── Orphans ────────────────────────────────────────────────────────

describe("useOrphanScan", () => {
  it("starts disabled (manual trigger)", () => {
    const { result } = renderHook(() => useOrphanScan(), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("can be called with includeDeleted param", () => {
    const { result } = renderHook(() => useOrphanScan(true), {
      wrapper: createWrapper(),
    });
    // Query is always disabled (manual trigger)
    expect(result.current.fetchStatus).toBe("idle");
    expect(typeof result.current.refetch).toBe("function");
  });
});

describe("usePurgeOrphans", () => {
  it("purges orphans", async () => {
    const { result } = renderHook(() => usePurgeOrphans(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate(false);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

// ─── Tool Metrics ───────────────────────────────────────────────────

describe("useConversationCosts", () => {
  it("fetches conversation costs", async () => {
    const { result } = renderHook(
      () => useConversationCosts("conv1"),
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("totalCost");
  });

  it("is disabled when conversationId is null", () => {
    const { result } = renderHook(
      () => useConversationCosts(null),
      { wrapper: createWrapper() }
    );
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("is disabled when enabled is false", () => {
    const { result } = renderHook(
      () => useConversationCosts("conv1", false),
      { wrapper: createWrapper() }
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});
