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
  useAllAgentDescriptors,
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

  it("asks for page 1 — a page INDEX — after a full page of 50", async () => {
    // DescriptorStore skips `index * limit` rows. Sending the row offset (50)
    // as the index skipped 2,500 rows, so the list stopped at 50 agents.
    const agents = Array.from({ length: 60 }, (_, i) => ({
      resource: `eddi://ai.labs.agent/agentstore/agents/a${i}?version=1`,
      name: `Agent ${i}`,
      description: "",
      createdOn: 0,
      lastModifiedOn: 0,
    }));
    const indexes: number[] = [];
    server.use(
      http.get("*/agentstore/agents/descriptors", ({ request }) => {
        const url = new URL(request.url);
        const limit = Number(url.searchParams.get("limit"));
        const index = Number(url.searchParams.get("index"));
        indexes.push(index);
        return HttpResponse.json(agents.slice(index * limit, index * limit + limit));
      }),
    );
    const { result } = renderHook(() => useInfiniteAgentDescriptors(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.hasNextPage).toBe(true));
    await act(async () => {
      await result.current.fetchNextPage();
    });
    await waitFor(() => expect(result.current.data!.pages).toHaveLength(2));
    expect(indexes).toEqual([0, 1]);
    expect(result.current.data!.pages.flat()).toHaveLength(60);
    expect(result.current.hasNextPage).toBe(false);
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

describe("useAllAgentDescriptors", () => {
  it("keeps paging until the list is complete, so pickers and sync matching see every agent", async () => {
    const agents = Array.from({ length: 120 }, (_, i) => ({
      resource: `eddi://ai.labs.agent/agentstore/agents/a${i}?version=1`,
      name: `Agent ${i}`,
      description: "",
      createdOn: 0,
      lastModifiedOn: 0,
    }));
    server.use(
      http.get("*/agentstore/agents/descriptors", ({ request }) => {
        const url = new URL(request.url);
        const limit = Number(url.searchParams.get("limit"));
        const index = Number(url.searchParams.get("index"));
        return HttpResponse.json(agents.slice(index * limit, index * limit + limit));
      }),
    );
    const { result } = renderHook(() => useAllAgentDescriptors(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isComplete).toBe(true));
    expect(result.current.data!.pages.flat()).toHaveLength(120);
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

  it("reports an environment still serving an OLDER version as live, with that version", async () => {
    // A save bumps the agent to v4; production still runs v3. The per-version
    // endpoint says NOT_FOUND for v4, which used to render "Not deployed".
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", () =>
        HttpResponse.json({ status: "NOT_FOUND" }),
      ),
      http.get("*/administration/:env/deploymentstatus", ({ params }) =>
        HttpResponse.json(
          params.env === "production"
            ? [{ environment: "production", agentId: "agent1", agentVersion: 3, status: "READY" }]
            : [],
        ),
      ),
    );
    const { result } = renderHook(() => useDeploymentStatuses("agent1", 4), {
      wrapper: createWrapper(),
    });
    await waitFor(() =>
      expect(result.current.data?.find((s) => s.environment === "production")?.status).toBe("READY"),
    );
    expect(result.current.data).toEqual([
      { environment: "production", status: "READY", deployedVersion: 3 },
      { environment: "test", status: "NOT_FOUND" },
    ]);
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
  it("lists every version, newest first, from per-version descriptor reads", async () => {
    // The store listing has no `version` parameter: asking it
    // `filter=agent1&version=v` returns the CURRENT descriptor every time, so
    // the picker offered only the latest version. Each version's descriptor is
    // read by id and version instead.
    server.use(
      http.get("*/agentstore/agents/:id/currentversion", () => HttpResponse.json(3)),
      http.get("*/agentstore/agents/descriptors", () =>
        HttpResponse.json([
          {
            resource: "eddi://ai.labs.agent/agentstore/agents/agent1?version=3",
            name: "Agent v3",
            lastModifiedOn: Date.now(),
          },
        ]),
      ),
      http.get("*/descriptorstore/descriptors/:id", ({ params, request }) => {
        const version = Number(new URL(request.url).searchParams.get("version"));
        return HttpResponse.json({
          resource: `eddi://ai.labs.agent/agentstore/agents/${params.id}?version=${version}`,
          name: `Agent v${version}`,
          lastModifiedOn: Date.now() - (3 - version) * 3600000,
        });
      }),
    );
    const { result } = renderHook(() => useAgentVersions("agent1"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    // Sorted descending by version
    expect(result.current.data!.map((v) => v.version)).toEqual([3, 2, 1]);
  });

  it("returns one entry per version and excludes other agents", async () => {
    // The API issues one descriptor query per version and flattens the results,
    // and `filter=` is a TEXT match. So the same version arrives more than once,
    // and an agent whose id merely contains this one does too. Rendered as
    // key={v.version}, duplicates made React log "two children with the same key"
    // and a foreign version was selectable in this agent's picker.
    server.use(
      http.get("*/agentstore/agents/descriptors", ({ request }) => {
        const filter = new URL(request.url).searchParams.get("filter");
        if (filter !== "agent1") return HttpResponse.json([]);
        return HttpResponse.json([
          {
            resource: "eddi://ai.labs.agent/agentstore/agents/agent1?version=1",
            name: "Agent v1",
            lastModifiedOn: 1000,
          },
          // Same version again, as a second per-version query would return it.
          {
            resource: "eddi://ai.labs.agent/agentstore/agents/agent1?version=1",
            name: "Agent v1 again",
            lastModifiedOn: 1000,
          },
          // A different agent matching the text filter by substring.
          {
            resource: "eddi://ai.labs.agent/agentstore/agents/agent12?version=9",
            name: "Unrelated agent",
            lastModifiedOn: 2000,
          },
        ]);
      })
    );

    const { result } = renderHook(() => useAgentVersions("agent1"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const versions = result.current.data!.map((v) => v.version);
    expect(versions).toEqual([1]);
    // Keys must be unique, since the version picker keys options by version.
    expect(new Set(versions).size).toBe(versions.length);
    expect(versions).not.toContain(9);
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

  it("appends the destructive query flags when the options are passed", async () => {
    let captured: URL | null = null;
    server.use(
      http.post("*/administration/:env/undeploy/:id", ({ request }) => {
        captured = new URL(request.url);
        return new HttpResponse(null, { status: 200 });
      })
    );

    const { result } = renderHook(() => useUndeployAgent(), {
      wrapper: createWrapper(),
    });

    await act(async () => {
      result.current.mutate({
        agentId: "agent1",
        version: 3,
        endAllActiveConversations: true,
        undeployAllPreviousVersions: true,
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(captured).not.toBeNull();
    expect(captured!.searchParams.get("version")).toBe("3");
    expect(captured!.searchParams.get("endAllActiveConversations")).toBe("true");
    expect(captured!.searchParams.get("undeployThisAndAllPreviousAgentVersions")).toBe("true");
  });

  it("omits the destructive query flags when the options are not passed", async () => {
    let captured: URL | null = null;
    server.use(
      http.post("*/administration/:env/undeploy/:id", ({ request }) => {
        captured = new URL(request.url);
        return new HttpResponse(null, { status: 200 });
      })
    );

    const { result } = renderHook(() => useUndeployAgent(), {
      wrapper: createWrapper(),
    });

    await act(async () => {
      result.current.mutate({ agentId: "agent1", version: 3 });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(captured).not.toBeNull();
    expect(captured!.searchParams.has("endAllActiveConversations")).toBe(false);
    expect(captured!.searchParams.has("undeployThisAndAllPreviousAgentVersions")).toBe(false);
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
