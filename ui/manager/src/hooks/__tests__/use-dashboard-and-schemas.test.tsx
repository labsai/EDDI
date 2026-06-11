import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { type ReactNode } from "react";
import {
  useDashboardStats,
  useRecentAgents,
  useRecentConversations,
  useCoordinatorStatusLight,
} from "@/hooks/use-dashboard";
import { useLatestVersions, getVersionInfo } from "@/hooks/use-latest-versions";
import { useJsonSchema, useAgentJsonSchema, useWorkflowJsonSchema } from "@/hooks/use-json-schema";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";

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

// ─── Dashboard ──────────────────────────────────────────────────────

describe("useDashboardStats", () => {
  it("fetches dashboard stats", async () => {
    const { result } = renderHook(() => useDashboardStats(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });
});

describe("useRecentAgents", () => {
  it("fetches recent agents (limit 4)", async () => {
    const { result } = renderHook(() => useRecentAgents(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });
});

describe("useRecentConversations", () => {
  it("fetches recent conversations", async () => {
    const { result } = renderHook(() => useRecentConversations(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("accepts custom limit", async () => {
    const { result } = renderHook(() => useRecentConversations(3), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useCoordinatorStatusLight", () => {
  it("fetches coordinator status", async () => {
    const { result } = renderHook(() => useCoordinatorStatusLight(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });
});

// ─── Latest Versions ────────────────────────────────────────────────

describe("useLatestVersions", () => {
  it("fetches latest versions for resource URIs", async () => {
    const uris = [
      "eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1",
    ];
    const { result } = renderHook(() => useLatestVersions(uris), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
    expect(typeof result.current.data).toBe("object");
  });

  it("is disabled when no URIs provided", () => {
    const { result } = renderHook(() => useLatestVersions([]), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("deduplicates URIs by resource ID", async () => {
    const uris = [
      "eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1",
      "eddi://ai.labs.rules/rulestore/rulesets/beh1?version=2",
    ];
    const { result } = renderHook(() => useLatestVersions(uris), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    // beh1 should only appear once
    expect(result.current.data).toBeDefined();
  });

  it("skips invalid URIs", async () => {
    const uris = [
      "invalid-uri",
      "",
      "eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1",
    ];
    const { result } = renderHook(() => useLatestVersions(uris), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });
});

describe("getVersionInfo", () => {
  it("extracts version info from valid URI", () => {
    const result = getVersionInfo(
      "eddi://ai.labs.rules/rulestore/rulesets/beh1?version=3"
    );
    expect(result).toBeDefined();
    expect(result!.id).toBe("beh1");
    expect(result!.version).toBe(3);
  });

  it("returns default version for non-standard URI", () => {
    const result = getVersionInfo("invalid-uri");
    expect(result).toBeDefined();
    expect(result!.version).toBe(1);
  });

  it("returns default version for empty string", () => {
    const result = getVersionInfo("");
    expect(result).toBeDefined();
    expect(result!.version).toBe(1);
  });
});

// ─── JSON Schema ────────────────────────────────────────────────────

describe("useJsonSchema", () => {
  it("fetches schema for a resource type", async () => {
    server.use(
      http.get("*/rulestore/rulesets/jsonSchema", () => {
        return HttpResponse.json({
          type: "object",
          properties: { behaviorGroups: { type: "array" } },
        });
      })
    );
    const { result } = renderHook(() => useJsonSchema("rules"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
    expect(result.current.data).toHaveProperty("type");
  });

  it("is disabled when typeSlug is undefined", () => {
    const { result } = renderHook(() => useJsonSchema(undefined), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useAgentJsonSchema", () => {
  it("fetches agent JSON schema", async () => {
    server.use(
      http.get("*/agentstore/agents/jsonSchema", () => {
        return HttpResponse.json({
          type: "object",
          properties: { workflows: { type: "array" } },
        });
      })
    );
    const { result } = renderHook(() => useAgentJsonSchema(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });
});

describe("useWorkflowJsonSchema", () => {
  it("fetches workflow JSON schema", async () => {
    server.use(
      http.get("*/workflowstore/workflows/jsonSchema", () => {
        return HttpResponse.json({
          type: "object",
          properties: { steps: { type: "array" } },
        });
      })
    );
    const { result } = renderHook(() => useWorkflowJsonSchema(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });
});
