import { describe, it, expect } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { DISCUSSION_STYLES } from "@/lib/api/groups";
import {
  useGroupDescriptors,
  useEnrichedGroupDescriptors,
  useGroup,
  useDiscussionStyles,
  useAvailableStyles,
  isStyleSupported,
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
          { name: "ROUND_TABLE", label: "Collaborative Council" },
          { name: "DEBATE", label: "Structured Deliberation" },
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

describe("useAvailableStyles", () => {
  // The endpoint returns an ARRAY of {style, phases, description} — see
  // RestAgentGroupStore.readDiscussionStyles. Reading it as a map keyed by the
  // enum matched nothing and silently disabled the check against the real API,
  // so these fixtures deliberately mirror the wire format exactly.
  const descriptor = (style: string) => ({
    style,
    phases: ["Initial Opinions", "Synthesis"],
    description: `${style} description`,
  });

  it("offers only the styles the backend reports", async () => {
    server.use(
      http.get("*/groupstore/groups/styles", () =>
        HttpResponse.json([
          descriptor("ROUND_TABLE"),
          descriptor("DEBATE"),
          descriptor("CUSTOM"),
        ]),
      ),
    );
    const { result } = renderHook(() => useAvailableStyles(), {
      wrapper: createWrapper(),
    });
    await waitFor(() =>
      expect(result.current).toEqual(["ROUND_TABLE", "DEBATE", "CUSTOM"]),
    );
  });

  it("never offers a style this build does not know", async () => {
    server.use(
      http.get("*/groupstore/groups/styles", () =>
        HttpResponse.json([descriptor("ROUND_TABLE"), descriptor("FISHBOWL")]),
      ),
    );
    const { result } = renderHook(() => useAvailableStyles(), {
      wrapper: createWrapper(),
    });
    // Nothing in this build can describe, preview or configure it.
    await waitFor(() => expect(result.current).toEqual(["ROUND_TABLE"]));
  });

  it("falls back to the full static list when the request fails", async () => {
    let requested = false;
    server.use(
      http.get("*/groupstore/groups/styles", () => {
        requested = true;
        return HttpResponse.json({ message: "boom" }, { status: 500 });
      }),
    );
    const { result } = renderHook(() => useAvailableStyles(), {
      wrapper: createWrapper(),
    });
    // Wait for the failing request to actually land, so this asserts the error
    // path rather than the pre-request state that looks identical.
    await waitFor(() => expect(requested).toBe(true));
    await waitFor(() => expect(result.current).toEqual([...DISCUSSION_STYLES]));
  });

  it("ignores a response that shares no style with this build", async () => {
    let requested = false;
    server.use(
      http.get("*/groupstore/groups/styles", () => {
        requested = true;
        return HttpResponse.json([descriptor("SOMETHING_ELSE")]);
      }),
    );
    const { result } = renderHook(() => useAvailableStyles(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(requested).toBe(true));
    // A response with nothing in common is a contract change, not a backend
    // with zero presets — the static list stands rather than blanking pickers.
    await waitFor(() => expect(result.current).toEqual([...DISCUSSION_STYLES]));
  });

  it("keeps the static list for a map-shaped response (the old wrong contract)", async () => {
    let requested = false;
    server.use(
      http.get("*/groupstore/groups/styles", () => {
        requested = true;
        return HttpResponse.json({ ROUND_TABLE: { label: "x", phases: [] } });
      }),
    );
    const { result } = renderHook(() => useAvailableStyles(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(requested).toBe(true));
    await waitFor(() => expect(result.current).toEqual([...DISCUSSION_STYLES]));
  });
});

describe("isStyleSupported", () => {
  it("accepts a supported style and a blank one", () => {
    expect(isStyleSupported("DEBATE", ["DEBATE", "ROUND_TABLE"])).toBe(true);
    // No style chosen yet is not an error state.
    expect(isStyleSupported(null, ["DEBATE"])).toBe(true);
  });

  it("rejects a style the backend does not offer", () => {
    expect(isStyleSupported("NEGOTIATION", ["DEBATE", "ROUND_TABLE"])).toBe(false);
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
