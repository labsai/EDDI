/* ──────────────────────────────────────────────
   ChatWidget — the agent's conversationControls (B4), end to end against a
   mocked backend. Above all decision D9: HTML in a reply is rendered only for
   an agent whose profile says `rawHtml: true`; otherwise — and on a server
   that says nothing — it is shown as the text it is.
   ────────────────────────────────────────────── */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { ChatLocationProvider } from "@/components/ChatLocationProvider";
import { ChatWidget } from "./ChatWidget";
import { ChatProvider } from "@/store/chat-store";

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
  vi.restoreAllMocks();
});

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
  window.localStorage.clear();
});

const HTML_REPLY = "Here is <b>bold</b> and <u>underlined</u> text.";

function renderWidget() {
  return render(
    <ChatProvider>
      <ChatLocationProvider initialPath={"/chat/production/agent-1"}>
        <ChatWidget />
      </ChatLocationProvider>
    </ChatProvider>,
  );
}

/** `profile` undefined: a server older than the field (no `conversationControls`). */
function mockBackend(conversationControls?: Record<string, boolean>) {
  const urls: string[] = [];
  globalThis.fetch = vi.fn(async (url: string | URL | Request) => {
    const href = String(url);
    urls.push(href);
    if (href.includes("/start")) {
      return new Response(null, { status: 201, headers: { Location: "/agents/conv-1" } });
    }
    if (href.includes("/profile")) {
      return new Response(
        JSON.stringify({
          agentId: "agent-1",
          name: "Helper",
          reviewNotice: null,
          rating: null,
          ...(conversationControls ? { conversationControls } : {}),
        }),
        { status: 200 },
      );
    }
    return new Response(
      JSON.stringify({
        conversationId: "conv-1",
        conversationState: "READY",
        undoAvailable: true,
        redoAvailable: true,
        conversationOutputs: [
          { output: [{ type: "text", text: HTML_REPLY }], quickReplies: [{ value: "Yes", expressions: "yes" }] },
        ],
      }),
      { status: 200 },
    );
  }) as typeof fetch;
  return urls;
}

const ALL_ON = {
  undo: true,
  redo: true,
  attachments: true,
  rating: true,
  quickReplies: true,
  streaming: true,
};

describe("ChatWidget conversationControls", () => {
  it("shows HTML in a reply as text when the agent does not enable rawHtml (D9)", async () => {
    const urls = mockBackend({ ...ALL_ON, rawHtml: false });
    const { container } = renderWidget();
    await waitFor(() => expect(urls.some((u) => u.includes("/profile"))).toBe(true));
    await waitFor(() => expect(container.querySelector(".markdown-body")?.textContent).toContain("<b>bold</b>"));
    expect(container.querySelector(".markdown-body b")).toBeNull();
    expect(container.querySelector(".markdown-body u")).toBeNull();
  });

  it("shows HTML as text on a server that reports no controls", async () => {
    const urls = mockBackend();
    const { container } = renderWidget();
    await waitFor(() => expect(urls.some((u) => u.includes("/profile"))).toBe(true));
    await waitFor(() => expect(container.querySelector(".markdown-body")?.textContent).toContain("<u>underlined</u>"));
    expect(container.querySelector(".markdown-body b")).toBeNull();
  });

  it("renders HTML in a reply, sanitised, when the agent sets rawHtml: true", async () => {
    mockBackend({ ...ALL_ON, rawHtml: true });
    const { container } = renderWidget();
    // The raw-HTML parser is a lazy chunk: wait for it.
    await waitFor(() => expect(container.querySelector(".markdown-body b")?.textContent).toBe("bold"), {
      timeout: 5000,
    });
    expect(container.querySelector(".markdown-body")?.textContent).not.toContain("<b>");
  });

  it("hides the controls the agent switched off", async () => {
    mockBackend({ undo: false, redo: false, attachments: false, rating: true, quickReplies: false, streaming: true, rawHtml: false });
    const { container } = renderWidget();
    await waitFor(() => expect(container.querySelector(".markdown-body")?.textContent).toContain("bold"));
    await waitFor(() => expect(screen.queryByTestId("chat-attach-btn")).toBeNull());
    expect(screen.queryByRole("button", { name: "Yes" })).toBeNull();
    expect(container.querySelector(".quick-replies")).toBeNull();
  });

  it("keeps the controls when the agent leaves them on", async () => {
    mockBackend({ ...ALL_ON, rawHtml: false });
    renderWidget();
    expect(await screen.findByTestId("chat-attach-btn")).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Yes" })).toBeInTheDocument();
  });
});
