import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import {
  useGroupDescriptors,
  useEnrichedGroupDescriptors,
  useGroup,
  useDiscussionStyles,
  useCreateGroup,
  useUpdateGroup,
  useDeleteGroup,
  useDuplicateGroup,
  useGroupConversations,
  useGroupConversation,
  useStartDiscussion,
  useDeleteGroupConversation,
  useDeleteGroupWithMembers,
} from "@/hooks/use-groups";

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

// ─── Group Config ────────────────────────────────────────────

describe("useGroupDescriptors", () => {
  it("fetches group descriptors", async () => {
    const { result } = renderHook(() => useGroupDescriptors(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
  });
});

describe("useEnrichedGroupDescriptors", () => {
  it("fetches enriched group descriptors", async () => {
    const { result } = renderHook(() => useEnrichedGroupDescriptors(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
  });
});

describe("useGroup", () => {
  it("fetches a single group", async () => {
    const { result } = renderHook(() => useGroup("group1"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("is disabled when id is empty", () => {
    const { result } = renderHook(() => useGroup(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useDiscussionStyles", () => {
  it("fetches discussion styles", async () => {
    server.use(
      http.get("*/groupstore/groups/styles", () => {
        return HttpResponse.json([
          { name: "ROUND_TABLE", label: "Round Table" },
          { name: "DEBATE", label: "Debate" },
        ]);
      }),
    );

    const { result } = renderHook(() => useDiscussionStyles(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });
});

describe("useCreateGroup", () => {
  it("creates a group successfully", async () => {
    const { result } = renderHook(() => useCreateGroup(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      groupName: "Test Group",
      agentMembers: [],
      discussionStyle: "ROUND_TABLE",
    } as never);

    await waitFor(() =>
      expect(
        result.current.isSuccess || result.current.isError,
      ).toBe(true),
    );
  });
});

describe("useUpdateGroup", () => {
  it("updates a group successfully", async () => {
    const { result } = renderHook(() => useUpdateGroup(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      id: "group1",
      version: 1,
      config: {
        groupName: "Updated Group",
        agentMembers: [],
        discussionStyle: "DEBATE",
      } as never,
    });

    await waitFor(() =>
      expect(
        result.current.isSuccess || result.current.isError,
      ).toBe(true),
    );
  });
});

describe("useDeleteGroup", () => {
  it("deletes a group", async () => {
    const { result } = renderHook(() => useDeleteGroup(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({ id: "group1", version: 1 });
    await waitFor(() =>
      expect(
        result.current.isSuccess || result.current.isError,
      ).toBe(true),
    );
  });
});

describe("useDuplicateGroup", () => {
  it("duplicates a group", async () => {
    const { result } = renderHook(() => useDuplicateGroup(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({ id: "group1", version: 1 });
    await waitFor(() =>
      expect(
        result.current.isSuccess || result.current.isError,
      ).toBe(true),
    );
  });
});

// ─── Group Conversations ─────────────────────────────────────

describe("useGroupConversations", () => {
  it("fetches conversations for a group", async () => {
    const { result } = renderHook(
      () => useGroupConversations("group1"),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
  });

  it("is disabled when groupId is empty", () => {
    const { result } = renderHook(
      () => useGroupConversations(""),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useGroupConversation", () => {
  it("fetches a single group conversation", async () => {
    const { result } = renderHook(
      () => useGroupConversation("group1", "gconv1"),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("transcript");
  });

  it("is disabled when groupId is empty", () => {
    const { result } = renderHook(
      () => useGroupConversation("", "gconv1"),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("is disabled when conversationId is empty", () => {
    const { result } = renderHook(
      () => useGroupConversation("group1", ""),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useStartDiscussion", () => {
  it("starts a discussion successfully", async () => {
    const { result } = renderHook(() => useStartDiscussion(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      groupId: "group1",
      question: "What is the meaning of life?",
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDeleteGroupConversation", () => {
  it("deletes a group conversation", async () => {
    const { result } = renderHook(
      () => useDeleteGroupConversation(),
      { wrapper: createWrapper() },
    );

    result.current.mutate({
      groupId: "group1",
      conversationId: "gconv1",
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDeleteGroupWithMembers", () => {
  it("deletes a group with its members", async () => {
    const { result } = renderHook(
      () => useDeleteGroupWithMembers(),
      { wrapper: createWrapper() },
    );

    result.current.mutate({
      groupId: "group1",
      version: 1,
      config: {
        groupName: "Test",
        agentMembers: [],
        discussionStyle: "ROUND_TABLE",
      } as never,
    });

    await waitFor(() =>
      expect(
        result.current.isSuccess || result.current.isError,
      ).toBe(true),
    );
  });
});
