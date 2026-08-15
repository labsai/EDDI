import { describe, it, expect, afterEach } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import {
  usePendingApprovals,
  useAllGroupPendingApprovals,
  useApprovalStatus,
  useResumeConversation,
} from "@/hooks/use-hitl";
import { useChatStore } from "@/hooks/use-chat";

afterEach(() => act(() => useChatStore.getState().reset()));

function makeWrapper() {
  const qc = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
}

describe("use-hitl", () => {
  it("lists pending approvals from the API", async () => {
    const { result } = renderHook(() => usePendingApprovals(), { wrapper: makeWrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(2);
    expect(result.current.data?.[0]?.conversationId).toBe("conv-awaiting-1");
  });

  it("lists cross-group pending approvals in a single request", async () => {
    const { result } = renderHook(() => useAllGroupPendingApprovals(), { wrapper: makeWrapper() });

    await waitFor(() => expect(result.current.data).toBeDefined());
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data?.[0]?.groupId).toBe("group1");
    expect(result.current.isError).toBe(false);
    expect(result.current.truncated).toBe(false);
  });

  it("resumes a conversation with a valid decision", async () => {
    const { result } = renderHook(() => useResumeConversation(), { wrapper: makeWrapper() });

    await act(async () => {
      await expect(
        result.current.mutateAsync({
          conversationId: "conv-awaiting-1",
          decision: { verdict: "APPROVED" },
        }),
      ).resolves.toBeUndefined();
    });
  });

  it("surfaces a 400 error when the decision note exceeds the 4096-char cap", async () => {
    const { result } = renderHook(() => useResumeConversation(), { wrapper: makeWrapper() });

    await act(async () => {
      await expect(
        result.current.mutateAsync({
          conversationId: "conv-awaiting-1",
          decision: { verdict: "APPROVED", note: "x".repeat(5000) },
        }),
      ).rejects.toMatchObject({ status: 400 });
    });
  });

  it("clears the open conversation's chat pause banner when it is resumed", async () => {
    act(() => {
      useChatStore.getState().setConversationId("conv-awaiting-1");
      useChatStore.getState().setPaused(true, "needs sign-off");
    });
    const { result } = renderHook(() => useResumeConversation(), { wrapper: makeWrapper() });

    await act(async () => {
      await result.current.mutateAsync({
        conversationId: "conv-awaiting-1",
        decision: { verdict: "APPROVED" },
      });
    });

    await waitFor(() => expect(useChatStore.getState().isPaused).toBe(false));
  });

  it("leaves the chat pause banner untouched when a different conversation is resumed", async () => {
    act(() => {
      useChatStore.getState().setConversationId("conv-other");
      useChatStore.getState().setPaused(true, "needs sign-off");
    });
    const { result } = renderHook(() => useResumeConversation(), { wrapper: makeWrapper() });

    await act(async () => {
      await result.current.mutateAsync({
        conversationId: "conv-awaiting-1",
        decision: { verdict: "APPROVED" },
      });
    });

    expect(useChatStore.getState().isPaused).toBe(true);
  });

  it("fetches fresh approval status for each pause of the same conversation", async () => {
    // A turn may pause up to maxPausesPerTurn times (backend default 3). Keyed
    // on the conversation id alone, the second pause rendered the FIRST
    // pause's cached calls — decided buttons included — and, verified live,
    // `removeQueries` after deciding produces no refetch on an
    // actively-observed query. Pause identity in the key makes the fresh
    // fetch a cache-model guarantee rather than a removal side-effect.
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={qc}>{children}</QueryClientProvider>
    );
    const { result, rerender } = renderHook(
      ({ pauseKey }: { pauseKey: string }) =>
        useApprovalStatus("conv-awaiting-1", true, pauseKey),
      { wrapper, initialProps: { pauseKey: "2026-08-15T21:41:35Z" } },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // The turn pauses again — same conversation, new pause.
    rerender({ pauseKey: "2026-08-16T10:29:33Z" });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // One cache entry per pause: the second pause was a real fetch, not the
    // first pause's cache hit.
    const entries = qc
      .getQueryCache()
      .findAll({ queryKey: ["approval-status", "conv-awaiting-1"] });
    expect(entries).toHaveLength(2);
  });
});
