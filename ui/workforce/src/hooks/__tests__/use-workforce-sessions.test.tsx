import { describe, expect, it } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { type ReactNode } from "react";
import { server } from "@eddi/app-kit/test/mocks/server";
import { useMyChatDescriptors } from "@/hooks/use-workforce-sessions";

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <MemoryRouter>
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      </MemoryRouter>
    );
  };
}

const descriptor = (id: string, userId: string) => ({
  resource: `eddi://ai.labs.conversation/conversationstore/conversations/${id}?version=1`,
  agentResource: "eddi://ai.labs.agent/agentstore/agents/agent1?version=1",
  userId,
  createdOn: Date.now(),
  lastModifiedOn: Date.now(),
  conversationState: "READY",
});

describe("useMyChatDescriptors", () => {
  it("asks the backend for the caller's own chats without team members' threads", async () => {
    const urls: URL[] = [];
    server.use(
      http.get("*/conversationstore/conversations", ({ request }) => {
        urls.push(new URL(request.url));
        return HttpResponse.json([descriptor("c1", "u-sub-1")]);
      }),
    );

    const { result } = renderHook(() => useMyChatDescriptors(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(urls).toHaveLength(1);
    const [url] = urls;
    expect(url?.searchParams.get("mine")).toBe("true");
    expect(url?.searchParams.get("excludeGroupMembers")).toBe("true");
    expect(url?.searchParams.get("limit")).toBe("100");
  });

  it("keeps every row the backend returns — no client-side comparison with the username", async () => {
    // The server records the principal name, which need not be the token's
    // preferred_username (docs/security.md recommends `sub`): a client-side
    // comparison used to empty an admin's sidebar.
    server.use(
      http.get("*/conversationstore/conversations", () =>
        HttpResponse.json([descriptor("c1", "0f3c-sub"), descriptor("c2", "")]),
      ),
    );

    const { result } = renderHook(() => useMyChatDescriptors(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(2);
  });
});
