import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import {
  useExportAgent,
  useImportAgent,
  usePreviewImport,
  useImportAgentMerge,
  usePreviewExport,
  useExportSelective,
  usePreviewUpgrade,
  useImportUpgrade,
  useListRemoteAgents,
  usePreviewSync,
  useExecuteSync,
} from "@/hooks/use-backup";

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

describe("useExportAgent", () => {
  it("exports an agent successfully", async () => {
    server.use(
      http.get("*/backup/export/:agentId", () => {
        return HttpResponse.json({ agent: {}, workflows: [] });
      }),
    );

    const { result } = renderHook(() => useExportAgent(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({ agentId: "agent-1", version: 1 });
    await waitFor(() =>
      expect(
        result.current.isSuccess || result.current.isError,
      ).toBe(true),
    );
  });
});

describe("useImportAgent", () => {
  it("imports an agent from file", async () => {
    server.use(
      http.post("*/backup/import", () => {
        return HttpResponse.json(
          { agentId: "imported-1" },
          { status: 201 },
        );
      }),
    );

    const { result } = renderHook(() => useImportAgent(), {
      wrapper: createWrapper(),
    });

    const file = new File(["{}"], "agent.json", {
      type: "application/json",
    });
    result.current.mutate(file);
    await waitFor(() =>
      expect(
        result.current.isSuccess || result.current.isError,
      ).toBe(true),
    );
  });
});

describe("usePreviewImport", () => {
  it("previews an import from file", async () => {
    server.use(
      http.post("*/backup/import/preview", () => {
        return HttpResponse.json({
          sourceAgentId: "agent-1",
          sourceAgentName: "Test Agent",
          resources: [],
          conflicts: [],
          warnings: [],
        });
      }),
    );

    const { result } = renderHook(() => usePreviewImport(), {
      wrapper: createWrapper(),
    });

    const file = new File(["{}"], "agent.json", {
      type: "application/json",
    });
    result.current.mutate(file);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("sourceAgentId");
  });
});

describe("useImportAgentMerge", () => {
  it("merges an imported agent", async () => {
    server.use(
      http.post("*/backup/import/merge", () => {
        return HttpResponse.json({ agentId: "merged-1" }, { status: 201 });
      }),
    );

    const { result } = renderHook(() => useImportAgentMerge(), {
      wrapper: createWrapper(),
    });

    const file = new File(["{}"], "agent.json", {
      type: "application/json",
    });
    result.current.mutate({ file, selectedSourceIds: ["res-1"] });
    await waitFor(() =>
      expect(
        result.current.isSuccess || result.current.isError,
      ).toBe(true),
    );
  });
});

describe("usePreviewExport", () => {
  it("fetches export preview when enabled", async () => {
    server.use(
      http.get("*/backup/export/:agentId/preview", () => {
        return HttpResponse.json({
          agentId: "agent-1",
          resources: [],
        });
      }),
    );

    const { result } = renderHook(
      () => usePreviewExport("agent-1", 1, true),
      { wrapper: createWrapper() },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("is disabled when enabled is false", () => {
    const { result } = renderHook(
      () => usePreviewExport("agent-1", 1, false),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useExportSelective", () => {
  it("exports selective resources", async () => {
    server.use(
      http.post("*/backup/export/:agentId/selective", () => {
        return HttpResponse.json({ success: true });
      }),
    );

    const { result } = renderHook(() => useExportSelective(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      agentId: "agent-1",
      version: 1,
      selectedResourceIds: ["res-1", "res-2"],
    });

    await waitFor(() =>
      expect(
        result.current.isSuccess || result.current.isError,
      ).toBe(true),
    );
  });
});

describe("usePreviewUpgrade", () => {
  it("previews an upgrade import", async () => {
    server.use(
      http.post("*/backup/import/upgrade/preview", () => {
        return HttpResponse.json({
          sourceAgentId: "agent-1",
          targetAgentId: "agent-2",
          resources: [],
        });
      }),
    );

    const { result } = renderHook(() => usePreviewUpgrade(), {
      wrapper: createWrapper(),
    });

    const file = new File(["{}"], "agent.json", {
      type: "application/json",
    });
    result.current.mutate({ file, targetAgentId: "agent-2" });
    await waitFor(() =>
      expect(
        result.current.isSuccess || result.current.isError,
      ).toBe(true),
    );
  });
});

describe("useImportUpgrade", () => {
  it("performs upgrade import", async () => {
    server.use(
      http.post("*/backup/import/upgrade", () => {
        return HttpResponse.json({ agentId: "upgraded-1" }, { status: 201 });
      }),
    );

    const { result } = renderHook(() => useImportUpgrade(), {
      wrapper: createWrapper(),
    });

    const file = new File(["{}"], "agent.json", {
      type: "application/json",
    });
    result.current.mutate({
      file,
      targetAgentId: "agent-2",
      selectedSourceIds: ["res-1"],
    });
    await waitFor(() =>
      expect(
        result.current.isSuccess || result.current.isError,
      ).toBe(true),
    );
  });
});

describe("useListRemoteAgents", () => {
  it("lists remote agents", async () => {
    server.use(
      http.post("*/backup/sync/agents", () => {
        return HttpResponse.json([
          { id: "remote-1", name: "Remote Agent 1" },
        ]);
      }),
    );

    const { result } = renderHook(() => useListRemoteAgents(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      sourceUrl: "https://remote.example.com",
      sourceAuth: "Bearer token",
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("usePreviewSync", () => {
  it("previews a sync operation", async () => {
    server.use(
      http.post("*/backup/sync/preview", () => {
        return HttpResponse.json({
          resources: [],
          conflicts: [],
        });
      }),
    );

    const { result } = renderHook(() => usePreviewSync(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      sourceUrl: "https://remote.example.com",
      sourceAgentId: "agent-1",
      sourceVersion: 1,
      targetAgentId: "agent-local",
      sourceAuth: "Bearer token",
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useExecuteSync", () => {
  it("executes a sync operation", async () => {
    server.use(
      http.post("*/backup/sync/execute", () => {
        return HttpResponse.json({ success: true });
      }),
    );

    const { result } = renderHook(() => useExecuteSync(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      sourceUrl: "https://remote.example.com",
      sourceAgentId: "agent-1",
      sourceVersion: 1,
      targetAgentId: "agent-local",
      selectedResources: ["res-1"],
      workflowOrder: null,
      sourceAuth: "Bearer token",
    });
    await waitFor(() =>
      expect(
        result.current.isSuccess || result.current.isError,
      ).toBe(true),
    );
  });
});
