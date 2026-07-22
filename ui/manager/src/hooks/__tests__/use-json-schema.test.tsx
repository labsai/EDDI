import { describe, it, expect } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import {
  useJsonSchema,
  useAgentJsonSchema,
  useWorkflowJsonSchema,
} from "@/hooks/use-json-schema";

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
  };
}

describe("useJsonSchema", () => {
  it("fetches JSON schema for a known resource type", async () => {
    const { result } = renderHook(() => useJsonSchema("rules"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("is disabled when typeSlug is undefined", () => {
    const { result } = renderHook(() => useJsonSchema(undefined), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("errors on unknown resource type", async () => {
    const { result } = renderHook(() => useJsonSchema("nonexistent"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("useAgentJsonSchema", () => {
  it("fetches agent JSON schema", async () => {
    const { result } = renderHook(() => useAgentJsonSchema(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });
});

describe("useWorkflowJsonSchema", () => {
  it("fetches workflow JSON schema", async () => {
    const { result } = renderHook(() => useWorkflowJsonSchema(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });
});
