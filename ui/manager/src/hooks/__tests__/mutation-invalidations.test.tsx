import { describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

vi.mock("@eddi/app-kit/lib/api/agents", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@eddi/app-kit/lib/api/agents")>()),
  deleteAgent: vi.fn(async () => undefined),
  duplicateAgent: vi.fn(async () => ({ location: "/x/2" })),
}));
vi.mock("@eddi/app-kit/lib/api/agent-setup", () => ({
  setupAgent: vi.fn(async () => ({})),
  createApiAgent: vi.fn(async () => ({})),
}));
vi.mock("@eddi/app-kit/lib/api/conversations", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@eddi/app-kit/lib/api/conversations")>()),
  deleteConversation: vi.fn(async () => undefined),
}));

import { useDeleteAgent, useDuplicateAgent } from "@eddi/app-kit/hooks/use-agents";
import { useSetupAgent, useCreateApiAgent } from "@eddi/app-kit/hooks/use-agent-setup";
import { useDeleteConversation } from "@/hooks/use-conversations";
import { AGENT_MUTATION_INVALIDATIONS, CONVERSATION_MUTATION_INVALIDATIONS, chatKeys } from "@eddi/app-kit/lib/query-keys";

/**
 * After a mutation every cache that shows what it changed is refreshed, not just
 * the list it was started from: deleting or duplicating an agent left the chat's
 * deployed-agents list (60 s) and the dashboard counts (30 s) stale, and deleting
 * a conversation left the dashboard's "recent conversations" card showing it.
 */
function setup() {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  const invalidated: unknown[][] = [];
  vi.spyOn(client, "invalidateQueries").mockImplementation(((filters: { queryKey?: unknown[] }) => {
    invalidated.push([...(filters.queryKey ?? [])]);
    return Promise.resolve();
  }) as never);
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  return { wrapper, invalidated };
}

const has = (invalidated: unknown[][], key: readonly unknown[]) =>
  invalidated.some((k) => JSON.stringify(k) === JSON.stringify(key));

describe("what a mutation refreshes", () => {
  it("names the agent caches: lists, the chat's deployed agents, the dashboard counts", () => {
    expect(AGENT_MUTATION_INVALIDATIONS).toEqual([["agents"], chatKeys.deployedAgents, ["dashboard", "stats"]]);
  });

  it.each([
    ["delete agent", () => useDeleteAgent(), { id: "a", version: 1 }],
    ["duplicate agent", () => useDuplicateAgent(), { id: "a", version: 1 }],
    ["setup agent", () => useSetupAgent(), {}],
    ["create API agent", () => useCreateApiAgent(), {}],
  ] as const)("%s refreshes every agent cache", async (_name, hook, variables) => {
    const { wrapper, invalidated } = setup();
    const { result } = renderHook(hook as () => { mutateAsync: (v: unknown) => Promise<unknown> }, { wrapper });
    await act(async () => {
      await result.current.mutateAsync(variables);
    });
    for (const key of AGENT_MUTATION_INVALIDATIONS) expect(has(invalidated, key), JSON.stringify(key)).toBe(true);
  });

  it("deleting a conversation refreshes the lists and the dashboard's recent card", async () => {
    const { wrapper, invalidated } = setup();
    const { result } = renderHook(() => useDeleteConversation(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ id: "c1" });
    });
    for (const key of CONVERSATION_MUTATION_INVALIDATIONS) expect(has(invalidated, key), JSON.stringify(key)).toBe(true);
    expect(has(invalidated, ["dashboard", "recent-conversations"])).toBe(true);
  });
});
