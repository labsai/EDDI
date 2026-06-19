import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import {
  useUserProperties,
  useDeleteProperties,
} from "@/hooks/use-properties";

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

describe("useUserProperties", () => {
  it("fetches properties for a user", async () => {
    const { result } = renderHook(() => useUserProperties("user-123"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
    expect(result.current.data).toHaveProperty("user_name");
  });

  it("is disabled when userId is empty", () => {
    const { result } = renderHook(() => useUserProperties(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("is disabled when userId is whitespace", () => {
    const { result } = renderHook(() => useUserProperties("   "), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useDeleteProperties", () => {
  it("deletes properties successfully", async () => {
    const { result } = renderHook(() => useDeleteProperties(), {
      wrapper: createWrapper(),
    });
    result.current.mutate("user-123");
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
