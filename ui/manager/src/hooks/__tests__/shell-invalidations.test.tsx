import { describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

/**
 * Stale caches after writes that touch more than one kind of document.
 *
 * Import / merge / upgrade / sync create or bump the agent AND its workflows and
 * resources, yet invalidated only the agent queries. Operator activate / reset /
 * deactivate create, delete or redeploy an agent and rewrite a global variable,
 * yet invalidated only the operator's own queries — so the Agents list kept a
 * deleted operator on screen, every action on it a 404.
 */

vi.mock("@/lib/api/backup", () => ({
  importAgent: vi.fn(async () => ({})),
  importAgentMerge: vi.fn(async () => ({})),
  importAgentUpgrade: vi.fn(async () => ({})),
  executeSync: vi.fn(async () => ({})),
  executeSyncBatch: vi.fn(async () => ({})),
  exportAndDownloadAgent: vi.fn(),
  exportAgentSelective: vi.fn(),
  previewImport: vi.fn(),
  previewExport: vi.fn(),
  previewUpgrade: vi.fn(),
  listRemoteAgents: vi.fn(),
  previewSync: vi.fn(),
  previewSyncBatch: vi.fn(),
}));

vi.mock("@/lib/api/operator", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/operator")>();
  return {
    ...actual,
    resetOperator: vi.fn(async () => {}),
    deactivateOperator: vi.fn(async () => {}),
    reactivateOperator: vi.fn(async () => {}),
  };
});

import {
  useExecuteSync,
  useExecuteSyncBatch,
  useImportAgent,
  useImportAgentMerge,
  useImportUpgrade,
} from "@/hooks/use-backup";
import {
  useDeactivateOperator,
  useReactivateOperator,
  useResetOperator,
} from "@/hooks/use-operator";

function setup() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const spy = vi.spyOn(queryClient, "invalidateQueries");
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  const invalidated = () =>
    spy.mock.calls.map(([filters]) => JSON.stringify(filters?.queryKey));
  return { wrapper, invalidated };
}

const file = new File(["zip"], "agent.zip");

/** The table rows mix hooks with different variable types; only mutateAsync matters. */
type AnyMutationHook = () => { mutateAsync: (variables: never) => Promise<unknown> };

describe("import, merge, upgrade and sync invalidations", () => {
  it.each([
    ["import", () => useImportAgent(), file],
    ["merge", () => useImportAgentMerge(), { file }],
    ["upgrade", () => useImportUpgrade(), { file, targetAgentId: "a1" }],
    [
      "sync",
      () => useExecuteSync(),
      {
        sourceUrl: "https://src",
        sourceAgentId: "a1",
        sourceVersion: 1,
        targetAgentId: null,
        selectedResources: null,
        workflowOrder: null,
        sourceAuth: "",
      },
    ],
    ["sync batch", () => useExecuteSyncBatch(), { sourceUrl: "https://src", requests: [], sourceAuth: "" }],
  ] as const)("%s refreshes agents, workflows AND resources", async (_name, hook, vars) => {
    const { wrapper, invalidated } = setup();
    const { result } = renderHook(hook as AnyMutationHook, { wrapper });
    await act(async () => {
      await result.current.mutateAsync(vars as never);
    });
    const keys = invalidated();
    expect(keys).toContain('["agents"]');
    expect(keys).toContain('["workflows"]');
    expect(keys).toContain('["resources"]');
    expect(keys).toContain('["agent"]');
    // A ZIP also carries schedules and connections.
    expect(keys).toContain('["schedules"]');
    expect(keys).toContain('["connections"]');
  });
});

describe("operator lifecycle invalidations", () => {
  const config = { agentId: "op-1", version: 1 } as never;

  it.each([
    ["reset", () => useResetOperator()],
    ["deactivate", () => useDeactivateOperator()],
    ["reactivate", () => useReactivateOperator()],
  ] as const)("%s refreshes the agent lists and variables, not only the operator", async (_n, hook) => {
    const { wrapper, invalidated } = setup();
    const { result } = renderHook(hook as AnyMutationHook, { wrapper });
    await act(async () => {
      await result.current.mutateAsync(config);
    });
    const keys = invalidated();
    expect(keys).toContain('["operator"]');
    expect(keys).toContain('["agents"]');
    expect(keys).toContain('["variables"]');
    expect(keys).toContain('["chat","deployedAgents"]');
  });
});
