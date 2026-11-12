import { afterEach, describe, expect, it, vi } from "vitest";
import { render, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { resetUserEventStreamForTests } from "@eddi/core/user-events";
import { AuthContext, GUEST_CONTEXT } from "@eddi/app-kit/components/auth/auth-context";
import { UserEventBridge } from "@eddi/app-kit/components/layout/user-event-bridge";
import { INVALIDATION_WINDOW_MS } from "@eddi/app-kit/lib/coalesce-invalidations";
import { appUserEventStream, staleQueryKeys } from "@eddi/app-kit/lib/user-event-stream";
import { createTestQueryClient } from "@eddi/app-kit/test/test-utils";
import { closeMockUserEventStreams, pushMockUserEvent } from "@eddi/app-kit/test/mocks/handlers";

/**
 * The bridge between the tab's user event stream (B12) and the query cache:
 * open only while signed in, closed on sign-out, and every event — and every
 * (re)connect — turned into the refetches that show what happened.
 */
function mount(authenticated: boolean) {
  const queryClient = createTestQueryClient();
  const invalidate = vi.spyOn(queryClient, "invalidateQueries");
  const tree = (signedIn: boolean) => (
    <AuthContext.Provider value={{ ...GUEST_CONTEXT, authenticated: signedIn }}>
      <QueryClientProvider client={queryClient}>
        <UserEventBridge />
      </QueryClientProvider>
    </AuthContext.Provider>
  );
  const view = render(tree(authenticated));
  return { invalidate, rerender: (signedIn: boolean) => view.rerender(tree(signedIn)), unmount: view.unmount };
}

const invalidatedKeys = (spy: ReturnType<typeof vi.spyOn>) =>
  spy.mock.calls.map((call: unknown[]) => JSON.stringify((call[0] as { queryKey: unknown[] }).queryKey));

afterEach(() => {
  closeMockUserEventStreams();
  resetUserEventStreamForTests();
});

describe("UserEventBridge", () => {
  it("does not open the stream for a signed-out visitor", async () => {
    mount(false);
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(appUserEventStream().status).toBe("idle");
  });

  it("opens the stream once signed in, refetches what is shown on open, and closes it on sign-out", async () => {
    const { invalidate, rerender } = mount(true);
    await waitFor(() => expect(appUserEventStream().status).toBe("open"));
    // There is no replay: the ready frame means "refetch now".
    await waitFor(() => expect(invalidatedKeys(invalidate)).toContain(JSON.stringify(["pending-approvals"])));
    expect(invalidatedKeys(invalidate)).toContain(JSON.stringify(["chat", "workforce-sessions"]));

    rerender(false);
    expect(appUserEventStream().status).toBe("idle");
  });

  it("an approval decided elsewhere refreshes the inbox and that conversation's paused card at once", async () => {
    const { invalidate } = mount(true);
    await waitFor(() => expect(appUserEventStream().status).toBe("open"));
    invalidate.mockClear();

    pushMockUserEvent({ kind: "approval.decided", surface: "regular", conversationId: "c-42", agentId: "a1", state: "APPROVED" });

    await waitFor(() => expect(invalidatedKeys(invalidate)).toContain(JSON.stringify(["approval-status", "c-42"])));
    // The inbox keys were just refetched by the on-open pass, so their refetch
    // lands at the end of that window (at most INVALIDATION_WINDOW_MS later).
    await waitFor(
      () => {
        expect(invalidatedKeys(invalidate)).toContain(JSON.stringify(["pending-approvals"]));
        expect(invalidatedKeys(invalidate)).toContain(JSON.stringify(["all-group-pending-approvals"]));
      },
      { timeout: INVALIDATION_WINDOW_MS + 1500 },
    );
  });

  it("a burst of events refetches each query once now and once more after the window, not once per event", async () => {
    const { invalidate } = mount(true);
    await waitFor(() => expect(appUserEventStream().status).toBe("open"));
    // The on-open refetches start their own windows; let them lapse.
    await new Promise((resolve) => setTimeout(resolve, INVALIDATION_WINDOW_MS + 100));
    invalidate.mockClear();

    for (let i = 0; i < 10; i++) {
      pushMockUserEvent({ kind: "approval.decided", surface: "regular", conversationId: "c-9", agentId: "a1", state: "APPROVED" });
    }
    const count = () => invalidatedKeys(invalidate).filter((key: string) => key === JSON.stringify(["approval-status", "c-9"])).length;
    await waitFor(() => expect(count()).toBe(1));
    await new Promise((resolve) => setTimeout(resolve, INVALIDATION_WINDOW_MS + 200));
    expect(count()).toBe(2);
  });
});

describe("staleQueryKeys", () => {
  const base = { id: "e1", ts: 1 };

  it("a group pause refreshes that team's discussions, not a 1:1 approval status", () => {
    const keys = staleQueryKeys({ ...base, kind: "approval.pending", surface: "group", groupId: "g1", groupConversationId: "gc1" });
    expect(keys).toContainEqual(["groupConversations", "g1"]);
    expect(keys.some((key) => key[0] === "approval-status")).toBe(false);
  });

  it("a team that ended refreshes its discussions and the sidebar's team sessions", () => {
    expect(staleQueryKeys({ ...base, kind: "team.ended", groupId: "g1", state: "COMPLETED" })).toEqual([
      ["groupConversations", "g1"],
      ["chat", "workforce-sessions", "teams"],
      ["all-group-pending-approvals"],
    ]);
  });

  it("a finished job refreshes the person's chats", () => {
    expect(staleQueryKeys({ ...base, kind: "job.finished", conversationId: "c1" })).toContainEqual(["chat", "workforce-sessions", "chats"]);
  });

  it("a notification refreshes the bell", () => {
    expect(staleQueryKeys({ ...base, kind: "notification.created", notificationId: "n1" })).toEqual([["workspaces", "notifications"]]);
  });
});
