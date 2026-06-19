import { describe, expect, it } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { type ReactNode } from "react";
import { usePlatformStatus } from "@/hooks/use-platform-status";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
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

describe("usePlatformStatus", () => {
  it("returns checking initially", () => {
    const { result } = renderHook(() => usePlatformStatus(), {
      wrapper: createWrapper(),
    });
    expect(result.current.status).toBe("checking");
    expect(result.current.instanceId).toBeNull();
    expect(result.current.latencyMs).toBeNull();
  });

  it("returns online when API is healthy", async () => {
    server.use(
      http.get("*/administration/logs/instance-id", () => {
        return HttpResponse.json({ instanceId: "eddi-node-1" });
      })
    );
    const { result } = renderHook(() => usePlatformStatus(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.status).toBe("online"));
    expect(result.current.instanceId).toBe("eddi-node-1");
    expect(result.current.latencyMs).toBeDefined();
    expect(typeof result.current.latencyMs).toBe("number");
    expect(result.current.lastCheckedAt).toBeInstanceOf(Date);
  });

  it("returns offline when API fails", async () => {
    server.use(
      http.get("*/administration/logs/instance-id", () => {
        return HttpResponse.json({}, { status: 500 });
      })
    );
    const { result } = renderHook(() => usePlatformStatus(), {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(result.current.status).toBe("offline"));
    expect(result.current.instanceId).toBeNull();
    expect(result.current.latencyMs).toBeNull();
  });
});
