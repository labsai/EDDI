import { describe, it, expect, beforeAll, beforeEach, vi } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { ChatPanel } from "../chat-panel";
import { useChatStore } from "@/hooks/use-chat";
import { useDebugStore } from "@/hooks/use-debug-events";

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

/** The Manager chat offers the same rating controls as the Chat UI when the agent's profile asks for them. */
describe("ChatPanel — ratings", () => {
  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    useChatStore.getState().reset();
    useChatStore.setState({ streamingEnabled: false });
    useDebugStore.getState().reset();
    useChatStore.getState().setSelectedAgent("agent1", "Test Agent");
    useChatStore.getState().setConversationId("conv1");
    useChatStore.getState().addMessage({ id: "w", role: "agent", content: "Welcome!", timestamp: 1 });
    useChatStore.getState().addMessage({ id: "u", role: "user", content: "Hi", timestamp: 2 });
    useChatStore.getState().addMessage({ id: "a", role: "agent", content: "Hello there", timestamp: 3 });
  });

  it("shows nothing without a rating profile", async () => {
    renderWithProviders(<ChatPanel />);
    await screen.findByText("Hello there");
    expect(screen.queryByTestId("chat-rate-conversation")).toBeNull();
    expect(screen.queryByTestId("chat-step-rating-1")).toBeNull();
  });

  it("puts thumbs under each step's reply and rates the right step", async () => {
    let rated = "";
    server.use(
      http.get("*/agents/:agentId/profile", () => HttpResponse.json({ agentId: "agent1", rating: RATING })),
      http.put("*/agents/:conversationId/steps/:stepIndex/rating", async ({ params, request }) => {
        rated = `${params.conversationId}/${params.stepIndex} ${JSON.stringify(await request.json())}`;
        return HttpResponse.json({ stepIndex: Number(params.stepIndex), score: 1, tags: [], comment: null });
      }),
    );
    renderWithProviders(<ChatPanel />);
    const step1 = await screen.findByTestId("chat-step-rating-1");
    expect(screen.getByTestId("chat-step-rating-0")).toBeInTheDocument();
    fireEvent.click(step1.querySelector("[data-testid=chat-rating-up]")!);
    await waitFor(() => expect(rated).toBe('conv1/1 {"score":1,"channel":"manager"}'));
  });

  it("opens the rating card from the header", async () => {
    server.use(http.get("*/agents/:agentId/profile", () => HttpResponse.json({ agentId: "agent1", rating: RATING })));
    renderWithProviders(<ChatPanel />);
    fireEvent.click(await screen.findByTestId("chat-rate-conversation"));
    expect(await screen.findByTestId("chat-rating-card")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("chat-rating-card-close"));
    expect(screen.queryByTestId("chat-rating-card")).toBeNull();
  });

  it("offers the card after End, though ending clears the transcript, and rates the ended conversation", async () => {
    let rated = "";
    server.use(
      http.get("*/agents/:agentId/profile", () => HttpResponse.json({ agentId: "agent1", rating: RATING })),
      http.post("*/agents/:conversationId/endConversation", () => new HttpResponse(null, { status: 200 })),
      http.put("*/agents/:conversationId/rating", async ({ params, request }) => {
        rated = `${params.conversationId} ${JSON.stringify(await request.json())}`;
        return HttpResponse.json({});
      }),
    );
    renderWithProviders(<ChatPanel />);
    await screen.findByTestId("chat-rate-conversation");
    fireEvent.click(screen.getByTestId("end-conversation"));
    const card = await screen.findByTestId("chat-rating-card");
    await waitFor(() => expect(screen.queryByText("Hello there")).toBeNull());
    expect(card).toBeInTheDocument();
    fireEvent.click(card.querySelectorAll("[role=radio]")[3] as HTMLElement);
    await waitFor(() => expect(rated).toBe('conv1 {"score":4,"channel":"manager"}'));
  });
});
