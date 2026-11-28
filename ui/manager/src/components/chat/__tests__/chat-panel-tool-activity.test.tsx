import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import { ChatPanel } from "@eddi/app-kit/components/chat/chat-panel";
import { chatToolActivity, useChatStore } from "@eddi/app-kit/hooks/use-chat";
import { useDebugStore } from "@eddi/app-kit/hooks/use-debug-events";

/**
 * T1.9 — a turn's tool calls as collapsible cards under its question; T1.6 —
 * before Edit or Regenerate of a turn that ran tools, say which, since neither
 * takes back what they did.
 */
describe("ChatPanel — tool cards and the tool notice", () => {
  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    useChatStore.getState().reset();
    useDebugStore.getState().reset();
    chatToolActivity.clear();
    useChatStore.getState().setSelectedAgent("agent1", "Support Bot");
    useChatStore.getState().setConversationId("conv1");
    useChatStore.getState().replaceMessages([
      { id: "a0", role: "agent", content: "Welcome", timestamp: 1 },
      { id: "u1", role: "user", content: "Book me a table", timestamp: 2 },
      { id: "a1", role: "agent", content: "Booked for eight.", timestamp: 3 },
      { id: "u2", role: "user", content: "Thanks", timestamp: 4 },
      { id: "a2", role: "agent", content: "You're welcome.", timestamp: 5 },
    ]);
    useChatStore.getState().setUndoRedo(true, false);
    server.use(
      http.get("*/agents/:agentId/profile", ({ params }) =>
        HttpResponse.json({
          agentId: String(params.agentId),
          reviewNotice: null,
          conversationControls: {
            undo: true, redo: true, attachments: true, rating: true, quickReplies: true, streaming: true, rawHtml: false,
          },
        }),
      ),
      http.post("*/agents/:convId/undo", () => new HttpResponse(null, { status: 200 })),
      http.get("*/agents/conv1", () =>
        HttpResponse.json({
          conversationState: "READY",
          undoAvailable: true,
          redoAvailable: true,
          conversationSteps: [],
          conversationOutputs: [],
        }),
      ),
    );
  });

  it("groups each turn's calls under its own question, collapsed", async () => {
    chatToolActivity.record("conv1", 1, { tool: "find_table", ok: true, summary: "Table 4 free" });
    chatToolActivity.record("conv1", 1, { tool: "book_table", ok: false, summary: "<b>timeout</b>" });
    renderWithProviders(<ChatPanel />);

    const groups = await screen.findAllByTestId("tool-activity");
    expect(groups).toHaveLength(1);
    // Between the question of step 1 and its reply.
    const question = screen.getByText("Book me a table");
    const reply = screen.getByText("Booked for eight.");
    expect(question.compareDocumentPosition(groups[0]!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(groups[0]!.compareDocumentPosition(reply) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    const toggle = within(groups[0]!).getByTestId("tool-activity-toggle");
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(toggle).toHaveTextContent("Tools used: 2");
    fireEvent.click(toggle);
    const cards = within(groups[0]!).getAllByTestId("tool-card");
    expect(cards.map((c) => within(c).getByTestId("tool-card-name").textContent)).toEqual(["find_table", "book_table"]);
    expect(within(cards[1]!).getByTestId("tool-card-summary").textContent).toBe("<b>timeout</b>");
    expect(groups[0]!.querySelector("b")).toBeNull();
  });

  it("shows cards for the turns of the conversation on screen only", async () => {
    chatToolActivity.record("other-conversation", 1, { tool: "elsewhere", ok: true, summary: "" });
    renderWithProviders(<ChatPanel />);
    await screen.findByTestId("edit-last");
    expect(screen.queryByTestId("tool-activity")).toBeNull();

    // A call arriving for the conversation on screen appears at once.
    act(() => chatToolActivity.record("conv1", 2, { tool: "say_thanks", ok: true, summary: "" }));
    expect(await screen.findByTestId("tool-activity")).toBeInTheDocument();
  });

  it("names the tools beside Edit and Regenerate when the last turn ran tools", async () => {
    chatToolActivity.record("conv1", 2, { tool: "send_email", ok: true, summary: "sent" });
    chatToolActivity.record("conv1", 2, { tool: "send_email", ok: true, summary: "sent again" });
    renderWithProviders(<ChatPanel />);

    const notices = await screen.findAllByTestId("tool-turn-notice");
    expect(notices).toHaveLength(2);
    expect(notices[0]).toHaveTextContent("Ran send_email. Editing takes back the reply, not what the tools did.");
    expect(notices[1]).toHaveTextContent("Ran send_email. What they did stays, and a new reply may run them again.");
    // Read with the button it qualifies.
    expect(screen.getByTestId("edit-last")).toHaveAttribute("aria-describedby", notices[0]!.id);
    expect(screen.getByTestId("regenerate-last")).toHaveAttribute("aria-describedby", notices[1]!.id);
  });

  it("says nothing beside Edit and Regenerate when the last turn ran no tools", async () => {
    // An earlier turn's tools do not make the last one a tool turn.
    chatToolActivity.record("conv1", 1, { tool: "book_table", ok: true, summary: "" });
    renderWithProviders(<ChatPanel />);
    await screen.findByTestId("edit-last");
    expect(screen.getByTestId("regenerate-last")).toBeInTheDocument();
    expect(screen.queryByTestId("tool-turn-notice")).toBeNull();
    expect(screen.queryByTestId("turn-notice")).toBeNull();
    expect(screen.getByTestId("edit-last")).not.toHaveAttribute("aria-describedby");
  });

  it("a host's own notice replaces the default", async () => {
    chatToolActivity.record("conv1", 2, { tool: "send_email", ok: true, summary: "" });
    renderWithProviders(<ChatPanel renderTurnNotice={() => "Custom"} />);
    expect((await screen.findAllByTestId("turn-notice"))[0]).toHaveTextContent("Custom");
    expect(screen.queryByTestId("tool-turn-notice")).toBeNull();
  });

  it("keeps the step's calls through the edit, for a redo to find", async () => {
    chatToolActivity.record("conv1", 2, { tool: "send_email", ok: true, summary: "" });
    renderWithProviders(<ChatPanel />);
    fireEvent.click(await screen.findByTestId("edit-last"));
    const input = screen.getByTestId("chat-input") as HTMLTextAreaElement;
    await waitFor(() => expect(input.value).toBe("Thanks"));
    expect(chatToolActivity.forStep("conv1", 2)).toBeDefined();
  });
});
