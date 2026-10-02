import { describe, it, expect } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import {
  useDeleteUserData,
  useExportUserData,
  useRestrictProcessing,
  useUnrestrictProcessing,
  useIsProcessingRestricted,
} from "@/hooks/use-gdpr";

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

describe("useDeleteUserData", () => {
  it("deletes user data successfully", async () => {
    const { result } = renderHook(() => useDeleteUserData(), {
      wrapper: createWrapper(),
    });
    result.current.mutate("user-123");
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("memoriesDeleted");
    expect(result.current.data).toHaveProperty("conversationsDeleted");
  });
});

describe("useExportUserData", () => {
  it("exports user data successfully", async () => {
    const { result } = renderHook(() => useExportUserData(), {
      wrapper: createWrapper(),
    });
    result.current.mutate("user-123");
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("memories");
    expect(result.current.data).toHaveProperty("conversations");
  });
});

describe("useRestrictProcessing", () => {
  it("restricts processing successfully", async () => {
    const { result } = renderHook(() => useRestrictProcessing(), {
      wrapper: createWrapper(),
    });
    result.current.mutate("user-123");
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useUnrestrictProcessing", () => {
  it("unrestricts processing successfully", async () => {
    const { result } = renderHook(() => useUnrestrictProcessing(), {
      wrapper: createWrapper(),
    });
    result.current.mutate("user-123");
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useIsProcessingRestricted", () => {
  it("fetches restriction status", async () => {
    const { result } = renderHook(
      () => useIsProcessingRestricted("user-123"),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(typeof result.current.data).toBe("boolean");
  });

  it("is disabled when userId is empty", () => {
    const { result } = renderHook(
      () => useIsProcessingRestricted(""),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("is disabled when userId is whitespace", () => {
    const { result } = renderHook(
      () => useIsProcessingRestricted("   "),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useDeleteUserData — cache invalidation", () => {
  it("marks every cached read the erasure touched as stale", async () => {
    // Regression: erasure invalidated nothing, so memories, conversations,
    // schedules and the restriction badge kept showing the erased user's data.
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const seeded = [
      ["user-memories", "user-123"],
      ["user-properties", "user-123"],
      ["conversations", { index: 0 }],
      ["userConversations", "all", "user-123"],
      ["groupConversations", "g1"],
      ["schedules", "list", undefined],
      ["audit", "trail", "c1", 0, 100],
      ["gdpr", "restricted", "user-123"],
      // The dashboard's "Recent conversations" card lists them too.
      ["dashboard", "recent-conversations", 5],
    ];
    for (const key of seeded) queryClient.setQueryData(key, { seeded: true });
    const untouched = ["agents"];
    queryClient.setQueryData(untouched, []);

    const { result } = renderHook(() => useDeleteUserData(), {
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      ),
    });
    result.current.mutate("user-123");
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    for (const key of seeded) {
      expect(queryClient.getQueryState(key)?.isInvalidated, JSON.stringify(key)).toBe(true);
    }
    expect(queryClient.getQueryState(untouched)?.isInvalidated).toBe(false);
  });
});
