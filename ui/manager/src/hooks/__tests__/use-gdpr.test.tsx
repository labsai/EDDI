import { describe, it, expect } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider, useQuery } from "@tanstack/react-query";
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
  it("evicts every cached read the erasure touched, so no later screen paints erased data", async () => {
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

    // Removed, not merely marked stale: an invalidated inactive entry still
    // holds the erased user's data and paints it on the next mount.
    for (const key of seeded) {
      expect(queryClient.getQueryData(key), JSON.stringify(key)).toBeUndefined();
    }
    expect(queryClient.getQueryData(untouched)).toEqual([]);
  });

  it("refetches a screen that is showing the erased data right now", async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    let served = "before erasure";
    const { result } = renderHook(
      () => ({
        memories: useQuery({ queryKey: ["user-memories", "user-123"], queryFn: async () => served }),
        erase: useDeleteUserData(),
      }),
      {
        wrapper: ({ children }: { children: ReactNode }) => (
          <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
        ),
      },
    );
    await waitFor(() => expect(result.current.memories.data).toBe("before erasure"));
    served = "after erasure";
    result.current.erase.mutate("user-123");
    await waitFor(() => expect(result.current.memories.data).toBe("after erasure"));
  });
});
