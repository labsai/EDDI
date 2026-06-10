import { renderHook, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { type ReactNode } from "react";
import {
  useVariables,
  useUpsertVariable,
  useDeleteVariable,
} from "@/hooks/use-variables";
import {
  useUserConversation,
  useCreateUserConversation,
  useDeleteUserConversation,
} from "@/hooks/use-user-conversations";
import {
  useSkills,
  useCapabilitySearch,
} from "@/hooks/use-capabilities";
import {
  useExtensionTypes,
} from "@/hooks/use-extensions-store";
import {
  useTemplatePreview,
} from "@/hooks/use-template-preview";
import {
  useSetupAgent,
  useCreateApiAgent,
} from "@/hooks/use-agent-setup";
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

// ─── Variables ──────────────────────────────────────────────────────

describe("useVariables", () => {
  it("fetches all variables", async () => {
    const { result } = renderHook(() => useVariables(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });
});

describe("useUpsertVariable", () => {
  it("creates or updates a variable", async () => {
    const { result } = renderHook(() => useUpsertVariable(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        key: "test-key",
        variable: { value: "test-value", description: "Test" } as never,
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDeleteVariable", () => {
  it("deletes a variable", async () => {
    const { result } = renderHook(() => useDeleteVariable(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate("test-key");
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

// ─── User Conversations ────────────────────────────────────────────

describe("useUserConversation", () => {
  it("fetches a user conversation binding", async () => {
    const { result } = renderHook(
      () => useUserConversation("booking", "user1"),
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("is disabled when intent is empty", () => {
    const { result } = renderHook(
      () => useUserConversation("", "user1"),
      { wrapper: createWrapper() }
    );
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("is disabled when userId is empty", () => {
    const { result } = renderHook(
      () => useUserConversation("booking", ""),
      { wrapper: createWrapper() }
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useCreateUserConversation", () => {
  it("creates a user conversation", async () => {
    const { result } = renderHook(() => useCreateUserConversation(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        intent: "booking",
        userId: "user1",
        data: {
          intent: "booking",
          userId: "user1",
          environment: "production",
          agentId: "agent1",
          conversationId: "conv1",
        },
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDeleteUserConversation", () => {
  it("deletes a user conversation", async () => {
    const { result } = renderHook(() => useDeleteUserConversation(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({ intent: "booking", userId: "user1" });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

// ─── Capabilities ──────────────────────────────────────────────────

describe("useSkills", () => {
  it("fetches all skills", async () => {
    const { result } = renderHook(() => useSkills(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
    expect(result.current.data!.length).toBeGreaterThan(0);
  });
});

describe("useCapabilitySearch", () => {
  it("searches capabilities by skill", async () => {
    const { result } = renderHook(
      () => useCapabilitySearch("customer-support"),
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("is disabled when skill is empty", () => {
    const { result } = renderHook(
      () => useCapabilitySearch(""),
      { wrapper: createWrapper() }
    );
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("is disabled when skill is whitespace", () => {
    const { result } = renderHook(
      () => useCapabilitySearch("   "),
      { wrapper: createWrapper() }
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

// ─── Extension Store ────────────────────────────────────────────────

describe("useExtensionTypes", () => {
  it("fetches extension types", async () => {
    const { result } = renderHook(() => useExtensionTypes(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
    // Should include the MCP Calls well-known type
    const mcpType = result.current.data!.find(
      (t) => t.type === "eddi://ai.labs.mcpcalls"
    );
    expect(mcpType).toBeDefined();
  });

  it("merges well-known types when not returned by backend", async () => {
    // Default handler returns types without mcpcalls
    server.use(
      http.get("*/extensionstore/extensions", () => {
        return HttpResponse.json([
          { type: "eddi://ai.labs.rules", displayName: "Rules", configs: {}, extensions: {} },
        ]);
      })
    );
    const { result } = renderHook(() => useExtensionTypes(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const mcpType = result.current.data!.find(
      (t) => t.type === "eddi://ai.labs.mcpcalls"
    );
    expect(mcpType).toBeDefined();
  });

  it("does not duplicate well-known types when backend returns them", async () => {
    server.use(
      http.get("*/extensionstore/extensions", () => {
        return HttpResponse.json([
          { type: "eddi://ai.labs.mcpcalls", displayName: "MCP", configs: {}, extensions: {} },
        ]);
      })
    );
    const { result } = renderHook(() => useExtensionTypes(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const mcpTypes = result.current.data!.filter(
      (t) => t.type === "eddi://ai.labs.mcpcalls"
    );
    expect(mcpTypes).toHaveLength(1);
  });
});

// ─── Template Preview ───────────────────────────────────────────────

describe("useTemplatePreview", () => {
  it("previews a template", async () => {
    server.use(
      http.post("*/template/preview", () => {
        return HttpResponse.json({
          rendered: "Hello World",
          success: true,
        });
      })
    );
    const { result } = renderHook(() => useTemplatePreview(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        template: "Hello {name}",
        conversationId: "conv1",
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("resolved");
  });
});

// ─── Agent Setup ────────────────────────────────────────────────────

describe("useSetupAgent", () => {
  it("sets up an agent", async () => {
    server.use(
      http.post("*/agentsetup", () => {
        return HttpResponse.json({
          agentId: "new-agent",
          agentVersion: 1,
          workflowId: "wf-new",
          workflowVersion: 1,
        });
      })
    );
    const { result } = renderHook(() => useSetupAgent(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        name: "Test Agent",
        description: "Test",
        language: "en",
      } as never);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("agentId");
  });
});

describe("useCreateApiAgent", () => {
  it("creates an API agent", async () => {
    server.use(
      http.post("*/agentsetup/api", () => {
        return HttpResponse.json({
          agentId: "api-agent",
          agentVersion: 1,
          workflowId: "wf-api",
          workflowVersion: 1,
        });
      })
    );
    const { result } = renderHook(() => useCreateApiAgent(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        name: "API Agent",
        description: "Test",
      } as never);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
