/* ──────────────────────────────────────────────
   ChatWidget — conversation ratings, end to end against a mocked backend:
   the profile decides whether anything renders, the reply thumbs reach the
   right step, the card offers itself on end and opens from the header, and
   a reload restores what was rated.
   ────────────────────────────────────────────── */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { ChatWidget } from "./ChatWidget";
import { ChatProvider } from "@/store/chat-store";

const originalFetch = globalThis.fetch;

interface Call {
  method: string;
  url: string;
  body?: string;
}

afterEach(() => {
  globalThis.fetch = originalFetch;
  vi.restoreAllMocks();
});

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
  window.localStorage.clear();
});

function renderWidget() {
  return render(
    <MemoryRouter initialEntries={["/chat/production/agent-1"]}>
      <ChatProvider>
        <Routes>
          <Route path="/chat/:environment/:agentId" element={<ChatWidget />} />
        </Routes>
      </ChatProvider>
    </MemoryRouter>,
  );
}

const RATING = {
  scale: "FIVE_STAR",
  conversationRating: true,
  promptText: null,
  askOnEnd: true,
  askAfterTurns: 0,
  messageRating: true,
  comment: false,
  commentMaxLength: 1000,
  commentAskBelowScore: 0,
  reasonTags: [],
  ratingWindowHours: 168,
};

function mockBackend({
  rating = RATING as unknown,
  state = "READY",
  stored = { conversation: null, steps: [] } as unknown,
  rateStatus = 200,
  snapshot,
}: { rating?: unknown; state?: string; stored?: unknown; rateStatus?: number; snapshot?: unknown } = {}) {
  const calls: Call[] = [];
  globalThis.fetch = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    const href = String(url);
    const method = init?.method ?? "GET";
    calls.push({ method, url: href, body: init?.body ? String(init.body) : undefined });
    if (href.includes("/start")) {
      return new Response(null, { status: 201, headers: { Location: "/agents/conv-1" } });
    }
    if (href.includes("/profile")) {
      return new Response(JSON.stringify({ agentId: "agent-1", name: "Helper", reviewNotice: null, rating }), { status: 200 });
    }
    if (href.includes("/rating")) {
      if (method === "GET") return new Response(JSON.stringify(stored), { status: 200 });
      if (rateStatus !== 200) {
        return new Response(JSON.stringify({ error: "RATING_WINDOW_CLOSED", message: "late" }), { status: rateStatus });
      }
      return new Response(method === "DELETE" ? null : "{}", { status: method === "DELETE" ? 204 : 200 });
    }
    if (snapshot) return new Response(JSON.stringify(snapshot), { status: 200 });
    return new Response(
      JSON.stringify({
        conversationId: "conv-1",
        conversationState: state,
        conversationOutputs: [{ output: [{ type: "text", text: "Hello, how can I help?" }], quickReplies: [] }],
      }),
      { status: 200 },
    );
  }) as typeof fetch;
  return calls;
}

describe("ChatWidget ratings", () => {
  it("renders no rating control when the profile carries no rating", async () => {
    const calls = mockBackend({ rating: null });
    renderWidget();
    await screen.findByText("Hello, how can I help?");
    await waitFor(() => expect(calls.some((c) => c.url.includes("/profile"))).toBe(true));
    expect(screen.queryByTestId("step-rating-up")).toBeNull();
    expect(screen.queryByTestId("rate-conversation")).toBeNull();
    expect(screen.queryByTestId("rating-card")).toBeNull();
    expect(calls.some((c) => c.url.includes("/rating"))).toBe(false);
  });

  it("shows only the header entry when the agent asks for a conversation rating but no reply ratings", async () => {
    mockBackend({ rating: { ...RATING, messageRating: false } });
    renderWidget();
    expect(await screen.findByTestId("rate-conversation")).toBeInTheDocument();
    await screen.findByText("Hello, how can I help?");
    expect(screen.queryByTestId("step-rating-up")).toBeNull();
  });

  it("puts thumbs under the greeting and rates its step (0) on the server", async () => {
    const calls = mockBackend();
    renderWidget();
    const up = await screen.findByTestId("step-rating-up");
    fireEvent.click(up);
    await waitFor(() => expect(up).toHaveAttribute("aria-pressed", "true"));
    const put = calls.find((c) => c.method === "PUT");
    expect(put?.url).toContain("/agents/conv-1/steps/0/rating");
    expect(JSON.parse(put!.body!)).toEqual({ score: 1, channel: "chat-ui" });
  });

  it("rolls a refused thumb back and shows a friendly notice", async () => {
    mockBackend({ rateStatus: 409 });
    renderWidget();
    const up = await screen.findByTestId("step-rating-up");
    fireEvent.click(up);
    expect(await screen.findByTestId("rating-toast")).toHaveTextContent("This conversation can no longer be rated.");
    expect(up).toHaveAttribute("aria-pressed", "false");
  });

  it("opens the rating card from the header and rates the conversation", async () => {
    const calls = mockBackend();
    renderWidget();
    fireEvent.click(await screen.findByTestId("rate-conversation"));
    fireEvent.click(await screen.findByRole("radio", { name: "4 of 5 stars" }));
    expect(await screen.findByTestId("rating-card-thanks")).toBeInTheDocument();
    const put = calls.find((c) => c.method === "PUT" && c.url.endsWith("/agents/conv-1/rating"));
    expect(JSON.parse(put!.body!)).toEqual({ score: 4, channel: "chat-ui" });
    // The composer was never blocked.
    expect(screen.getByTestId("chat-input")).not.toBeDisabled();
  });

  it("offers the card by itself when the conversation has ended", async () => {
    mockBackend({ state: "ENDED" });
    renderWidget();
    expect(await screen.findByTestId("rating-card")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("rating-card-close"));
    expect(screen.queryByTestId("rating-card")).toBeNull();
  });

  it("rebuilds from the steps when a full read's lists disagree, so the last reply rates its own step", async () => {
    const step = (data: Array<{ key: string; value: unknown }>) => ({ conversationStep: data, timestamp: "2026-10-25T10:00:00Z" });
    const calls = mockBackend({
      rating: { ...RATING, conversationRating: false },
      snapshot: {
        conversationId: "conv-1",
        conversationState: "READY",
        // Two outputs against three steps: their positions cannot be trusted.
        conversationOutputs: [
          { output: [{ type: "text", text: "Hello, how can I help?" }], quickReplies: [] },
          { output: [{ type: "text", text: "Second answer" }], quickReplies: [] },
        ],
        conversationSteps: [
          step([{ key: "output:text:P:1", value: ["Hello, how can I help?"] }]),
          step([{ key: "input:initial", value: "first question" }, { key: "output:text:P:1", value: ["First answer"] }]),
          step([{ key: "input:initial", value: "second question" }, { key: "output:text:P:1", value: ["Second answer"] }]),
        ],
      },
    });
    renderWidget();
    await screen.findByText("Second answer");
    const thumbs = await screen.findAllByTestId("step-rating-up");
    fireEvent.click(thumbs[thumbs.length - 1]);
    await waitFor(() => expect(calls.some((c) => c.method === "PUT")).toBe(true));
    expect(calls.find((c) => c.method === "PUT")?.url).toContain("/agents/conv-1/steps/2/rating");
  });

  it("restores the stored rating of the greeting's step", async () => {
    mockBackend({ stored: { conversation: null, steps: [{ stepIndex: 0, score: 0, tags: [], comment: null }] } });
    renderWidget();
    await waitFor(() => expect(screen.getByTestId("step-rating-down")).toHaveAttribute("aria-pressed", "true"));
  });
});
