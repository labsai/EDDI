import { beforeEach, describe, expect, it } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { type ReactNode } from "react";
import { server } from "@/test/mocks/server";
import { useChatStore, useSendMessage } from "@/hooks/use-chat";

/**
 * A 409 means the conversation is paused awaiting approval and the send was
 * rejected *without* being consumed, so the optimistic user message must be
 * rolled back.
 *
 * The pre-existing test for this passed even while the rollback was dead code:
 * it tracked the pending message id in a plain `let` inside the hook body, and
 * `renderHook` with no store subscription never re-renders, so that `let`
 * happened to survive. In the real app the optimistic message triggers a
 * re-render, TanStack Query then calls the newest render's `onError`, and it saw
 * a freshly re-initialised `null`.
 *
 * This harness subscribes to the store, so it re-renders exactly like the real
 * chat panel — which is what makes the assertion meaningful.
 */

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

/** Re-renders on every store change, like the real chat panel does. */
function ChatHarness({ message }: { message: string }) {
  const messages = useChatStore((s) => s.messages);
  const isPaused = useChatStore((s) => s.isPaused);
  const send = useSendMessage();

  return (
    <div>
      <span data-testid="message-count">{messages.length}</span>
      <span data-testid="paused">{String(isPaused)}</span>
      <span data-testid="renders">{messages.map((m) => m.content).join("|")}</span>
      <button onClick={() => send.mutate({ message })}>send</button>
    </div>
  );
}

describe("useSendMessage 409 rollback (with re-renders)", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
    useChatStore.getState().setSelectedAgent("agent1", "Support Agent");
    useChatStore.getState().setConversationId("conv1");
  });

  it("removes the optimistic user message when the send is rejected with 409", async () => {
    server.use(
      http.post("*/agents/*", () => new HttpResponse(null, { status: 409 })),
    );

    const Wrapper = createWrapper();
    render(
      <Wrapper>
        <ChatHarness message="already paused, try again" />
      </Wrapper>,
    );

    fireEvent.click(screen.getByText("send"));

    // The optimistic message appears first — that add is what forces the
    // re-render that used to break the rollback.
    await waitFor(() =>
      expect(screen.getByTestId("renders").textContent).toContain(
        "already paused, try again",
      ),
    );

    // ...and must be gone once the 409 lands.
    await waitFor(() => expect(screen.getByTestId("paused").textContent).toBe("true"));
    await waitFor(() =>
      expect(screen.getByTestId("renders").textContent).not.toContain(
        "already paused, try again",
      ),
    );
    expect(screen.getByTestId("message-count").textContent).toBe("0");
    expect(useChatStore.getState().isProcessing).toBe(false);
  });
});
