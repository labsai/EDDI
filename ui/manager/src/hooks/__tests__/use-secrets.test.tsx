import { describe, expect, it } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { SECRET_EXISTS, SECRET_NOT_FOUND } from "@/lib/api/secrets";

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
  it("rotates an existing secret, re-reading its grant first", async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.put("*/secretstore/secrets/:tenantId/:keyName", async ({ request, params }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ reference: "r", tenantId: params.tenantId, keyName: params.keyName });
      }),
    );
    const { result } = renderHook(() => useRotateSecret(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        tenantId: "default",
        keyName: "sendgrid-api-key",
        newValue: "newSecret789",
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    // From the fresh listing, not from anything the caller passed.
    expect(body).toEqual({
      value: "newSecret789",
      allowedAgents: ["agent1", "agent4"],
      description: "SendGrid transactional email service key",
    });
  });

  it("refuses to rotate a key that no longer exists rather than re-creating it", async () => {
    let stored = false;
    server.use(
      http.put("*/secretstore/secrets/:tenantId/:keyName", () => {
        stored = true;
        return HttpResponse.json({});
      }),
    );
    const { result } = renderHook(() => useRotateSecret(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({ tenantId: "default", keyName: "deleted-key", newValue: "v" });
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toMatchObject({ code: SECRET_NOT_FOUND });
    expect(stored).toBe(false);
  });
});

describe("useStoreSecret createOnly", () => {
  it("refuses an existing key without writing", async () => {
    let stored = false;
    server.use(
      http.put("*/secretstore/secrets/:tenantId/:keyName", () => {
        stored = true;
        return HttpResponse.json({});
      }),
    );
    const { result } = renderHook(() => useStoreSecret(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        tenantId: "default",
        keyName: "openai-api-key",
        value: "sk-new",
        createOnly: true,
      });
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toMatchObject({ code: SECRET_EXISTS });
    expect(stored).toBe(false);
  });

  it("stores a new key", async () => {
    const { result } = renderHook(() => useStoreSecret(), {
      wrapper: createWrapper(),
    });
    await act(async () => {
      result.current.mutate({
        tenantId: "default",
        keyName: "brand-new-key",
        value: "v",
        createOnly: true,
      });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
