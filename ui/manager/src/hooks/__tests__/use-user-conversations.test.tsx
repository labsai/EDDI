import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import {
  useUserConversation,
  useCreateUserConversation,
  useDeleteUserConversation,
} from "@/hooks/use-user-conversations";

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

describe("useUserConversation", () => {
  it("fetches a user conversation by intent and userId", async () => {
    const { result } = renderHook(
      () => useUserConversation("booking", "user-1"),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("intent");
    expect(result.current.data).toHaveProperty("conversationId");
  });

  it("is disabled when intent is empty", () => {
    const { result } = renderHook(
      () => useUserConversation("", "user-1"),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("is disabled when userId is empty", () => {
    const { result } = renderHook(
      () => useUserConversation("booking", ""),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("is disabled when both are whitespace", () => {
    const { result } = renderHook(
      () => useUserConversation("   ", "   "),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useCreateUserConversation", () => {
  it("creates a user conversation binding", async () => {
    const { result } = renderHook(() => useCreateUserConversation(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      intent: "test",
      userId: "user1",
      data: {
        intent: "test",
        userId: "user1",
        environment: "production",
        agentId: "agent1",
        conversationId: "conv1",
      },
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDeleteUserConversation", () => {
  it("deletes a user conversation binding", async () => {
    const { result } = renderHook(() => useDeleteUserConversation(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({ intent: "test", userId: "user1" });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
