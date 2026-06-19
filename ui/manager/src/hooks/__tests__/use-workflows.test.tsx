import { describe, expect, it } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import {
  useWorkflowDescriptors,
  useWorkflow,
  useWorkflowVersions,
  useUpdateWorkflow,
  useCreateWorkflow,
  useDeleteWorkflow,
  useDuplicateWorkflow,
} from "@/hooks/use-workflows";

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

describe("useWorkflowDescriptors", () => {
  it("fetches workflow descriptors", async () => {
    const { result } = renderHook(() => useWorkflowDescriptors(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
    expect(Array.isArray(result.current.data)).toBe(true);
  });

  it("accepts filter parameter", async () => {
    const { result } = renderHook(() => useWorkflowDescriptors(20, 0, "test"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("handles API error", async () => {
    server.use(
      http.get("*/workflowstore/workflows/descriptors", () => {
        return HttpResponse.json({ error: "fail" }, { status: 500 });
      })
    );
    const { result } = renderHook(() => useWorkflowDescriptors(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("useWorkflow", () => {
  it("fetches a single workflow", async () => {
    const { result } = renderHook(() => useWorkflow("wf1", 2), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("is disabled when id is empty", () => {
    const { result } = renderHook(() => useWorkflow("", 0), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useWorkflowVersions", () => {
  it("fetches workflow versions", async () => {
    server.use(
      http.get("*/workflowstore/workflows/descriptors", ({ request }) => {
        const url = new URL(request.url);
        const filter = url.searchParams.get("filter");
        if (filter === "wf1") {
          return HttpResponse.json([
            {
              resource: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
              name: "WF v1",
              lastModifiedOn: Date.now() - 86400000,
            },
            {
              resource: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
              name: "WF v2",
              lastModifiedOn: Date.now(),
            },
          ]);
        }
        return HttpResponse.json([]);
      })
    );
    const { result } = renderHook(() => useWorkflowVersions("wf1"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.length).toBe(2);
    // Check shape, not exact values (MSW returns descriptors which are then mapped)
    expect(result.current.data![0]).toBeDefined();
  });

  it("is disabled when id is empty", () => {
    const { result } = renderHook(() => useWorkflowVersions(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useUpdateWorkflow", () => {
  it("updates a workflow", async () => {
    const { result } = renderHook(() => useUpdateWorkflow(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        id: "wf1",
        version: 2,
        config: { steps: [] } as never,
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useCreateWorkflow", () => {
  it("creates a workflow", async () => {
    const { result } = renderHook(() => useCreateWorkflow(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({ config: { steps: [] } as never });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("location");
  });
});

describe("useDeleteWorkflow", () => {
  it("deletes a workflow", async () => {
    const { result } = renderHook(() => useDeleteWorkflow(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({ id: "wf1", version: 2 });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDuplicateWorkflow", () => {
  it("duplicates a workflow", async () => {
    server.use(
      http.post("*/workflowstore/workflows/:id", () => {
        return new HttpResponse(null, {
          status: 201,
          headers: {
            Location: "/workflowstore/workflows/wf-new?version=1",
          },
        });
      }),
    );
    const { result } = renderHook(() => useDuplicateWorkflow(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({ id: "wf1", version: 2 });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
