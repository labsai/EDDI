import { renderHook, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { type ReactNode } from "react";
import {
  useEnrichedChannelDescriptors,
  useChannel,
  useCreateChannel,
  useUpdateChannel,
  useDeleteChannel,
  useDuplicateChannel,
} from "@/hooks/use-channels";
import {
  useTriggers,
  useCreateTrigger,
  useUpdateTrigger,
  useDeleteTrigger,
} from "@/hooks/use-triggers";
import {
  useDeleteUserData,
  useExportUserData,
  useRestrictProcessing,
  useUnrestrictProcessing,
  useIsProcessingRestricted,
} from "@/hooks/use-gdpr";
import {
  useUserMemories,
  useSearchMemories,
  useCountMemories,
  useDeleteMemory,
  useDeleteAllMemories,
} from "@/hooks/use-user-memory";
import {
  useUserProperties,
  useDeleteProperties,
} from "@/hooks/use-properties";

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

// ─── Channels ───────────────────────────────────────────────────────

describe("useEnrichedChannelDescriptors", () => {
  it("fetches channel descriptors", async () => {
    const { result } = renderHook(() => useEnrichedChannelDescriptors(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });
});

describe("useChannel", () => {
  it("fetches a single channel", async () => {
    const { result } = renderHook(() => useChannel("ch1"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("is disabled when id is empty", () => {
    const { result } = renderHook(() => useChannel(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useCreateChannel", () => {
  it("creates a channel", async () => {
    const { result } = renderHook(() => useCreateChannel(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({ name: "Test", channelType: "slack" } as never);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useUpdateChannel", () => {
  it("updates a channel", async () => {
    const { result } = renderHook(() => useUpdateChannel(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        id: "ch1",
        version: 1,
        config: { name: "Updated" } as never,
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDeleteChannel", () => {
  it("deletes a channel", async () => {
    const { result } = renderHook(() => useDeleteChannel(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({ id: "ch1", version: 1 });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDuplicateChannel", () => {
  it("duplicates a channel", async () => {
    const { result } = renderHook(() => useDuplicateChannel(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({ id: "ch1", version: 1 });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

// ─── Triggers ───────────────────────────────────────────────────────

describe("useTriggers", () => {
  it("fetches all triggers", async () => {
    const { result } = renderHook(() => useTriggers(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
    expect(result.current.data!.length).toBeGreaterThan(0);
  });
});

describe("useCreateTrigger", () => {
  it("creates a trigger", async () => {
    const { result } = renderHook(() => useCreateTrigger(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        intent: "test_intent",
        agentDeployments: [{ environment: "production", agentId: "agent1" }],
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useUpdateTrigger", () => {
  it("updates a trigger", async () => {
    const { result } = renderHook(() => useUpdateTrigger(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        intent: "booking_request",
        config: {
          intent: "booking_request",
          agentDeployments: [{ environment: "test", agentId: "agent3" }],
        },
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDeleteTrigger", () => {
  it("deletes a trigger", async () => {
    const { result } = renderHook(() => useDeleteTrigger(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate("booking_request");
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

// ─── GDPR ───────────────────────────────────────────────────────────

describe("useDeleteUserData", () => {
  it("deletes user data", async () => {
    const { result } = renderHook(() => useDeleteUserData(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate("user-123");
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("memoriesDeleted");
  });
});

describe("useExportUserData", () => {
  it("exports user data", async () => {
    const { result } = renderHook(() => useExportUserData(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate("user-123");
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("userId");
    expect(result.current.data).toHaveProperty("memories");
  });
});

describe("useRestrictProcessing", () => {
  it("restricts processing", async () => {
    const { result } = renderHook(() => useRestrictProcessing(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate("user-123");
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useUnrestrictProcessing", () => {
  it("unrestricts processing", async () => {
    const { result } = renderHook(() => useUnrestrictProcessing(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate("user-123");
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useIsProcessingRestricted", () => {
  it("checks restriction status", async () => {
    const { result } = renderHook(
      () => useIsProcessingRestricted("user-123"),
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(typeof result.current.data).toBe("boolean");
  });

  it("is disabled when userId is empty", () => {
    const { result } = renderHook(
      () => useIsProcessingRestricted(""),
      { wrapper: createWrapper() }
    );
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("is disabled when userId is whitespace", () => {
    const { result } = renderHook(
      () => useIsProcessingRestricted("   "),
      { wrapper: createWrapper() }
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

// ─── User Memory ────────────────────────────────────────────────────

describe("useUserMemories", () => {
  it("fetches user memories", async () => {
    const { result } = renderHook(() => useUserMemories("user-123"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
    expect(result.current.data!.length).toBeGreaterThan(0);
  });

  it("is disabled when userId is empty", () => {
    const { result } = renderHook(() => useUserMemories(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useSearchMemories", () => {
  it("searches user memories", async () => {
    const { result } = renderHook(
      () => useSearchMemories("user-123", "language"),
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("is disabled when query is empty", () => {
    const { result } = renderHook(
      () => useSearchMemories("user-123", ""),
      { wrapper: createWrapper() }
    );
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("is disabled when userId is empty", () => {
    const { result } = renderHook(
      () => useSearchMemories("", "test"),
      { wrapper: createWrapper() }
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useCountMemories", () => {
  it("counts user memories", async () => {
    const { result } = renderHook(() => useCountMemories("user-123"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(typeof result.current.data).toBe("number");
  });

  it("is disabled when userId is empty", () => {
    const { result } = renderHook(() => useCountMemories(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useDeleteMemory", () => {
  it("deletes a memory entry", async () => {
    const { result } = renderHook(() => useDeleteMemory(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate("mem-1");
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDeleteAllMemories", () => {
  it("deletes all memories for a user", async () => {
    const { result } = renderHook(() => useDeleteAllMemories(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate("user-123");
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

// ─── Properties ─────────────────────────────────────────────────────

describe("useUserProperties", () => {
  it("fetches user properties", async () => {
    const { result } = renderHook(() => useUserProperties("user-123"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("is disabled when userId is empty", () => {
    const { result } = renderHook(() => useUserProperties(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useDeleteProperties", () => {
  it("deletes user properties", async () => {
    const { result } = renderHook(() => useDeleteProperties(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate("user-123");
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
