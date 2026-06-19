import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import {
  useVariables,
  useUpsertVariable,
  useDeleteVariable,
} from "@/hooks/use-variables";

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

describe("useVariables", () => {
  it("fetches all global variables", async () => {
    const { result } = renderHook(() => useVariables(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
    expect(result.current.data!.length).toBeGreaterThan(0);
    expect(result.current.data![0]).toHaveProperty("key");
    expect(result.current.data![0]).toHaveProperty("value");
  });
});

describe("useUpsertVariable", () => {
  it("upserts a variable successfully", async () => {
    const { result } = renderHook(() => useUpsertVariable(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      key: "test-var",
      variable: {
        key: "test-var",
        value: "test-value",
        description: "A test variable",
        exportable: true,
      },
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDeleteVariable", () => {
  it("deletes a variable successfully", async () => {
    const { result } = renderHook(() => useDeleteVariable(), {
      wrapper: createWrapper(),
    });

    result.current.mutate("test-var");
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
