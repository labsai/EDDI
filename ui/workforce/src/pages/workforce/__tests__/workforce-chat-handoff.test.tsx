import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { ThemeProvider } from "@eddi/ui";
import { createTestQueryClient } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import { useChatStore } from "@eddi/app-kit/hooks/use-chat";
import { useDebugStore } from "@eddi/app-kit/hooks/use-debug-events";
import { MOCK_CATALOG_AGENTS, MOCK_CATALOG_GROUPS } from "@eddi/app-kit/test/mocks/catalog-fixtures";
import { addHandoff, clearWorkforceHandoffs, readHandoffs } from "@eddi/app-kit/lib/workforce-handoffs";
import { readInitialChatMessage } from "@eddi/app-kit/lib/workforce-chat-start";
import { WorkforceChat } from "@/pages/workforce/workforce-chat";
import { readInitialTeamQuestion } from "@/lib/workforce-team-start";

/**
 * T2.2 on the chat page: the composer offers only the catalog's agents and
 * teams, a hand-off is recorded (in this browser) and opens the target with the
 * question in router state, and the chat shows the marker.
 */

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

function TeamProbe() {
  const location = useLocation();
  const q = readInitialTeamQuestion(location.state);
  return <div data-testid="team-probe">{q ? `${location.pathname}${location.search}|${q.groupId}|${q.text}|${q.handoffId ?? ""}` : "none"}</div>;
}

function renderChat() {
  return render(
    <MemoryRouter initialEntries={["/workforce/chat?agentId=agent1&conversationId=conv1"]}>
      <QueryClientProvider client={createTestQueryClient()}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <Routes>
            <Route path="/workforce/chat" element={<WorkforceChat />} />
            <Route path="/workforce/:boardId" element={<TeamProbe />} />
          </Routes>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("WorkforceChat — @mention hand-off", () => {
  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    useChatStore.getState().reset();
    useDebugStore.getState().reset();
    localStorage.setItem("workforce-chat-details-panel", "false");
    useChatStore.getState().setSelectedAgent("agent1", "Support Bot");
    useChatStore.getState().setConversationId("conv1");
    server.use(
      http.get("*/agents/:agentId/profile", ({ params }) =>
        HttpResponse.json({ agentId: String(params.agentId), reviewNotice: null }),
      ),
      http.get("*/agents/conv1", () =>
        HttpResponse.json({
          conversationState: "READY",
          agentId: "agent1",
          undoAvailable: false,
          redoAvailable: false,
          conversationSteps: [{ conversationStep: [{ key: "input:initial", value: "Hello" }] }],
          conversationOutputs: [{ input: "Hello", output: [{ type: "text", text: "Hi there" }] }],
        }),
      ),
    );
  });

  afterEach(() => clearWorkforceHandoffs());

  it("offers the catalog's agents and teams only, never an unlisted deployed agent", async () => {
    server.use(
      http.get("*/agentstore/agents/descriptors", () =>
        HttpResponse.json([{ resource: "eddi://ai.labs.agent/agentstore/agents/unlisted-x?version=1", name: "Internal helper" }]),
      ),
    );
    renderChat();
    const input = (await screen.findByTestId("chat-input")) as HTMLTextAreaElement;
    await waitFor(() => expect(input).toHaveAttribute("role", "combobox"));
    fireEvent.change(input, { target: { value: "@", selectionStart: 1, selectionEnd: 1 } });
    const names = screen.getAllByRole("option").map((o) => o.textContent ?? "");
    const expected = [
      ...MOCK_CATALOG_AGENTS.map((a) => ("appearance" in a && a.appearance?.displayName) || a.name),
      ...MOCK_CATALOG_GROUPS.map((g) => g.name),
    ];
    expect(names).toHaveLength(Math.min(expected.length, 8));
    for (const name of names) expect(expected.some((e) => name.startsWith(String(e)))).toBe(true);
    expect(names.some((n) => n.includes("Internal helper"))).toBe(false);
  });

  it("hands a question to a team: recorded here, opened with the question in router state", async () => {
    renderChat();
    const input = (await screen.findByTestId("chat-input")) as HTMLTextAreaElement;
    await waitFor(() => expect(input).toHaveAttribute("role", "combobox"));
    await waitFor(() => expect(input).not.toBeDisabled());
    const team = MOCK_CATALOG_GROUPS[0];
    fireEvent.change(input, { target: { value: "@Product", selectionStart: 8, selectionEnd: 8 } });
    fireEvent.keyDown(input, { key: "Enter" });
    fireEvent.change(input, { target: { value: "Should we ship?", selectionStart: 15, selectionEnd: 15 } });
    fireEvent.keyDown(input, { key: "Enter" });

    const probe = await screen.findByTestId("team-probe");
    const [record] = readHandoffs(null);
    expect(record).toMatchObject({
      from: { agentId: "agent1", agentName: "Support Bot", conversationId: "conv1" },
      to: { kind: "team", id: team.groupId, name: team.name, version: team.version, conversationId: null },
      question: "Should we ship?",
    });
    expect(probe).toHaveTextContent(`/workforce/${team.groupId}?version=${team.version}|${team.groupId}|Should we ship?|${record!.id}`);
  });

  it("draws the marker of a hand-off made from this chat", async () => {
    addHandoff(null, {
      from: { agentId: "agent1", agentName: "Support Bot", conversationId: "conv1", afterStep: 1 },
      to: { kind: "agent", id: "aaaaaaaaaaaaaaaaaaaaaa02", name: "Travel Planner", conversationId: "conv-new" },
      question: "Plan a weekend",
    });
    useChatStore.getState().replaceMessages([
      { id: "u1", role: "user", content: "Hello", timestamp: 1 },
      { id: "a1", role: "agent", content: "Hi there", timestamp: 2 },
    ]);
    renderChat();
    const marker = await screen.findByTestId("chat-transcript-marker");
    expect(marker).toHaveTextContent("You handed “Plan a weekend” over to Travel Planner.");
    expect(marker.querySelector("a")).toHaveAttribute(
      "href",
      "/workforce/chat?agentId=aaaaaaaaaaaaaaaaaaaaaa02&conversationId=conv-new",
    );
    // The chat start state used for an agent hand-off carries its id too.
    expect(readInitialChatMessage({ initialMessage: { agentId: "a", text: "t", handoffId: "h" } })).toEqual({
      agentId: "a",
      text: "t",
      handoffId: "h",
    });
  });
});
