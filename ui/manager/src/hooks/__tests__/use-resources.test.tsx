import { describe, expect, it } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import {
  useResourceDescriptors,
  useResource,
  useResourceVersions,
  useUpdateResource,
  useCreateResource,
  useDeleteResource,
  useDuplicateResource,
} from "@/hooks/use-resources";

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

describe("useResourceDescriptors", () => {
  it("fetches rules descriptors", async () => {
    const { result } = renderHook(
      () => useResourceDescriptors("rules"),
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
    expect(Array.isArray(result.current.data)).toBe(true);
  });

  it("fetches llm descriptors", async () => {
    const { result } = renderHook(
      () => useResourceDescriptors("llm"),
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("handles API error", async () => {
    server.use(
      http.get("*/rulestore/rulesets/descriptors", () => {
        return HttpResponse.json({}, { status: 500 });
      })
    );
    const { result } = renderHook(
      () => useResourceDescriptors("rules"),
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("useResource", () => {
  it("fetches a single resource", async () => {
    const { result } = renderHook(
      () => useResource("rules", "beh1", 1),
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("is disabled when id is empty", () => {
    const { result } = renderHook(
      () => useResource("rules", "", 0),
      { wrapper: createWrapper() }
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useResourceVersions", () => {
  it("fetches versions for a resource", async () => {
    const { result } = renderHook(
      () => useResourceVersions("rules", "beh1"),
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("is disabled when id is empty", () => {
    const { result } = renderHook(
      () => useResourceVersions("rules", ""),
      { wrapper: createWrapper() }
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useUpdateResource", () => {
  it("updates a resource", async () => {
    const { result } = renderHook(() => useUpdateResource("rules"), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        id: "beh1",
        version: 1,
        body: { behaviorGroups: [] },
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useCreateResource", () => {
  it("creates a resource", async () => {
    const { result } = renderHook(() => useCreateResource("rules"), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        body: { behaviorGroups: [] },
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("location");
  });
});

describe("useDeleteResource", () => {
  it("deletes a resource", async () => {
    const { result } = renderHook(() => useDeleteResource("rules"), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({ id: "beh1", version: 1 });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDuplicateResource", () => {
  it("duplicates a resource", async () => {
    const { result } = renderHook(() => useDuplicateResource("rules"), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({ id: "beh1", version: 1 });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
