import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import {
  useDeleteUserData,
  useExportUserData,
  useRestrictProcessing,
  useUnrestrictProcessing,
  useIsProcessingRestricted,
} from "@/hooks/use-gdpr";

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
