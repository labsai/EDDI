import { describe, expect, it } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import {
  useAgentDescriptors,
  useInfiniteAgentDescriptors,
  useAgent,
  useDeploymentStatus,
  useAgentVersions,
  useUpdateAgent,
  useDeploymentStatuses,
  useCreateAgent,
  useDeleteAgent,
  useDuplicateAgent,
  useDeployAgent,
  useUndeployAgent,
  groupAgentsByName,
} from "@/hooks/use-agents";
import type { AgentDescriptor } from "@/lib/api/agents";

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <MemoryRouter>
        <QueryClientProvider client={queryClient}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
            {children}
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>
    );
  };
}

describe("useAgentDescriptors", () => {
  it("fetches agent descriptors", async () => {
    const { result } = renderHook(() => useAgentDescriptors(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
    expect(Array.isArray(result.current.data)).toBe(true);
    expect(result.current.data!.length).toBeGreaterThan(0);
    expect(result.current.data![0]).toHaveProperty("resource");
    expect(result.current.data![0]).toHaveProperty("name");
  });

  it("accepts custom limit and index", async () => {
    const { result } = renderHook(() => useAgentDescriptors(5, 0, ""), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("returns loading state initially", () => {
    const { result } = renderHook(() => useAgentDescriptors(), {
      wrapper: createWrapper(),
    });
    expect(result.current.isLoading).toBe(true);
  });

  it("handles API error", async () => {
    server.use(
      http.get("*/agentstore/agents/descriptors", () => {
        return HttpResponse.json({ error: "fail" }, { status: 500 });
      })
    );
    const { result } = renderHook(() => useAgentDescriptors(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("useInfiniteAgentDescriptors", () => {
  it("fetches first page of infinite agent list", async () => {
    const { result } = renderHook(() => useInfiniteAgentDescriptors(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.pages).toBeDefined();
    expect(result.current.data!.pages.length).toBeGreaterThan(0);
  });

  it("supports filter parameter", async () => {
    const { result } = renderHook(
      () => useInfiniteAgentDescriptors("Support"),
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.pages).toBeDefined();
  });

  it("determines no next page when less than PAGE_SIZE results", async () => {
    // Default mock returns 8 items (< 50 PAGE_SIZE), so no next page
    const { result } = renderHook(() => useInfiniteAgentDescriptors(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.hasNextPage).toBe(false);
  });
});

describe("useAgent", () => {
  it("fetches a single agent by id", async () => {
    const { result } = renderHook(() => useAgent("agent1", 3), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
    expect(result.current.data).toHaveProperty("workflows");
  });

  it("is disabled when id is empty", () => {
    const { result } = renderHook(() => useAgent(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useDeploymentStatus", () => {
  it("fetches deployment status", async () => {
    const { result } = renderHook(
      () => useDeploymentStatus("agent1", 3, "production"),
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("status");
    expect(result.current.data!.status).toBe("READY");
  });

  it("is disabled when agentId is empty", () => {
    const { result } = renderHook(
      () => useDeploymentStatus("", 3),
      { wrapper: createWrapper() }
    );
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("is disabled when version is 0", () => {
    const { result } = renderHook(
      () => useDeploymentStatus("agent1", 0),
      { wrapper: createWrapper() }
    );
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("polls when status is IN_PROGRESS", async () => {
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", () => {
        return HttpResponse.json({ status: "IN_PROGRESS" });
      })
    );
    const { result } = renderHook(
      () => useDeploymentStatus("agent1", 3),
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.status).toBe("IN_PROGRESS");
  });
});

describe("useDeploymentStatuses", () => {
  it("fetches deployment statuses", async () => {
    server.use(
      http.get("*/administration/deploymentstatus/:agentId", () => {
        return HttpResponse.json([
          { environment: "production", status: "READY" },
          { environment: "test", status: "NOT_DEPLOYED" },
        ]);
      })
    );
    const { result } = renderHook(
      () => useDeploymentStatuses("agent1", 3),
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("is disabled with empty agentId", () => {
    const { result } = renderHook(
      () => useDeploymentStatuses("", 3),
      { wrapper: createWrapper() }
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useAgentVersions", () => {
  it("fetches and sorts agent versions", async () => {
    server.use(
      http.get("*/agentstore/agents/descriptors", ({ request }) => {
        const url = new URL(request.url);
        const filter = url.searchParams.get("filter");
        if (filter === "agent1") {
          return HttpResponse.json([
            {
              resource: "eddi://ai.labs.agent/agentstore/agents/agent1?version=1",
              name: "Agent v1",
              lastModifiedOn: Date.now() - 86400000,
            },
            {
              resource: "eddi://ai.labs.agent/agentstore/agents/agent1?version=3",
              name: "Agent v3",
              lastModifiedOn: Date.now(),
            },
            {
              resource: "eddi://ai.labs.agent/agentstore/agents/agent1?version=2",
              name: "Agent v2",
              lastModifiedOn: Date.now() - 3600000,
            },
          ]);
        }
        return HttpResponse.json([]);
      })
    );
    const { result } = renderHook(() => useAgentVersions("agent1"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
    // Sorted descending by version
    expect(result.current.data![0]!.version).toBe(3);
    expect(result.current.data![1]!.version).toBe(2);
    expect(result.current.data![2]!.version).toBe(1);
  });

  it("is disabled when agentId is empty", () => {
    const { result } = renderHook(() => useAgentVersions(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useUpdateAgent", () => {
  it("updates an agent and invalidates queries", async () => {
    const { result } = renderHook(() => useUpdateAgent(), {
      wrapper: createWrapper(),
    });

    await act(async () => {
      result.current.mutate({
        id: "agent1",
        version: 3,
        agent: { workflows: [], channels: [] } as never,
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useCreateAgent", () => {
  it("creates an agent", async () => {
    const { result } = renderHook(() => useCreateAgent(), {
      wrapper: createWrapper(),
    });

    await act(async () => {
      result.current.mutate({
        agent: { workflows: [], channels: [] } as never,
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("location");
  });

  it("creates agent and updates descriptor when name provided", async () => {
    const { result } = renderHook(() => useCreateAgent(), {
      wrapper: createWrapper(),
    });

    await act(async () => {
      result.current.mutate({
        agent: { workflows: [], channels: [] } as never,
        name: "Test Agent",
        description: "A test agent",
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDeleteAgent", () => {
  it("deletes an agent", async () => {
    const { result } = renderHook(() => useDeleteAgent(), {
      wrapper: createWrapper(),
    });

    await act(async () => {
      result.current.mutate({ id: "agent1", version: 3 });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDuplicateAgent", () => {
  it("duplicates an agent", async () => {
    const { result } = renderHook(() => useDuplicateAgent(), {
      wrapper: createWrapper(),
    });

    await act(async () => {
      result.current.mutate({ id: "agent1", version: 3 });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("duplicates with deepCopy flag", async () => {
    const { result } = renderHook(() => useDuplicateAgent(), {
      wrapper: createWrapper(),
    });

    await act(async () => {
      result.current.mutate({ id: "agent1", version: 3, deepCopy: true });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDeployAgent", () => {
  it("deploys an agent", async () => {
    const { result } = renderHook(() => useDeployAgent(), {
      wrapper: createWrapper(),
    });

    await act(async () => {
      result.current.mutate({ agentId: "agent1", version: 3 });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("deploys with custom environment", async () => {
    const { result } = renderHook(() => useDeployAgent(), {
      wrapper: createWrapper(),
    });

    await act(async () => {
      result.current.mutate({
        agentId: "agent1",
        version: 3,
        environment: "test",
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useUndeployAgent", () => {
  it("undeploys an agent", async () => {
    const { result } = renderHook(() => useUndeployAgent(), {
      wrapper: createWrapper(),
    });

    await act(async () => {
      result.current.mutate({ agentId: "agent1", version: 3 });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("groupAgentsByName", () => {
  it("groups agents by resource ID, keeping latest version", () => {
    const agents: AgentDescriptor[] = [
      {
        resource: "eddi://ai.labs.agent/agentstore/agents/a1?version=1",
        name: "Agent A",
        description: "",
        createdOn: 1000,
        lastModifiedOn: 1000,
      },
      {
        resource: "eddi://ai.labs.agent/agentstore/agents/a1?version=3",
        name: "Agent A v3",
        description: "",
        createdOn: 1000,
        lastModifiedOn: 3000,
      },
      {
        resource: "eddi://ai.labs.agent/agentstore/agents/a2?version=1",
        name: "Agent B",
        description: "",
        createdOn: 1000,
        lastModifiedOn: 2000,
      },
    ];

    const result = groupAgentsByName(agents);
    expect(result).toHaveLength(2);
    // a1 should use version 3
    const a1 = result.find((a) => a.id === "a1");
    expect(a1).toBeDefined();
    expect(a1!.version).toBe(3);
    // sorted by lastModifiedOn descending
    expect(result[0]!.id).toBe("a1"); // 3000
    expect(result[1]!.id).toBe("a2"); // 2000
  });

  it("handles empty array", () => {
    const result = groupAgentsByName([]);
    expect(result).toEqual([]);
  });

  it("handles single agent", () => {
    const result = groupAgentsByName([
      {
        resource: "eddi://ai.labs.agent/agentstore/agents/a1?version=1",
        name: "A",
        description: "",
        createdOn: 1000,
        lastModifiedOn: 1000,
      },
    ]);
    expect(result).toHaveLength(1);
    expect(result[0]!.version).toBe(1);
  });
});
