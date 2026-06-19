import { describe, expect, it } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { type ReactNode } from "react";

import {
  useSecrets,
  useStoreSecret,
  useDeleteSecret,
  useVaultHealth,
  useRotateSecret,
} from "@/hooks/use-secrets";

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

describe("useSecrets", () => {
  it("fetches secrets for a tenant", async () => {
    const { result } = renderHook(() => useSecrets("default"), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });

  it("is disabled when tenantId is empty", () => {
    const { result } = renderHook(() => useSecrets(""), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useStoreSecret", () => {
  it("stores a secret", async () => {
    const { result } = renderHook(() => useStoreSecret(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        tenantId: "default",
        keyName: "api-key",
        value: "secret123",
        description: "API key",
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("stores a secret with allowed agents", async () => {
    const { result } = renderHook(() => useStoreSecret(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        tenantId: "default",
        keyName: "restricted-key",
        value: "secret456",
        allowedAgents: ["agent1", "agent2"],
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useDeleteSecret", () => {
  it("deletes a secret", async () => {
    const { result } = renderHook(() => useDeleteSecret(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({ tenantId: "default", keyName: "api-key" });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe("useVaultHealth", () => {
  it("fetches vault health", async () => {
    const { result } = renderHook(() => useVaultHealth(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });
});

describe("useRotateSecret", () => {
  it("rotates a secret", async () => {
    const { result } = renderHook(() => useRotateSecret(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        tenantId: "default",
        keyName: "api-key",
        newValue: "newSecret789",
        description: "Rotated",
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
