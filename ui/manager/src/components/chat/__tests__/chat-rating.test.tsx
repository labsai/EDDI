import { describe, expect, it, vi } from "vitest";
import { act, fireEvent, renderHook, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { toast } from "sonner";
import { server } from "@/test/mocks/server";
import { renderWithProviders } from "@/test/test-utils";
import type { ChatMessage } from "@/lib/api/chat";
import type { RatingProfile } from "@/lib/api/ratings";
import { agentReplyCount, lastStepIndex, ratableSteps, stepIndexes } from "@/lib/chat-rating-steps";
import { useChatRatings } from "@/hooks/use-chat-ratings";
import { ChatRatingCard, ChatStepRating } from "../chat-rating";

const profile = (extra: Partial<RatingProfile> = {}): RatingProfile => ({
  scale: "FIVE_STAR",
  conversationRating: true,
  promptText: null,
  askOnEnd: true,
  askAfterTurns: 0,
  messageRating: true,
  comment: true,
  commentMaxLength: 1000,
  commentAskBelowScore: 0,
  reasonTags: ["Wrong answer"],
  ratingWindowHours: 168,
  ...extra,
});

let n = 0;
const msg = (role: ChatMessage["role"], content = "x", extra: Partial<ChatMessage> = {}): ChatMessage => ({
  id: `m${n++}`,
  role,
  content,
  timestamp: 0,
  ...extra,
});

describe("chat rating steps", () => {
  it("counts live turns from the greeting and from stamped messages", () => {
    const messages = [msg("agent", "Hi"), msg("user"), msg("agent"), msg("system", "approved", { kind: "decision" }), msg("user"), msg("agent", "a"), msg("agent", "b")];
    expect(stepIndexes(messages)).toEqual([0, 1, 1, null, 2, 2, 2]);
    expect(ratableSteps(messages)).toEqual([0, null, 1, null, null, null, 2]);
    expect(agentReplyCount(messages)).toBe(2);
    expect(lastStepIndex(messages)).toBe(2);
    expect(stepIndexes([msg("agent", "x", { stepIndex: 4 }), msg("user"), msg("agent")])).toEqual([4, 5, 5]);
  });

  it("never offers thumbs on a failed or streaming reply", () => {
    expect(ratableSteps([msg("agent", "err", { isError: true }), msg("agent", "…", { isStreaming: true })])).toEqual([null, null]);
  });
});

describe("ChatStepRating", () => {
  it("rates, withdraws, and offers reasons on thumbs down", () => {
    const onRate = vi.fn().mockResolvedValue(true);
    const { rerender } = renderWithProviders(<ChatStepRating stepIndex={2} profile={profile()} onRate={onRate} />);
    fireEvent.click(screen.getByTestId("chat-rating-down"));
    expect(onRate).toHaveBeenCalledWith(2, { score: 0 });
    expect(screen.getByTestId("chat-rating-details")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Wrong answer" }));
    fireEvent.click(screen.getByTestId("chat-rating-send"));
    expect(onRate).toHaveBeenLastCalledWith(2, { score: 0, tags: ["Wrong answer"], comment: "" });

    rerender(<ChatStepRating stepIndex={2} profile={profile()} onRate={onRate} rating={{ stepIndex: 2, score: 1, tags: [], comment: null }} />);
    expect(screen.getByTestId("chat-rating-up")).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(screen.getByTestId("chat-rating-up"));
    expect(onRate).toHaveBeenLastCalledWith(2, null);
  });
});

describe("ChatRatingCard", () => {
  it("one tap on a star rates, then reasons, then thanks", async () => {
    const onRate = vi.fn().mockResolvedValue(true);
    renderWithProviders(<ChatRatingCard profile={profile()} current={null} onRate={onRate} onClose={() => {}} />);
    fireEvent.click(screen.getByTestId("chat-rating-star-2"));
    expect(onRate).toHaveBeenCalledWith({ score: 2, tags: [], comment: "" });
    fireEvent.click(await screen.findByTestId("chat-rating-card-send"));
    expect(await screen.findByTestId("chat-rating-thanks")).toBeInTheDocument();
  });

  it("thumbs scale, straight to thanks when there is nothing more to ask", async () => {
    const onRate = vi.fn().mockResolvedValue(true);
    renderWithProviders(<ChatRatingCard profile={profile({ scale: "THUMBS", reasonTags: [], comment: false })} current={null} onRate={onRate} onClose={() => {}} />);
    fireEvent.click(screen.getByTestId("chat-rating-card-up"));
    expect(await screen.findByTestId("chat-rating-thanks")).toBeInTheDocument();
  });
});

describe("useChatRatings", () => {
  it("restores, rates optimistically, and rolls a refusal back with a toast", async () => {
    const error = vi.spyOn(toast, "error");
    server.use(
      http.get("*/agents/:conversationId/rating", () =>
        HttpResponse.json({ conversation: null, steps: [{ stepIndex: 1, score: 1, tags: [], comment: null }] }),
      ),
      http.put("*/agents/:conversationId/steps/:stepIndex/rating", () =>
        HttpResponse.json({ error: "RATING_WINDOW_CLOSED", message: "late" }, { status: 409 }),
      ),
    );
    const { result } = renderHook(() => useChatRatings("conv-9", profile(), [msg("agent", "Hi")]));
    await waitFor(() => expect(result.current.stepRatings[1]?.score).toBe(1));
    await act(async () => {
      expect(await result.current.rateReply(0, { score: 0 })).toBe(false);
    });
    expect(result.current.stepRatings[0]).toBeUndefined();
    expect(error).toHaveBeenCalledWith("This conversation can no longer be rated.");
  });

  it("renders the conversation's own controls once the server names them", async () => {
    server.use(
      http.get("*/agents/:conversationId/rating", () =>
        HttpResponse.json({ conversation: null, steps: [], controls: profile({ scale: "THUMBS", messageRating: false }) }),
      ),
    );
    const { result } = renderHook(() => useChatRatings("conv-c", profile(), [msg("agent", "Hi")]));
    await waitFor(() => expect(result.current.profile?.scale).toBe("THUMBS"));
    expect(result.current.profile?.messageRating).toBe(false);
  });

  it("shows no card when the conversation's version takes no ratings", async () => {
    server.use(http.get("*/agents/:conversationId/rating", () => HttpResponse.json({ conversation: null, steps: [], controls: null })));
    const { result } = renderHook(() => useChatRatings("conv-d", profile(), [msg("agent", "Hi")]));
    await waitFor(() => expect(result.current.profile).toBeNull());
    act(() => result.current.openCard());
    expect(result.current.cardOpen).toBe(false);
  });

  it("offers the card after askAfterTurns replies, once, and on End when asked on end", async () => {
    const replies = (count: number) => {
      const list = [msg("agent", "Hi")];
      for (let i = 0; i < count; i++) list.push(msg("user"), msg("agent"));
      return list;
    };
    const { result, rerender } = renderHook(({ messages }) => useChatRatings("conv-1", profile({ askAfterTurns: 2 }), messages), {
      initialProps: { messages: replies(1) },
    });
    await waitFor(() => expect(result.current.cardOpen).toBe(false));
    rerender({ messages: replies(2) });
    await waitFor(() => expect(result.current.cardOpen).toBe(true));
    act(() => result.current.closeCard());
    rerender({ messages: replies(3) });
    expect(result.current.cardOpen).toBe(false);
    act(() => result.current.onEnded("conv-1"));
    expect(result.current.endedCard).toBeNull();

    const ended = renderHook(() => useChatRatings("conv-2", profile(), [msg("agent", "Hi")]));
    await waitFor(() => expect(ended.result.current.profile).not.toBeNull());
    act(() => ended.result.current.onEnded("conv-2"));
    expect(ended.result.current.endedCard?.conversationId).toBe("conv-2");
  });

  it("keeps the ended conversation's card after ending clears the transcript, and rates that conversation", async () => {
    const puts: string[] = [];
    server.use(
      http.put("*/agents/:conversationId/rating", ({ params }) => {
        puts.push(String(params.conversationId));
        return HttpResponse.json({});
      }),
    );
    const { result, rerender } = renderHook(({ id }: { id: string | null }) => useChatRatings(id, profile(), [msg("agent", "Hi")]), {
      initialProps: { id: "conv-3" as string | null },
    });
    await waitFor(() => expect(result.current.profile).not.toBeNull());
    // Ending clears the open conversation before the mutation's onSuccess runs.
    rerender({ id: null });
    act(() => result.current.onEnded("conv-3"));
    expect(result.current.endedCard).toMatchObject({ conversationId: "conv-3", current: null });

    await act(async () => {
      expect(await result.current.rateWhole({ score: 4 })).toBe(true);
    });
    expect(puts).toEqual(["conv-3"]);
    expect(result.current.endedCard?.current?.score).toBe(4);

    // Opening another conversation replaces the ended one's card.
    rerender({ id: "conv-4" });
    await waitFor(() => expect(result.current.endedCard).toBeNull());
  });

  it("does nothing for an agent that asks for no ratings", async () => {
    let read = false;
    server.use(
      http.get("*/agents/:conversationId/rating", () => {
        read = true;
        return HttpResponse.json({ conversation: null, steps: [] });
      }),
    );
    const { result } = renderHook(() => useChatRatings("conv-3", null, []));
    act(() => result.current.openCard());
    expect(result.current.cardOpen).toBe(false);
    expect(read).toBe(false);
  });
});
