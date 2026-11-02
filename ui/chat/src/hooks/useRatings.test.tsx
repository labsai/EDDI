import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { useRatings, RATING_TOAST_MS, type UseRatingsArgs } from "./useRatings";
import { parseRatingProfile, type RatingClient, type RatingProfile, type StoredRating } from "@/api/rating-api";
import { ApiError } from "@/api/http";
import type { ChatMessage, ConversationState } from "@/types";

const profile = (extra: Record<string, unknown> = {}): RatingProfile =>
  parseRatingProfile({ scale: "FIVE_STAR", messageRating: true, askOnEnd: true, askAfterTurns: 0, ...extra })!;

function client(overrides: Partial<RatingClient> = {}): RatingClient {
  return {
    read: vi.fn().mockResolvedValue({ conversation: null, steps: [] }),
    rateConversation: vi.fn().mockResolvedValue(null),
    rateStep: vi.fn().mockResolvedValue(null),
    withdrawConversation: vi.fn().mockResolvedValue(undefined),
    withdrawStep: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  };
}

let id = 0;
const msg = (role: "user" | "agent", content = "x"): ChatMessage => ({ id: `m${id++}`, role, content, timestamp: 0 });
const conversation = (turns: number): ChatMessage[] => {
  const messages = [msg("agent", "Hi")];
  for (let i = 0; i < turns; i++) messages.push(msg("user"), msg("agent"));
  return messages;
};

function setup(initial: Partial<UseRatingsArgs> = {}) {
  const args: UseRatingsArgs = {
    client: client(),
    conversationId: "conv-1",
    profile: profile(),
    messages: conversation(0),
    conversationState: "READY" as ConversationState,
    ...initial,
  };
  const hook = renderHook((props: UseRatingsArgs) => useRatings(props), { initialProps: args });
  return { ...hook, args };
}

const refusal = (code: string) => new ApiError(409, JSON.stringify({ error: code, message: "x" }), "ctx");

beforeEach(() => window.localStorage.clear());
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("useRatings", () => {
  it("restores what was rated when the conversation opens (reload)", async () => {
    const restored = client({
      read: vi.fn().mockResolvedValue({
        conversation: { stepIndex: -1, score: 4, tags: [], comment: null },
        steps: [{ stepIndex: 2, score: 0, tags: ["Too slow"], comment: null }],
      }),
    });
    const { result } = setup({ client: restored });
    await waitFor(() => expect(result.current.conversationRating?.score).toBe(4));
    expect(result.current.stepRatings[2].tags).toEqual(["Too slow"]);
    expect(restored.read).toHaveBeenCalledWith("conv-1");
  });

  it("keeps a thumb tapped while the restore read is still in flight", async () => {
    // The server answers the read as it was before the tap; applying that
    // answer verbatim would silently undo the person's rating.
    let answer!: (value: { conversation: null; steps: StoredRating[] }) => void;
    const c = client({ read: vi.fn().mockReturnValue(new Promise((resolve) => (answer = resolve))) });
    const { result } = setup({ client: c });
    await act(async () => {
      await result.current.rateStep(0, { score: 1 });
    });
    await act(async () => {
      // The older view: step 0 was a 0 before the tap.
      answer({ conversation: null, steps: [{ stepIndex: 0, score: 0, tags: [], comment: null }] });
    });
    expect(result.current.stepRatings[0]?.score).toBe(1);
  });

  it("a refused step rating before the read answers shows the server's value once it does", async () => {
    let answer!: (value: { conversation: null; steps: StoredRating[] }) => void;
    const c = client({
      read: vi.fn().mockReturnValue(new Promise((resolve) => (answer = resolve))),
      rateStep: vi.fn().mockRejectedValue(refusal("RATING_WINDOW_CLOSED")),
    });
    const { result } = setup({ client: c });
    await act(async () => {
      await result.current.rateStep(0, { score: 1 });
    });
    await act(async () => {
      answer({ conversation: null, steps: [{ stepIndex: 0, score: 0, tags: [], comment: null }] });
    });
    expect(result.current.stepRatings[0]?.score).toBe(0);
  });

  it("a refused step rating after the read rolls back to the server's value, not to nothing", async () => {
    const c = client({
      read: vi.fn().mockResolvedValue({ conversation: null, steps: [{ stepIndex: 0, score: 0, tags: [], comment: null }] }),
      rateStep: vi.fn().mockRejectedValue(refusal("RATING_WINDOW_CLOSED")),
    });
    const { result } = setup({ client: c });
    await waitFor(() => expect(result.current.stepRatings[0]?.score).toBe(0));
    await act(async () => {
      await result.current.rateStep(0, { score: 1 });
    });
    expect(result.current.stepRatings[0]?.score).toBe(0);
  });

  it("a refused conversation rating before the read answers shows the server's rating once it does", async () => {
    let answer!: (value: { conversation: StoredRating; steps: [] }) => void;
    const c = client({
      read: vi.fn().mockReturnValue(new Promise((resolve) => (answer = resolve))),
      rateConversation: vi.fn().mockRejectedValue(refusal("RATING_WINDOW_CLOSED")),
    });
    const { result } = setup({ client: c });
    await act(async () => {
      await result.current.rateConversation({ score: 1 });
    });
    await act(async () => {
      answer({ conversation: { stepIndex: -1, score: 4, tags: [], comment: null }, steps: [] });
    });
    expect(result.current.conversationRating?.score).toBe(4);
  });

  it("an older refusal does not undo a newer successful change", async () => {
    let refuse!: (error: unknown) => void;
    const c = client({
      rateStep: vi
        .fn()
        .mockReturnValueOnce(new Promise((_, reject) => (refuse = reject)))
        .mockResolvedValueOnce(null),
    });
    const { result } = setup({ client: c });
    await waitFor(() => expect(result.current.stepRatings).toEqual({}));
    let first!: Promise<boolean>;
    act(() => {
      first = result.current.rateStep(0, { score: 0 });
    });
    await act(async () => {
      await result.current.rateStep(0, { score: 1 });
    });
    await act(async () => {
      refuse(refusal("RATING_WINDOW_CLOSED"));
      await first;
    });
    expect(result.current.stepRatings[0]?.score).toBe(1);
  });

  it("keeps an optimistic rating across a profile reload for the same conversation", async () => {
    let confirm!: (value: null) => void;
    const c = client({
      read: vi.fn().mockResolvedValue({ conversation: null, steps: [] }),
      rateStep: vi.fn().mockReturnValue(new Promise((resolve) => (confirm = resolve))),
    });
    const { result, rerender, args } = setup({ client: c });
    await waitFor(() => expect(c.read).toHaveBeenCalledTimes(1));
    let write!: Promise<boolean>;
    act(() => {
      write = result.current.rateStep(0, { score: 1 });
    });
    // The profile drops out and comes back while the write is still in flight: the
    // effect re-runs for the same conversation and its read answers the older state.
    rerender({ ...args, profile: null });
    rerender({ ...args, profile: profile() });
    await waitFor(() => expect(c.read).toHaveBeenCalledTimes(2));
    expect(result.current.stepRatings[0]?.score).toBe(1);
    await act(async () => {
      confirm(null);
      await write;
    });
    expect(result.current.stepRatings[0]?.score).toBe(1);
  });

  it("lets a later restore read update a rating the server has confirmed long since", async () => {
    const c = client({
      read: vi
        .fn()
        .mockResolvedValueOnce({ conversation: null, steps: [] })
        .mockResolvedValueOnce({ conversation: null, steps: [{ stepIndex: 0, score: 0, tags: [], comment: null }] }),
    });
    const { result, rerender, args } = setup({ client: c });
    await act(async () => {
      await result.current.rateStep(0, { score: 1 });
    });
    rerender({ ...args, profile: null });
    rerender({ ...args, profile: profile() });
    await waitFor(() => expect(result.current.stepRatings[0]?.score).toBe(0));
  });

  it("does not close the card when the profile arrives after the person opened it", async () => {
    const { result, rerender, args } = setup({ profile: null });
    act(() => result.current.openCard());
    expect(result.current.cardOpen).toBe(false); // no controls yet, nothing to show
    rerender({ ...args, profile: profile() });
    await waitFor(() => expect(result.current.cardOpen).toBe(true));
  });

  it("renders the conversation's own controls once the server names them", async () => {
    // The profile follows the version a new conversation would get (thumbs);
    // this conversation runs on one that asks for stars and no reply ratings.
    const pinned = { scale: "FIVE_STAR", conversationRating: true, messageRating: false, askOnEnd: true, askAfterTurns: 0 };
    const c = client({ read: vi.fn().mockResolvedValue({ conversation: null, steps: [], controls: parseRatingProfile(pinned) }) });
    const { result } = setup({ client: c, profile: profile({ scale: "THUMBS", messageRating: true }) });
    await waitFor(() => expect(result.current.profile?.scale).toBe("FIVE_STAR"));
    expect(result.current.profile?.messageRating).toBe(false);
  });

  it("shows no card when the conversation's version takes no ratings, whatever the profile says", async () => {
    const c = client({ read: vi.fn().mockResolvedValue({ conversation: null, steps: [], controls: null }) });
    const { result } = setup({ client: c, conversationState: "ENDED" });
    await waitFor(() => expect(result.current.profile).toBeNull());
    act(() => result.current.openCard());
    expect(result.current.cardOpen).toBe(false);
  });

  it("keeps the profile's controls when an older server does not name the conversation's", async () => {
    const { result } = setup();
    await act(async () => {});
    expect(result.current.profile?.scale).toBe("FIVE_STAR");
  });

  it("does nothing at all without a rating profile", async () => {
    const c = client();
    const { result } = setup({ client: c, profile: null, conversationState: "ENDED" });
    await act(async () => {});
    expect(c.read).not.toHaveBeenCalled();
    expect(result.current.cardOpen).toBe(false);
  });

  it("rates a reply optimistically", async () => {
    let finish!: () => void;
    const c = client({ rateStep: vi.fn(() => new Promise<null>((resolve) => { finish = () => resolve(null); })) });
    const { result } = setup({ client: c });
    let saved: Promise<boolean>;
    act(() => {
      saved = result.current.rateStep(1, { score: 1 });
    });
    expect(result.current.stepRatings[1].score).toBe(1);
    await act(async () => {
      finish();
      expect(await saved).toBe(true);
    });
    expect(c.rateStep).toHaveBeenCalledWith("conv-1", 1, { score: 1 });
  });

  it("rolls a refused reply rating back and says why", async () => {
    const c = client({ rateStep: vi.fn().mockRejectedValue(refusal("RATING_WINDOW_CLOSED")) });
    const { result } = setup({ client: c });
    await waitFor(() => expect(c.read).toHaveBeenCalled());
    await act(async () => {
      expect(await result.current.rateStep(1, { score: 1 })).toBe(false);
    });
    expect(result.current.stepRatings[1]).toBeUndefined();
    expect(result.current.toast).toBe("This conversation can no longer be rated.");
  });

  it("rolls a failed withdrawal back to the previous rating", async () => {
    const c = client({
      read: vi.fn().mockResolvedValue({ conversation: null, steps: [{ stepIndex: 1, score: 1, tags: [], comment: null }] }),
      withdrawStep: vi.fn().mockRejectedValue(new TypeError("offline")),
    });
    const { result } = setup({ client: c });
    await waitFor(() => expect(result.current.stepRatings[1]).toBeDefined());
    await act(async () => {
      await result.current.rateStep(1, null);
    });
    expect(result.current.stepRatings[1].score).toBe(1);
    expect(result.current.toast).toContain("could not be saved");
  });

  it("rolls a refused conversation rating back; the toast goes away by itself", async () => {
    const c = client({ rateConversation: vi.fn().mockRejectedValue(refusal("RATING_DISABLED")) });
    const { result } = setup({ client: c });
    await waitFor(() => expect(c.read).toHaveBeenCalled());
    vi.useFakeTimers();
    await act(async () => {
      expect(await result.current.rateConversation({ score: 5 })).toBe(false);
    });
    expect(result.current.conversationRating).toBeNull();
    expect(result.current.toast).toContain("not available");
    act(() => {
      vi.advanceTimersByTime(RATING_TOAST_MS);
    });
    expect(result.current.toast).toBeNull();
  });

  it("forgets a reply's thumb locally after a rerun", async () => {
    const c = client({
      read: vi.fn().mockResolvedValue({ conversation: null, steps: [{ stepIndex: 3, score: 0, tags: [], comment: null }] }),
    });
    const { result } = setup({ client: c });
    await waitFor(() => expect(result.current.stepRatings[3]).toBeDefined());
    act(() => result.current.clearStep(3));
    expect(result.current.stepRatings[3]).toBeUndefined();
    expect(c.withdrawStep).not.toHaveBeenCalled();
  });

  describe("the card", () => {
    it("offers itself when the conversation ends (askOnEnd)", async () => {
      const { result, rerender, args } = setup();
      await waitFor(() => expect(args.client.read).toHaveBeenCalled());
      expect(result.current.cardOpen).toBe(false);
      rerender({ ...args, conversationState: "ENDED" });
      await waitFor(() => expect(result.current.cardOpen).toBe(true));
    });

    it("does not offer itself on end when askOnEnd is off", async () => {
      const { result, args } = setup({ profile: profile({ askOnEnd: false }), conversationState: "ENDED" });
      await waitFor(() => expect(args.client.read).toHaveBeenCalled());
      await act(async () => {});
      expect(result.current.cardOpen).toBe(false);
    });

    it("offers itself after askAfterTurns agent replies, not before", async () => {
      const { result, rerender, args } = setup({ profile: profile({ askOnEnd: false, askAfterTurns: 2 }), messages: conversation(1) });
      await waitFor(() => expect(args.client.read).toHaveBeenCalled());
      await act(async () => {});
      expect(result.current.cardOpen).toBe(false);
      rerender({ ...args, messages: conversation(2) });
      await waitFor(() => expect(result.current.cardOpen).toBe(true));
    });

    it("never asks somebody who already rated", async () => {
      const c = client({ read: vi.fn().mockResolvedValue({ conversation: { stepIndex: -1, score: 5, tags: [], comment: null }, steps: [] }) });
      const { result } = setup({ client: c, conversationState: "ENDED" });
      await waitFor(() => expect(result.current.conversationRating).not.toBeNull());
      await act(async () => {});
      expect(result.current.cardOpen).toBe(false);
    });

    it("offers itself at most once per conversation, even after a reload", async () => {
      const first = setup({ conversationState: "ENDED" });
      await waitFor(() => expect(first.result.current.cardOpen).toBe(true));
      first.unmount();

      // A reload: a fresh hook, same conversation.
      const second = setup({ conversationState: "ENDED" });
      await waitFor(() => expect(second.args.client.read).toHaveBeenCalled());
      await act(async () => {});
      expect(second.result.current.cardOpen).toBe(false);
    });

    it("remembers a dismissal, but the header entry still opens it", async () => {
      const { result, rerender, args } = setup({ profile: profile({ askAfterTurns: 1 }), messages: conversation(1) });
      await waitFor(() => expect(result.current.cardOpen).toBe(true));
      act(() => result.current.closeCard());
      expect(result.current.cardOpen).toBe(false);
      expect(JSON.parse(window.localStorage.getItem("eddi-chat.rating.conv-1")!)).toMatchObject({ dismissed: true });

      rerender({ ...args, messages: conversation(3), conversationState: "ENDED" });
      await act(async () => {});
      expect(result.current.cardOpen).toBe(false);

      act(() => result.current.openCard());
      expect(result.current.cardOpen).toBe(true);
    });

    it("works without storage (private mode) — once per page instead", async () => {
      vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
        throw new Error("blocked");
      });
      vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
        throw new Error("blocked");
      });
      const { result, rerender, args } = setup({ conversationState: "ENDED" });
      await waitFor(() => expect(result.current.cardOpen).toBe(true));
      act(() => result.current.closeCard());
      rerender({ ...args, messages: conversation(2) });
      await act(async () => {});
      expect(result.current.cardOpen).toBe(false);
    });

    it("starts over for another conversation", async () => {
      const { result, rerender, args } = setup({ conversationState: "ENDED" });
      await waitFor(() => expect(result.current.cardOpen).toBe(true));
      rerender({ ...args, conversationId: "conv-2", conversationState: "READY" });
      await waitFor(() => expect(args.client.read).toHaveBeenCalledWith("conv-2"));
      expect(result.current.cardOpen).toBe(false);
    });

    it("never opens when the agent asks for no conversation rating", async () => {
      const { result } = setup({ profile: profile({ conversationRating: false }), conversationState: "ENDED" });
      act(() => result.current.openCard());
      expect(result.current.cardOpen).toBe(false);
    });
  });
});
