import { describe, expect, it } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { useDebuggerAudit } from "@/hooks/use-debugger-audit";
import { useDebugStore } from "@/hooks/use-debug-events";
import { AUDIT_PAGE_SIZE } from "@/lib/audit-pages";

const row = (id: string) => ({ id, stepIndex: 0, cost: 0 });

describe("useDebuggerAudit", () => {
  // Both debugger tabs used to walk the whole trail themselves, and the trace
  // walked it again after every turn — up to 10,000 entries of full prompts.
  it("shares one trail between consumers and reads only the newest page after a turn", async () => {
    const skips: number[] = [];
    const full = Array.from({ length: AUDIT_PAGE_SIZE }, (_, i) => row(`e${i}`));
    server.use(
      http.get("*/auditstore/:conversationId", ({ request }) => {
        const skip = Number(new URL(request.url).searchParams.get("skip"));
        skips.push(skip);
        if (skip === 0) return HttpResponse.json(full);
        return HttpResponse.json([row("tail")]);
      })
    );
    useDebugStore.setState({ turns: [] });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );

    const first = renderHook(() => useDebuggerAudit("conv-share"), { wrapper });
    const second = renderHook(() => useDebuggerAudit("conv-share"), { wrapper });
    await waitFor(() => expect(first.result.current.data?.entries).toHaveLength(AUDIT_PAGE_SIZE + 1));
    expect(second.result.current.data?.entries).toHaveLength(AUDIT_PAGE_SIZE + 1);
    expect(skips).toEqual([0, AUDIT_PAGE_SIZE]);

    skips.length = 0;
    act(() => {
      useDebugStore.setState({
        turns: [{ turnIndex: 0, events: [], totalDurationMs: 0, startTime: 0 }],
      });
    });
    await waitFor(() => expect(skips.length).toBeGreaterThan(0));
    // Only the newest page — it overlaps what is loaded, so no re-walk.
    expect(skips.every((s) => s === 0)).toBe(true);

    first.unmount();
    second.unmount();
    useDebugStore.setState({ turns: [] });
  });
});
