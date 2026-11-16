import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { ThemeProvider } from "@eddi/ui";
import { http, HttpResponse } from "msw";
import { createTestQueryClient } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import { ChatPanel } from "@eddi/app-kit/components/chat/chat-panel";
import { useChatStore } from "@eddi/app-kit/hooks/use-chat";
import type { InitialChatMessage } from "@eddi/app-kit/lib/workforce-chat-start";

/**
 * T1.15 — in Workforce (`urlSync`) the open conversation lives in the address
 * bar, so a reload or a second tab returns to it. The Manager's chat (no
 * `urlSync`) keeps clearing the parameters once read.
 */

const DESCRIPTORS = "descriptors";

function snapshot(conversationId: string, text: string) {
  return {
    agentId: "agent1",
    agentVersion: 1,
    conversationId,
    conversationState: "READY",
    environment: "production",
    conversationSteps: [{ conversationStep: [{ key: "input:initial", value: `question in ${conversationId}` }] }],
    conversationOutputs: [{ input: `question in ${conversationId}`, output: [{ type: "text", text }] }],
  };
}

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="search">{location.search}</div>;
}

function renderChat(
  initialEntry: string | { pathname: string; search: string; state: unknown },
  props: { urlSync?: boolean; initialMessage?: InitialChatMessage | null } = { urlSync: true },
) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <QueryClientProvider client={createTestQueryClient()}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <Routes>
            <Route
              path="/workforce/chat"
              element={
                <>
                  <ChatPanel urlSync={props.urlSync} initialMessage={props.initialMessage ?? null} />
                  <LocationProbe />
                </>
              }
            />
          </Routes>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

function search(): URLSearchParams {
  return new URLSearchParams(screen.getByTestId("search").textContent ?? "");
}

describe("ChatPanel — the conversation in the URL (T1.15)", () => {
  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    useChatStore.getState().reset();
    useChatStore.setState({ streamingEnabled: false });
    server.use(
      http.get("*/agents/:conversationId", ({ params }) => {
        const id = String(params.conversationId);
        if (id === DESCRIPTORS) return;
        return HttpResponse.json(snapshot(id, `answer from ${id}`));
      }),
    );
  });

  it("keeps both ids in the address bar after opening the named conversation", async () => {
    renderChat("/workforce/chat?agentId=agent1&conversationId=conv-a&agentName=Evil");
    expect(await screen.findByText("answer from conv-a")).toBeInTheDocument();
    await waitFor(() => expect(search().get("conversationId")).toBe("conv-a"));
    expect(search().get("agentId")).toBe("agent1");
    // The ignored display name is never kept, let alone shown.
    await waitFor(() => expect(search().has("agentName")).toBe(false));
  });

  it("returns to the same conversation on a reload (a fresh store, the same URL)", async () => {
    const first = renderChat("/workforce/chat?agentId=agent1&conversationId=conv-a");
    expect(await screen.findByText("answer from conv-a")).toBeInTheDocument();
    const url = `/workforce/chat${screen.getByTestId("search").textContent}`;
    first.unmount();

    useChatStore.getState().reset();
    renderChat(url);
    expect(await screen.findByText("answer from conv-a")).toBeInTheDocument();
    expect(useChatStore.getState().conversationId).toBe("conv-a");
  });

  it("writes a conversation started here into the address bar", async () => {
    server.use(
      http.post("*/agents/:agentId/start", () =>
        new HttpResponse(null, { status: 201, headers: { Location: "/agents/conv-new" } }),
      ),
    );
    renderChat("/workforce/chat?agentId=agent1");
    fireEvent.click(await screen.findByTestId("open-chat"));
    await waitFor(() => expect(useChatStore.getState().conversationId).toBeTruthy());
    const started = useChatStore.getState().conversationId!;
    await waitFor(() => expect(search().get("conversationId")).toBe(started));
    expect(search().get("agentId")).toBe("agent1");
  });

  it("does not start anything from the URL alone", async () => {
    let started = false;
    server.use(
      http.post("*/agents/:agentId/start", () => {
        started = true;
        return new HttpResponse(null, { status: 201, headers: { Location: "/agents/conv-new" } });
      }),
    );
    renderChat("/workforce/chat?agentId=agent1");
    await waitFor(() => expect(useChatStore.getState().selectedAgentId).toBe("agent1"));
    await screen.findByTestId("open-chat");
    expect(started).toBe(false);
    expect(useChatStore.getState().conversationId).toBeNull();
  });

  it("sends a first message handed over in router state in a new conversation", async () => {
    const sent: string[] = [];
    server.use(
      http.post("*/agents/:agentId/start", () =>
        new HttpResponse(null, { status: 201, headers: { Location: "/agents/conv-new" } }),
      ),
      http.post("*/agents/:conversationId", async ({ request, params }) => {
        sent.push(`${String(params.conversationId)}:${await request.text()}`);
        return HttpResponse.json(snapshot(String(params.conversationId), "on its way"));
      }),
    );
    renderChat("/workforce/chat?agentId=agent1", {
      urlSync: true,
      initialMessage: { agentId: "agent1", text: "Where is my order?" },
    });
    await waitFor(() => expect(sent.some((s) => s.includes("Where is my order?"))).toBe(true));
    expect(sent).toHaveLength(1);
  });

  it("the Manager's chat (no urlSync) still clears the parameters once read", async () => {
    renderChat("/workforce/chat?agentId=agent1&conversationId=conv-a", { urlSync: false });
    expect(await screen.findByText("answer from conv-a")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId("search").textContent).toBe(""));
  });
});
