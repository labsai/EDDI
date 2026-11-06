import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import { ChatPanel } from "@eddi/app-kit/components/chat/chat-panel";
import { useChatStore } from "@eddi/app-kit/hooks/use-chat";
import { useDebugStore } from "@eddi/app-kit/hooks/use-debug-events";

/**
 * T1.6 (part) and T1.10 — edit the last message (undo + say), regenerate the
 * last reply (rerun), ↑ to edit; only where the agent allows undo and redo.
 */
describe("ChatPanel — edit the last message, regenerate the last reply", () => {
  let undos: number;
  let reruns: number;

  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  function controls(over: Record<string, boolean>) {
    server.use(
      http.get("*/agents/:agentId/profile", ({ params }) =>
        HttpResponse.json({
          agentId: String(params.agentId),
          reviewNotice: null,
          conversationControls: {
            undo: true, redo: true, attachments: true, rating: true, quickReplies: true, streaming: true, rawHtml: false,
            ...over,
          },
        }),
      ),
    );
  }

  beforeEach(() => {
    undos = 0;
    reruns = 0;
    useChatStore.getState().reset();
    useDebugStore.getState().reset();
    useChatStore.getState().setSelectedAgent("agent1", "Support Bot");
    useChatStore.getState().setConversationId("conv1");
    useChatStore.getState().replaceMessages([
      { id: "a0", role: "agent", content: "Welcome", timestamp: 1 },
      { id: "u1", role: "user", content: "What is the capital of France?", timestamp: 2 },
      { id: "a1", role: "agent", content: "Lyon.", timestamp: 3 },
    ]);
    useChatStore.getState().setUndoRedo(true, false);
    controls({});
    server.use(
      http.post("*/agents/:convId/undo", () => {
        undos += 1;
        return new HttpResponse(null, { status: 200 });
      }),
      http.post("*/agents/:convId/rerun", () => {
        reruns += 1;
        return new HttpResponse(null, { status: 200 });
      }),
      // After the undo, the conversation holds only the welcome.
      http.get("*/agents/conv1", () =>
        HttpResponse.json({
          conversationState: "READY",
          undoAvailable: false,
          redoAvailable: true,
          conversationSteps: [{ conversationStep: [{ key: "output:text:0", value: "Welcome" }] }],
          conversationOutputs: [{ output: [{ type: "text", text: "Welcome" }] }],
        }),
      ),
    );
  });

  it("Edit takes the last step back and puts its text into the composer, focused", async () => {
    renderWithProviders(<ChatPanel />);
    fireEvent.click(await screen.findByTestId("edit-last"));

    await waitFor(() => expect(undos).toBe(1));
    const input = screen.getByTestId("chat-input") as HTMLTextAreaElement;
    await waitFor(() => expect(input.value).toBe("What is the capital of France?"));
    await waitFor(() => expect(input).toHaveFocus());
    expect(screen.queryByText("Lyon.")).not.toBeInTheDocument();
  });

  it("↑ in an empty composer edits the last message; with text in it, ↑ is the caret's", async () => {
    renderWithProviders(<ChatPanel />);
    await screen.findByTestId("edit-last");
    const input = screen.getByTestId("chat-input") as HTMLTextAreaElement;

    fireEvent.change(input, { target: { value: "a draft" } });
    fireEvent.keyDown(input, { key: "ArrowUp" });
    expect(undos).toBe(0);

    fireEvent.change(input, { target: { value: "" } });
    fireEvent.keyDown(input, { key: "ArrowUp" });
    await waitFor(() => expect(undos).toBe(1));
    await waitFor(() => expect(input.value).toBe("What is the capital of France?"));
  });

  it("offers neither Edit nor ↑ when the agent switched undo off", async () => {
    controls({ undo: false });
    renderWithProviders(<ChatPanel />);
    await waitFor(() => expect(screen.queryByTestId("undo-btn")).not.toBeInTheDocument());
    expect(screen.queryByTestId("edit-last")).not.toBeInTheDocument();
    expect(screen.queryByTestId("regenerate-last")).not.toBeInTheDocument();
    fireEvent.keyDown(screen.getByTestId("chat-input"), { key: "ArrowUp" });
    await new Promise((r) => setTimeout(r, 20));
    expect(undos).toBe(0);
  });

  it("Regenerate reruns the last step, and needs redo allowed as well", async () => {
    const { unmount } = renderWithProviders(<ChatPanel />);
    fireEvent.click(await screen.findByTestId("regenerate-last"));
    await waitFor(() => expect(reruns).toBe(1));
    unmount();

    controls({ redo: false });
    act(() => {
      useChatStore.getState().replaceMessages([
        { id: "u2", role: "user", content: "And of Spain?", timestamp: 4 },
        { id: "a2", role: "agent", content: "Madrid.", timestamp: 5 },
      ]);
      useChatStore.getState().setUndoRedo(true, false);
    });
    renderWithProviders(<ChatPanel />);
    await screen.findByTestId("edit-last");
    expect(screen.queryByTestId("regenerate-last")).not.toBeInTheDocument();
  });

  it("never offers a secret message for editing, nor anything while a turn runs", async () => {
    act(() => {
      useChatStore.getState().replaceMessages([
        { id: "u1", role: "user", content: "●●●●●●●●", timestamp: 2 },
        { id: "a1", role: "agent", content: "Stored.", timestamp: 3 },
      ]);
    });
    renderWithProviders(<ChatPanel />);
    await screen.findByTestId("regenerate-last");
    expect(screen.queryByTestId("edit-last")).not.toBeInTheDocument();

    act(() => useChatStore.getState().setProcessing(true));
    expect(screen.queryByTestId("regenerate-last")).not.toBeInTheDocument();
  });

  it("shows the host's notice beside the action (the seam for tools whose effects stay)", async () => {
    const renderTurnNotice = vi.fn(({ action }: { action: string }) =>
      action === "regenerate" ? "This turn ran tools; their effects stay." : null,
    );
    renderWithProviders(<ChatPanel renderTurnNotice={renderTurnNotice} />);
    expect(await screen.findByTestId("turn-notice")).toHaveTextContent("their effects stay");
    expect(renderTurnNotice).toHaveBeenCalledWith(expect.objectContaining({ action: "edit" }));
    expect(screen.getAllByTestId("turn-notice")).toHaveLength(1);
  });
});
