import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import {
  useSkills,
  useCapabilitySearch,
  useSkillRegistry,
} from "@/hooks/use-capabilities";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterAll(() => server.close());
afterEach(() => server.resetHandlers());

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

describe("useSkills", () => {
  it("fetches all skills", async () => {
    const { result } = renderHook(() => useSkills(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
    expect(result.current.data!.length).toBeGreaterThan(0);
    expect(result.current.data).toContain("customer-support");
  });
});

describe("useCapabilitySearch", () => {
  it("searches for agents by skill", async () => {
    const { result } = renderHook(
      () => useCapabilitySearch("customer-support"),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
  });

  it("is disabled when skill is empty", () => {
    const { result } = renderHook(() => useCapabilitySearch(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("is disabled when skill is whitespace", () => {
    const { result } = renderHook(() => useCapabilitySearch("   "), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("accepts optional strategy parameter", async () => {
    const { result } = renderHook(
      () => useCapabilitySearch("faq", "all"),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useSkillRegistry", () => {
  it("returns registry shape and helper functions", async () => {
    const { result } = renderHook(() => useSkillRegistry(), {
      wrapper: createWrapper(),
    });

    // Initially loading
    expect(result.current.isLoading).toBe(true);
    expect(Array.isArray(result.current.registry)).toBe(true);
    expect(typeof result.current.refetchSkills).toBe("function");
    expect(typeof result.current.refetchMatches).toBe("function");
  });
});

