import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { act, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import { ChatPanel } from "@eddi/app-kit/components/chat/chat-panel";
import { chatTurnNotes, useChatStore } from "@eddi/app-kit/hooks/use-chat";
import { useDebugStore } from "@eddi/app-kit/hooks/use-debug-events";

/**
 * T2.7 — when a turn's answer came through a model cascade or a re-ask, a
 * plain-language note says so under the turn's reply. Only when the stream
 * said so: a turn without such events gets no note.
 */
describe("ChatPanel — turn notes", () => {
  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    useChatStore.getState().reset();
    useDebugStore.getState().reset();
    chatTurnNotes.clear();
    useChatStore.getState().setSelectedAgent("agent1", "Support Bot");
    useChatStore.getState().setConversationId("conv1");
    useChatStore.getState().replaceMessages([
      { id: "a0", role: "agent", content: "Welcome", timestamp: 1 },
      { id: "u1", role: "user", content: "Explain quantum tunnelling", timestamp: 2 },
      { id: "a1", role: "agent", content: "Particles can cross barriers.", timestamp: 3 },
      { id: "a1b", role: "agent", content: "Want an example?", timestamp: 4 },
      { id: "u2", role: "user", content: "Thanks", timestamp: 5 },
      { id: "a2", role: "agent", content: "You're welcome.", timestamp: 6 },
    ]);
    server.use(
      http.get("*/agents/:agentId/profile", ({ params }) =>
        HttpResponse.json({ agentId: String(params.agentId), reviewNotice: null }),
      ),
    );
  });

  it("puts the note under the turn's last reply, in plain words", async () => {
    chatTurnNotes.record("conv1", 1, { kind: "escalation", reason: "low_confidence" });
    renderWithProviders(<ChatPanel />);
    const notes = await screen.findAllByTestId("turn-notes");
    expect(notes).toHaveLength(1);
    expect(notes[0]).toHaveTextContent(
      "Answered by a larger model because the first answer wasn't confident enough.",
    );
    // After the turn's last bubble, before the next question.
    const lastReply = screen.getByText("Want an example?");
    const nextQuestion = screen.getByText("Thanks");
    expect(lastReply.compareDocumentPosition(notes[0]!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(notes[0]!.compareDocumentPosition(nextQuestion) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("shows no note for a turn the stream said nothing about", async () => {
    renderWithProviders(<ChatPanel />);
    await screen.findByText("You're welcome.");
    expect(screen.queryByTestId("turn-notes")).toBeNull();

    // Another conversation's note is not this one's.
    act(() => chatTurnNotes.record("other", 2, { kind: "retry", reason: "empty" }));
    expect(screen.queryByTestId("turn-notes")).toBeNull();

    act(() => chatTurnNotes.record("conv1", 2, { kind: "retry", reason: "empty" }));
    expect(await screen.findByTestId("turn-note")).toHaveTextContent(
      "The model was asked again because its first reply was empty.",
    );
  });
});
