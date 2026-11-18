import { describe, it, expect, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { server } from "@eddi/app-kit/test/mocks/server";
import { useDeleteAgent } from "@eddi/app-kit/hooks/use-agents";

describe("useDeleteAgent", () => {
  it("refreshes every cache that lists agents, not only the Agents page", async () => {
    server.use(http.delete("*/agentstore/agents/a1", () => new HttpResponse(null, { status: 200 })));
    const client = new QueryClient();
    const invalidate = vi.spyOn(client, "invalidateQueries");
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    const { result } = renderHook(() => useDeleteAgent(), { wrapper });
    result.current.mutate({ id: "a1", version: 1 });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const roots = invalidate.mock.calls.map(([filter]) => (filter?.queryKey as unknown[])[0]);
    expect(roots).toEqual(expect.arrayContaining(["agents", "chat", "groups", "channels"]));
  });

  it("sends only the version, never a parameter the backend does not know", async () => {
    const urls: string[] = [];
    server.use(
      http.delete("*/agentstore/agents/a1", ({ request }) => {
        urls.push(request.url);
        return new HttpResponse(null, { status: 200 });
      }),
    );
    const client = new QueryClient();
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    const { result } = renderHook(() => useDeleteAgent(), { wrapper });
    result.current.mutate({ id: "a1", version: 3 });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(new URL(urls[0]!).search).toBe("?version=3");
  });
});
