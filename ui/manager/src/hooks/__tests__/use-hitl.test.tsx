import { describe, it, expect } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import {
  usePendingApprovals,
  useAllGroupPendingApprovals,
  useResumeConversation,
} from "@/hooks/use-hitl";

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
});
