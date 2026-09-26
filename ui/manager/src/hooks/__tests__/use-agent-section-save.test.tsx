import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { server } from "@/test/mocks/server";
import { useAgentSectionSave } from "@/hooks/use-agent-section-save";
import type { Agent } from "@/lib/api/agents";

vi.mock("sonner", async () => {
  const actual = await vi.importActual<typeof import("sonner")>("sonner");
  return { ...actual, toast: { ...actual.toast, error: vi.fn(), success: vi.fn() } };
});
import { toast } from "sonner";

/**
 * The agent detail page's sections write the whole agent from the `agent` and
 * `version` the page passed down, and the page learns a save's new version only
 * when its queries refetch. A second edit in that window went to the superseded
 * version, 409'd, and — with no error handler in most sections — vanished.
 */

let current: number;
let docs: Record<number, Agent>;
let puts: number[];

beforeEach(() => {
  vi.mocked(toast.error).mockReset();
  current = 1;
  docs = { 1: { description: "d", capabilities: [] } };
  puts = [];
  server.use(
    http.put("*/agentstore/agents/a1", async ({ request }) => {
      const v = Number(new URL(request.url).searchParams.get("version"));
      puts.push(v);
      if (v !== current) return HttpResponse.json({ message: "Conflict" }, { status: 409 });
      current += 1;
      docs[current] = (await request.json()) as Agent;
      return new HttpResponse(null, {
        status: 200,
        headers: { Location: `eddi://ai.labs.agent/agentstore/agents/a1?version=${current}` },
      });
    }),
  );
});

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

function renderSaver(version: number, agent: Agent) {
  return renderHook(
    ({ v, a }: { v: number; a: Agent }) => useAgentSectionSave("a1", v, a),
    { wrapper, initialProps: { v: version, a: agent } },
  );
}

describe("useAgentSectionSave", () => {
  it("chains a second edit onto the version the first one created, keeping both", async () => {
    const page = docs[1]!;
    const { result } = renderSaver(1, page);

    // Two edits from the SAME render — the page has not caught up in between.
    act(() => {
      result.current.mutate({ agent: { ...page, a2aEnabled: true } });
      result.current.mutate({ agent: { ...page, description: "changed" } });
    });

    await waitFor(() => expect(current).toBe(3));
    expect(puts).toEqual([1, 2]);
    expect(docs[3]).toMatchObject({ a2aEnabled: true, description: "changed" });
    expect(toast.error).not.toHaveBeenCalled();
  });

  it("drops a field the later edit removed", async () => {
    const page: Agent = { ...docs[1]!, hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY" } };
    docs[1] = page;
    const { result } = renderSaver(1, page);

    const withoutHitl = { ...page };
    delete withoutHitl.hitlConfig;
    act(() => {
      result.current.mutate({ agent: { ...page, a2aEnabled: true } });
      result.current.mutate({ agent: withoutHitl });
    });

    await waitFor(() => expect(current).toBe(3));
    expect(docs[3]!.a2aEnabled).toBe(true);
    expect(docs[3]).not.toHaveProperty("hitlConfig");
  });

  it("follows the page once it shows a version newer than the last save", async () => {
    const { result, rerender } = renderSaver(1, docs[1]!);
    act(() => result.current.mutate({ agent: { ...docs[1]!, a2aEnabled: true } }));
    await waitFor(() => expect(current).toBe(2));

    // Someone else saved v3; the page refetched and shows it.
    docs[3] = { description: "elsewhere" };
    current = 3;
    rerender({ v: 3, a: docs[3] });
    act(() => result.current.mutate({ agent: { ...docs[3]!, a2aEnabled: false } }));

    await waitFor(() => expect(current).toBe(4));
    expect(puts).toEqual([1, 3]);
    expect(docs[4]).toEqual({ description: "elsewhere", a2aEnabled: false });
  });

  it("reports a failed save instead of dropping it", async () => {
    current = 7; // the page's version 1 is long superseded
    const { result } = renderSaver(1, docs[1]!);

    act(() => result.current.mutate({ agent: { ...docs[1]!, a2aEnabled: true } }));

    await waitFor(() => expect(toast.error).toHaveBeenCalledTimes(1));
    expect(vi.mocked(toast.error).mock.calls[0]![0]).toMatch(/409/);
  });

  it("hands a failure to the caller's onError instead of toasting", async () => {
    current = 7;
    const onError = vi.fn();
    const { result } = renderSaver(1, docs[1]!);

    act(() => result.current.mutate({ agent: { ...docs[1]!, a2aEnabled: true } }, { onError }));

    await waitFor(() => expect(onError).toHaveBeenCalledTimes(1));
    expect(toast.error).not.toHaveBeenCalled();
  });
});
