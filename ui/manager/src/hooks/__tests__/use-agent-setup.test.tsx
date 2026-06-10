import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import {
  useSetupAgent,
  useCreateApiAgent,
} from "@/hooks/use-agent-setup";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterAll(() => server.close());
afterEach(() => server.resetHandlers());

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
  };
}

describe("useSetupAgent", () => {
  it("sets up an agent successfully", async () => {
    const { result } = renderHook(() => useSetupAgent(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      name: "Test Agent",
      provider: "openai",
      model: "gpt-5.4",
      systemPrompt: "You are a test agent",
      deploy: true,
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("agentId");
    expect(result.current.data).toHaveProperty("action");
  });
});

describe("useCreateApiAgent", () => {
  it("creates an API agent successfully", async () => {
    const { result } = renderHook(() => useCreateApiAgent(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      name: "API Agent",
      provider: "anthropic",
      model: "claude-sonnet-4-6",
      openApiSpec: "openapi: 3.0.0",
      deploy: true,
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveProperty("agentId");
  });
});
