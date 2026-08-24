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

    // `toBeDefined()` alone was satisfied by whatever came back, and what came
    // back was a RULESET: the `*/rulestore/rulesets/:id` handler matched
    // "jsonSchema" as an id and answered with a config document. This hook is
    // for driving a schema-based form, so assert it is a schema.
    const schema = result.current.data as Record<string, unknown>;
    expect(schema).toHaveProperty("type", "object");
    expect(schema).toHaveProperty("properties");
    expect(schema).not.toHaveProperty("behaviorGroups");
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
