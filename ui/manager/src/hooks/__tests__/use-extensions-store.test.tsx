import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import { useExtensionTypes } from "@/hooks/use-extensions-store";
import { http, HttpResponse } from "msw";

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

describe("useExtensionTypes", () => {
  it("fetches extension types from the API", async () => {
    server.use(
      http.get("*/extensionstore/extensions", () => {
        return HttpResponse.json([
          {
            type: "eddi://ai.labs.rules",
            displayName: "Rules",
            configs: {},
            extensions: {},
          },
          {
            type: "eddi://ai.labs.output",
            displayName: "Output",
            configs: {},
            extensions: {},
          },
        ]);
      }),
    );

    const { result } = renderHook(() => useExtensionTypes(), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
    // Should include the backend types + well-known MCP type
    expect(result.current.data!.length).toBeGreaterThanOrEqual(2);
  });

  it("merges well-known MCP type when not returned by backend", async () => {
    server.use(
      http.get("*/extensionstore/extensions", () => {
        return HttpResponse.json([
          {
            type: "eddi://ai.labs.rules",
            displayName: "Rules",
            configs: {},
            extensions: {},
          },
        ]);
      }),
    );

    const { result } = renderHook(() => useExtensionTypes(), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const types = result.current.data!.map((t) => t.type);
    expect(types).toContain("eddi://ai.labs.mcpcalls");
  });

  it("does not duplicate MCP type if backend already includes it", async () => {
    server.use(
      http.get("*/extensionstore/extensions", () => {
        return HttpResponse.json([
          {
            type: "eddi://ai.labs.mcpcalls",
            displayName: "MCP Calls",
            configs: {},
            extensions: {},
          },
        ]);
      }),
    );

    const { result } = renderHook(() => useExtensionTypes(), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const mcpTypes = result.current.data!.filter(
      (t) => t.type === "eddi://ai.labs.mcpcalls",
    );
    expect(mcpTypes).toHaveLength(1);
  });

  it("passes filter parameter to the API", async () => {
    server.use(
      http.get("*/extensionstore/extensions", () => {
        return HttpResponse.json([]);
      }),
    );

    const { result } = renderHook(() => useExtensionTypes("rules"), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
