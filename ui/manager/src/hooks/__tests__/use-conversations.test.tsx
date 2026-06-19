import { describe, expect, it } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { type ReactNode } from "react";

import {
  useConversationDescriptors,
  useSimpleConversation,
  useRawConversation,
  useDeleteConversation,
  useConversationStepCount,
} from "@/hooks/use-conversations";

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

describe("useConversationDescriptors", () => {
  it("fetches conversation descriptors", async () => {
    const { result } = renderHook(() => useConversationDescriptors(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("supports pagination and filter", async () => {
    const { result } = renderHook(
      () => useConversationDescriptors(10, 0, "test", "agent1"),
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useSimpleConversation", () => {
  it("fetches simple conversation log", async () => {
    const { result } = renderHook(
      () => useSimpleConversation("conv1"),
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("is disabled when id is empty", () => {
    const { result } = renderHook(
      () => useSimpleConversation(""),
      { wrapper: createWrapper() }
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useRawConversation", () => {
  it("fetches raw conversation log", async () => {
    const { result } = renderHook(
      () => useRawConversation("conv1"),
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("is disabled when id is empty", () => {
    const { result } = renderHook(
      () => useRawConversation(""),
      { wrapper: createWrapper() }
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useDeleteConversation", () => {
  it("deletes a conversation", async () => {
    const { result } = renderHook(() => useDeleteConversation(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({ id: "conv1" });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("deletes permanently", async () => {
    const { result } = renderHook(() => useDeleteConversation(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({ id: "conv1", permanent: true });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useConversationStepCount", () => {
  it("fetches step count for a conversation", async () => {
    const { result } = renderHook(
      () => useConversationStepCount("conv1"),
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(typeof result.current.data).toBe("number");
  });

  it("is disabled when id is empty", () => {
    const { result } = renderHook(
      () => useConversationStepCount(""),
      { wrapper: createWrapper() }
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});
