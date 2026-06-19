import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import {
  useTriggers,
  useCreateTrigger,
  useUpdateTrigger,
  useDeleteTrigger,
} from "@/hooks/use-triggers";

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

describe("useTriggers", () => {
  it("fetches all triggers", async () => {
    const { result } = renderHook(() => useTriggers(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
    expect(result.current.data!.length).toBeGreaterThan(0);
    expect(result.current.data![0]).toHaveProperty("intent");
    expect(result.current.data![0]).toHaveProperty("agentDeployments");
  });
});

describe("useCreateTrigger", () => {
  it("creates a trigger successfully", async () => {
    const { result } = renderHook(() => useCreateTrigger(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      intent: "test_intent",
      agentDeployments: [{ environment: "test", agentId: "agent1" }],
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useUpdateTrigger", () => {
  it("updates a trigger successfully", async () => {
    const { result } = renderHook(() => useUpdateTrigger(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      intent: "booking_request",
      config: {
        intent: "booking_request",
        agentDeployments: [{ environment: "production", agentId: "agent1" }],
      },
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDeleteTrigger", () => {
  it("deletes a trigger successfully", async () => {
    const { result } = renderHook(() => useDeleteTrigger(), {
      wrapper: createWrapper(),
    });

    result.current.mutate("booking_request");
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
