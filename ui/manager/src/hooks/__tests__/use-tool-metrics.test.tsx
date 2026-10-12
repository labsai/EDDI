import { describe, it, expect } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import {
  useConversationCosts,
} from "@/hooks/use-tool-metrics";

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
  };
}

describe("useConversationCosts", () => {
  it("fetches conversation costs", async () => {
    const { result } = renderHook(
      () => useConversationCosts("conv-123"),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("totalCost");
    expect(result.current.data).toHaveProperty("toolCallCount");
  });

  it("is disabled when conversationId is null", () => {
    const { result } = renderHook(
      () => useConversationCosts(null),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("is disabled when enabled is false", () => {
    const { result } = renderHook(
      () => useConversationCosts("conv-123", false),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});
