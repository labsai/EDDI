/* ──────────────────────────────────────────────
   ChatWidget — a conversation ended because its agent version was retired

   With version following, an agent update that could not carry a running
   conversation over (a breaking change whose old version was then undeployed
   with "end all active conversations") ends it with
   `endReason: "agent-version-retired"`. The next message is refused; the
   widget says the assistant was updated and points at a new conversation,
   instead of a bare "Conversation Ended".
   ────────────────────────────────────────────── */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { ChatWidget } from "./ChatWidget";
import { ChatProvider, chatReducer, initialState } from "@/store/chat-store";

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
  vi.restoreAllMocks();
});

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
});

function renderWidget(query = "") {
  return render(
    <MemoryRouter initialEntries={[`/chat/production/agent-1${query}`]}>
      <ChatProvider>
        <Routes>
          <Route path="/chat/:environment/:agentId" element={<ChatWidget />} />
        </Routes>
      </ChatProvider>
    </MemoryRouter>,
  );
}

/**
 * A backend whose conversation is live until the first message, which it
 * refuses because the conversation has meanwhile ended. `endReason` is what
 * the snapshot read afterwards carries.
 */
function mockEndedOnSend({ streaming, endReason }: { streaming: boolean; endReason?: string }) {
  let ended = false;
  globalThis.fetch = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    const href = String(url);
    if (href.includes("/start")) {
      return new Response(null, { status: 201, headers: { Location: "/agents/conv-1" } });
    }
    if (href.includes("/agentstore/")) return new Response("{}", { status: 200 });
    if (href.includes("/stream")) {
      ended = true;
      return new Response(
        'event: error\ndata: {"message":"Conversation has ended","code":"conversation_ended"}\n\n',
        { status: 200, headers: { "Content-Type": "text/event-stream" } },
      );
    }
    if (init?.method === "POST" && !streaming) {
      ended = true;
      return new Response("Conversation has ended", { status: 410 });
    }
    return new Response(
      JSON.stringify({
        conversationId: "conv-1",
        conversationState: ended ? "ENDED" : "READY",
        conversationSteps: [],
        ...(ended && endReason ? { endReason } : {}),
      }),
      { status: 200 },
    );
  }) as typeof fetch;
}

async function send(text: string) {
  const input = await screen.findByTestId("chat-input");
  fireEvent.change(input, { target: { value: text } });
  fireEvent.keyDown(input, { key: "Enter", shiftKey: false });
}

describe("ChatWidget — conversation ended by an agent update", () => {
  it("streaming: explains the update and offers a new conversation", async () => {
    mockEndedOnSend({ streaming: true, endReason: "agent-version-retired" });
    renderWidget();

    await send("hello?");

    const notice = await screen.findByTestId("chat-ended-retired");
    expect(notice).toHaveTextContent(/assistant was updated/i);
    expect(notice).toHaveTextContent(/start a new conversation/i);
    // The existing "new conversation" action sits right beside it.
    expect(screen.getByRole("button", { name: /start new conversation/i })).toBeInTheDocument();
    expect(screen.queryByText("Conversation Ended")).toBeNull();
  });

  it("non-streaming: a 410 on a retired conversation shows the same notice", async () => {
    mockEndedOnSend({ streaming: false, endReason: "agent-version-retired" });
    renderWidget("?hideStreaming=true");

    await send("hello?");

    expect(await screen.findByTestId("chat-ended-retired")).toBeInTheDocument();
    // The refused message was never consumed, so it is not left in the
    // transcript as if it had been sent.
    await waitFor(() => expect(screen.queryByText("hello?")).toBeNull());
  });

  it("keeps the plain ended footer when the conversation ended for another reason", async () => {
    mockEndedOnSend({ streaming: true });
    renderWidget();

    await send("hello?");

    expect(await screen.findByText("Conversation Ended")).toBeInTheDocument();
    expect(screen.queryByTestId("chat-ended-retired")).toBeNull();
  });

  /**
   * The 410 path re-reads the conversation before deciding. A restart while that
   * read is in flight must win: the stale answer describes the OLD conversation
   * and must not stamp the retired footer onto the new one.
   */
  it("non-streaming: a restart during the 410 re-read keeps the new conversation live", async () => {
    let releaseRead: (() => void) | undefined;
    let refused = false;
    globalThis.fetch = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const href = String(url);
      if (href.includes("/start")) {
        const id = refused ? "conv-2" : "conv-1";
        return new Response(null, { status: 201, headers: { Location: `/agents/${id}` } });
      }
      if (href.includes("/agentstore/")) return new Response("{}", { status: 200 });
      if (init?.method === "POST") {
        refused = true;
        return new Response("Conversation has ended", { status: 410 });
      }
      if (refused && href.includes("conv-1")) {
        // The re-read after the 410: held until the test has restarted.
        await new Promise<void>((resolve) => (releaseRead = resolve));
        return new Response(
          JSON.stringify({
            conversationId: "conv-1",
            conversationState: "ENDED",
            conversationSteps: [],
            endReason: "agent-version-retired",
          }),
          { status: 200 },
        );
      }
      return new Response(
        JSON.stringify({ conversationId: href.includes("conv-2") ? "conv-2" : "conv-1", conversationState: "READY", conversationSteps: [] }),
        { status: 200 },
      );
    }) as typeof fetch;
    renderWidget("?hideStreaming=true");

    await send("hello?");
    await waitFor(() => expect(releaseRead).toBeDefined());
    fireEvent.click(screen.getByTestId("restart-btn"));
    fireEvent.click(await screen.findByTestId("restart-confirm-yes"));
    await waitFor(() =>
      expect((globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.some(([u]) => String(u).includes("/start") && refused)).toBe(true),
    );
    releaseRead!();

    expect(await screen.findByTestId("chat-input")).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByTestId("chat-ended-retired")).toBeNull();
  });

  it("starting a new conversation clears the notice", async () => {
    mockEndedOnSend({ streaming: true, endReason: "agent-version-retired" });
    renderWidget();
    await send("hello?");
    await screen.findByTestId("chat-ended-retired");

    // The new conversation is live again.
    mockEndedOnSend({ streaming: true, endReason: "agent-version-retired" });
    fireEvent.click(screen.getByRole("button", { name: /start new conversation/i }));

    await waitFor(() => expect(screen.queryByTestId("chat-ended-retired")).toBeNull());
    expect(await screen.findByTestId("chat-input")).toBeInTheDocument();
  });
});

describe("chatReducer — endReason", () => {
  it("records the reason with an ENDED state", () => {
    const next = chatReducer(initialState, {
      type: "SET_CONVERSATION_STATE",
      state: "ENDED",
      endReason: "agent-version-retired",
    });
    expect(next.endReason).toBe("agent-version-retired");
  });

  it("keeps a known reason when a source without one (the done payload) repeats ENDED", () => {
    const ended = chatReducer(initialState, {
      type: "SET_CONVERSATION_STATE",
      state: "ENDED",
      endReason: "agent-version-retired",
    });
    const again = chatReducer(ended, { type: "SET_CONVERSATION_STATE", state: "ENDED" });
    expect(again.endReason).toBe("agent-version-retired");
  });

  it("drops the reason once the conversation is no longer ended, and on clear", () => {
    const ended = chatReducer(initialState, {
      type: "SET_CONVERSATION_STATE",
      state: "ENDED",
      endReason: "agent-version-retired",
    });
    expect(chatReducer(ended, { type: "SET_CONVERSATION_STATE", state: "READY" }).endReason).toBeNull();
    expect(chatReducer(ended, { type: "CLEAR_MESSAGES" }).endReason).toBeNull();
  });
});
