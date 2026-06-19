import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import { useOrphanScan, usePurgeOrphans } from "@/hooks/use-orphans";

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

describe("useOrphanScan", () => {
  it("starts disabled (manual trigger)", () => {
    const { result } = renderHook(() => useOrphanScan(), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
    expect(result.current.data).toBeUndefined();
  });

  it("fetches orphans when refetch is called", async () => {
    const { result } = renderHook(() => useOrphanScan(), {
      wrapper: createWrapper(),
    });
    result.current.refetch();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("totalOrphans");
    expect(result.current.data).toHaveProperty("orphans");
  });

  it("accepts includeDeleted parameter", async () => {
    const { result } = renderHook(() => useOrphanScan(true), {
      wrapper: createWrapper(),
    });
    result.current.refetch();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("usePurgeOrphans", () => {
  it("purges orphans successfully", async () => {
    const { result } = renderHook(() => usePurgeOrphans(), {
      wrapper: createWrapper(),
    });

    result.current.mutate(false);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
