import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import { ChatPanel } from "@eddi/app-kit/components/chat/chat-panel";
import { chatConversation, useChatStore } from "@eddi/app-kit/hooks/use-chat";
import { useDebugStore } from "@eddi/app-kit/hooks/use-debug-events";
import { STOP_VERIFY_DELAYS_MS } from "@eddi/conversation";

/**
 * T1.5 / T1.10 / T1.12 — Stop, Esc, the reconnect line and the reply
 * announcer of the shared chat (Manager chat, Studio, Workforce chat).
 */
describe("ChatPanel — Stop, Esc, reconnect, announcements", () => {
  let cancels: string[];

  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    cancels = [];
    useChatStore.getState().reset();
    useDebugStore.getState().reset();
    useChatStore.getState().setSelectedAgent("agent1", "Support Bot");
    useChatStore.getState().setConversationId("conv1");
    useChatStore.getState().addMessage({ id: "u1", role: "user", content: "Hi", timestamp: 1 });
    // Check at once instead of after a second and a half.
    chatConversation.setOptions({ stopVerifyDelaysMs: [0] });
    server.use(
      http.post("*/agents/:convId/cancel", ({ params }) => {
        cancels.push(String(params.convId));
        return new HttpResponse(null, { status: 200 });
      }),
      http.get("*/agents/conv1", () =>
        HttpResponse.json({ conversationState: "EXECUTION_INTERRUPTED", conversationOutputs: [] }),
      ),
    );
  });

  afterEach(() => {
    chatConversation.setOptions({ stopVerifyDelaysMs: STOP_VERIFY_DELAYS_MS });
  });

  /** A turn the agent is answering: the reply is streaming in. */
  function turnRunning() {
    act(() => {
      useChatStore.getState().addMessage({ id: "a1", role: "agent", content: "Half a re", timestamp: 2, isStreaming: true });
      useChatStore.getState().setProcessing(true);
    });
  }

  it("offers Stop in place of Send while a turn runs, and Stop cancels it on the server", async () => {
    renderWithProviders(<ChatPanel />);
    expect(screen.getByTestId("chat-send")).toBeInTheDocument();
    expect(screen.queryByTestId("chat-stop")).not.toBeInTheDocument();

    turnRunning();
    expect(screen.queryByTestId("chat-send")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("chat-stop"));

    await waitFor(() => expect(cancels).toEqual(["conv1"]));
    expect(useChatStore.getState().isProcessing).toBe(false);
    await waitFor(() => expect(screen.getByTestId("chat-send")).toBeInTheDocument());
  });

  it("does not offer Stop while undo or redo holds the conversation", () => {
    renderWithProviders(<ChatPanel />);
    act(() => useChatStore.setState({ isProcessing: true, historyBusy: true }));
    expect(screen.queryByTestId("chat-stop")).not.toBeInTheDocument();
  });

  it("Esc stops a running turn from the composer", async () => {
    renderWithProviders(<ChatPanel />);
    turnRunning();
    const input = screen.getByTestId("chat-input");
    input.focus();
    fireEvent.keyDown(input, { key: "Escape" });
    await waitFor(() => expect(cancels).toEqual(["conv1"]));
  });

  it("Esc does nothing when no turn runs, or when an open dialog owns it", async () => {
    renderWithProviders(<ChatPanel />);
    fireEvent.keyDown(screen.getByTestId("chat-input"), { key: "Escape" });

    turnRunning();
    const dialog = document.createElement("div");
    dialog.setAttribute("role", "dialog");
    dialog.setAttribute("aria-modal", "true");
    document.body.appendChild(dialog);
    try {
      fireEvent.keyDown(screen.getByTestId("chat-input"), { key: "Escape" });
      await new Promise((r) => setTimeout(r, 30));
      expect(cancels).toEqual([]);
    } finally {
      dialog.remove();
    }
  });

  it("says so when the turn finished anyway on the server, and shows its whole reply", async () => {
    server.use(
      http.get("*/agents/conv1", () =>
        HttpResponse.json({
          conversationState: "READY",
          conversationOutputs: [{ output: [{ type: "text", text: "The whole reply, from another pod" }] }],
        }),
      ),
    );
    renderWithProviders(<ChatPanel />);
    turnRunning();
    fireEvent.click(screen.getByTestId("chat-stop"));

    expect(await screen.findByTestId("chat-notice")).toHaveTextContent(/Stop came too late/);
    expect(await screen.findByText("The whole reply, from another pod")).toBeInTheDocument();
    expect(screen.queryByText("Half a re")).not.toBeInTheDocument();
  });

  it("shows that it is reconnecting while a dropped turn is followed", () => {
    renderWithProviders(<ChatPanel />);
    turnRunning();
    expect(screen.queryByTestId("chat-reconnecting")).not.toBeInTheDocument();
    act(() => useChatStore.setState({ reconnecting: true }));
    expect(screen.getByTestId("chat-reconnecting")).toHaveTextContent(/Reconnecting/);
  });

  it("announces a reply once it is complete, with who said it — not the transcript already there", () => {
    act(() => {
      useChatStore.getState().addMessage({ id: "a0", role: "agent", content: "Earlier reply", timestamp: 1 });
    });
    renderWithProviders(<ChatPanel />);
    const announcer = screen.getByTestId("reply-announcer");
    expect(announcer).toHaveTextContent("");
    expect(screen.getByRole("log")).toHaveAttribute("aria-live", "off");

    turnRunning();
    expect(announcer).toHaveTextContent("");
    act(() => {
      useChatStore.getState().appendToLastAgentMessage("ply with **bold**");
      useChatStore.getState().finishStreaming();
    });
    expect(announcer).toHaveTextContent("Support Bot said: Half a reply with bold");
  });
});
