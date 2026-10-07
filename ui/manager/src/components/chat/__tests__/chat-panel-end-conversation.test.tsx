import { describe, it, expect, beforeAll, beforeEach, vi } from "vitest";
import { act, screen, waitFor, fireEvent } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

const toastError = vi.hoisted(() => vi.fn());
vi.mock("sonner", async (importOriginal) => {
  const actual = await importOriginal<typeof import("sonner")>();
  return { ...actual, toast: { ...actual.toast, error: toastError, success: vi.fn() } };
});

import { ChatPanel } from "../chat-panel";
import { useChatStore } from "@/hooks/use-chat";
import { useDebugStore } from "@/hooks/use-debug-events";

/**
 * "End Conversation" used to swallow a failure (the transcript simply stayed,
 * with no word why) and, on success, cleared whatever conversation was on
 * screen when the request RETURNED — which, after a switch, was not the one
 * ended.
 */
describe("ChatPanel — End Conversation", () => {
  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    toastError.mockReset();
    useChatStore.getState().reset();
    useChatStore.setState({ streamingEnabled: false });
    useDebugStore.getState().reset();
    useChatStore.getState().setSelectedAgent("agent1", "Test Agent");
    useChatStore.getState().setConversationId("conv1");
    useChatStore.getState().addMessage({ id: "m1", role: "user", content: "Hi", timestamp: 1 });
  });

  it("says why when ending fails, and keeps the transcript", async () => {
    server.use(
      http.post("*/agents/:convId/endConversation", () => new HttpResponse(null, { status: 500 })),
    );
    renderWithProviders(<ChatPanel />);

    fireEvent.click(screen.getByTestId("end-conversation"));

    await waitFor(() => expect(toastError).toHaveBeenCalledTimes(1));
    expect(toastError.mock.calls[0]![0]).toContain("HTTP 500");
    expect(useChatStore.getState().conversationId).toBe("conv1");
    expect(useChatStore.getState().messages).toHaveLength(1);
  });

  it("ends the conversation on screen at click time and leaves the one opened since", async () => {
    let ended: string | null = null;
    let release!: () => void;
    const released = new Promise<void>((r) => (release = r));
    server.use(
      http.post("*/agents/:convId/endConversation", async ({ params }) => {
        ended = String(params.convId);
        await released;
        return new HttpResponse(null, { status: 200 });
      }),
    );
    renderWithProviders(<ChatPanel />);

    fireEvent.click(screen.getByTestId("end-conversation"));
    await waitFor(() => expect(ended).toBe("conv1"));

    // The user opens another conversation while the end is in flight.
    act(() => {
      useChatStore.getState().clearMessages();
      useChatStore.getState().setConversationId("conv2");
      useChatStore.getState().addMessage({ id: "m2", role: "user", content: "Other", timestamp: 2 });
    });
    await act(async () => {
      release();
      await released;
    });

    await waitFor(() => expect(toastError).not.toHaveBeenCalled());
    // Give the mutation a moment to settle before asserting nothing was cleared.
    await new Promise((r) => setTimeout(r, 20));
    expect(useChatStore.getState().conversationId).toBe("conv2");
    expect(useChatStore.getState().messages.map((m) => m.id)).toEqual(["m2"]);
  });
});
