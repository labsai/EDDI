import { describe, it, expect, afterEach } from "vitest";
import {
  commentOffered,
  httpRatingClient,
  normalizeScore,
  parseRatingProfile,
  ratingErrorCode,
  ratingErrorMessage,
  RATING_CHANNEL,
} from "./rating-api";
import { ApiError, setBaseUrl } from "./http";
import { captureFetch } from "@/test-utils/sse";
import { setLocale } from "@/i18n";

const originalFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = originalFetch;
  setBaseUrl("");
  setLocale("en");
});

const refusal = (status: number, code: string) =>
  new ApiError(status, JSON.stringify({ error: code, message: "server words" }), "ctx");

describe("parseRatingProfile", () => {
  it("is null when the profile carries no rating, or asks for no control", () => {
    expect(parseRatingProfile(undefined)).toBeNull();
    expect(parseRatingProfile(null)).toBeNull();
    expect(parseRatingProfile("THUMBS")).toBeNull();
    expect(parseRatingProfile({ conversationRating: false, messageRating: false })).toBeNull();
  });

  it("reads the backend's record and clamps what it must not trust", () => {
    const profile = parseRatingProfile({
      scale: "FIVE_STAR",
      conversationRating: true,
      promptText: "  How was it?  ",
      askOnEnd: false,
      askAfterTurns: 3.7,
      messageRating: true,
      comment: true,
      commentMaxLength: 99999,
      commentAskBelowScore: 7,
      reasonTags: ["Helpful", "", 3, "Too slow"],
      ratingWindowHours: 168,
    });
    expect(profile).toEqual({
      scale: "FIVE_STAR",
      conversationRating: true,
      promptText: "How was it?",
      askOnEnd: false,
      askAfterTurns: 3,
      messageRating: true,
      comment: true,
      commentMaxLength: 4000,
      commentAskBelowScore: 1,
      reasonTags: ["Helpful", "Too slow"],
      ratingWindowHours: 168,
    });
  });

  it("defaults an unknown scale to thumbs and missing fields to the backend defaults", () => {
    const profile = parseRatingProfile({ scale: "TEN_STAR" });
    expect(profile?.scale).toBe("THUMBS");
    expect(profile?.conversationRating).toBe(true);
    expect(profile?.askOnEnd).toBe(true);
    expect(profile?.messageRating).toBe(false);
    expect(profile?.comment).toBe(false);
    expect(profile?.promptText).toBeNull();
  });
});

describe("scores", () => {
  it("normalizes per scale", () => {
    expect(normalizeScore("FIVE_STAR", 1)).toBe(0);
    expect(normalizeScore("FIVE_STAR", 3)).toBe(0.5);
    expect(normalizeScore("THUMBS", 1)).toBe(1);
  });

  it("offers the comment box per askBelowScore", () => {
    const base = parseRatingProfile({ comment: true, commentAskBelowScore: 0.6 })!;
    expect(commentOffered(base, "FIVE_STAR", 2)).toBe(true);
    expect(commentOffered(base, "FIVE_STAR", 4)).toBe(false);
    expect(commentOffered({ ...base, commentAskBelowScore: 0 }, "FIVE_STAR", 5)).toBe(true);
    expect(commentOffered({ ...base, comment: false }, "FIVE_STAR", 1)).toBe(false);
  });
});

describe("rating errors", () => {
  it("reads the code from `error`", () => {
    expect(ratingErrorCode(refusal(409, "RATING_WINDOW_CLOSED"))).toBe("RATING_WINDOW_CLOSED");
    expect(ratingErrorCode(new ApiError(500, "not json", "ctx"))).toBeNull();
    expect(ratingErrorCode(new Error("x"))).toBeNull();
  });

  it.each([
    ["RATING_WINDOW_CLOSED", 409, "can no longer be rated"],
    ["RATING_DISABLED", 409, "not available for this assistant"],
    ["INVALID_STEP", 400, "reply can't be rated"],
    ["COMMENT_TOO_LONG", 400, "comment is too long"],
    ["INVALID_TAG", 400, "check it and try again"],
    ["INVALID_SCORE", 400, "check it and try again"],
    ["COMMENT_NOT_ALLOWED", 400, "check it and try again"],
    ["NOT_CONVERSATION_OWNER", 403, "Only the person"],
    ["CONVERSATION_NOT_FOUND", 404, "no longer exists"],
    ["processing_restricted", 403, "can't be recorded"],
  ])("maps %s to a friendly message, never the server's words", (code, status, text) => {
    const message = ratingErrorMessage(refusal(status, code));
    expect(message).toContain(text);
    expect(message).not.toContain("server words");
  });

  it("explains an expired session and falls back to a generic message", () => {
    expect(ratingErrorMessage(new ApiError(401, "", "ctx"))).toContain("session has expired");
    expect(ratingErrorMessage(new TypeError("Failed to fetch"))).toContain("could not be saved");
  });

  it("is translated", () => {
    setLocale("de");
    expect(ratingErrorMessage(refusal(409, "RATING_WINDOW_CLOSED"))).toContain("nicht mehr bewertet");
  });
});

describe("httpRatingClient", () => {
  it("PUTs a conversation rating with the chat-ui channel, omitting empty extras", async () => {
    const { calls } = captureFetch(200, JSON.stringify({ stepIndex: -1, score: 4, tags: [], comment: null }));
    await httpRatingClient.rateConversation("c/1", { score: 4, tags: [], comment: "  " });
    expect(calls[0].url).toBe("/agents/c%2F1/rating");
    expect(calls[0].init?.method).toBe("PUT");
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({ score: 4, channel: RATING_CHANNEL });
  });

  it("PUTs a reply rating to its step, with tags and the trimmed comment", async () => {
    const { calls } = captureFetch(200, "{}");
    await httpRatingClient.rateStep("c1", 3, { score: 0, tags: ["Too slow"], comment: " slow " });
    expect(calls[0].url).toBe("/agents/c1/steps/3/rating");
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({ score: 0, tags: ["Too slow"], comment: "slow", channel: "chat-ui" });
  });

  it("DELETEs to withdraw", async () => {
    const { calls } = captureFetch(204);
    await httpRatingClient.withdrawConversation("c1");
    await httpRatingClient.withdrawStep("c1", 2);
    expect(calls.map((c) => `${c.init?.method} ${c.url}`)).toEqual(["DELETE /agents/c1/rating", "DELETE /agents/c1/steps/2/rating"]);
  });

  it("reads ratings, treating an empty body as nothing rated", async () => {
    captureFetch(200, JSON.stringify({ conversation: { stepIndex: -1, score: 5, tags: [], comment: null }, steps: [{ stepIndex: 1, score: 1, tags: [], comment: null }] }));
    const ratings = await httpRatingClient.read("c1");
    expect(ratings.conversation?.score).toBe(5);
    expect(ratings.steps).toHaveLength(1);

    captureFetch(200, "");
    expect(await httpRatingClient.read("c1")).toEqual({ conversation: null, steps: [] });
  });

  it("reads the conversation's own controls: parsed, null when off, undefined when not sent", async () => {
    captureFetch(200, JSON.stringify({ conversation: null, steps: [], controls: { scale: "THUMBS", conversationRating: true } }));
    expect((await httpRatingClient.read("c1")).controls?.scale).toBe("THUMBS");

    captureFetch(200, JSON.stringify({ conversation: null, steps: [], controls: null }));
    expect((await httpRatingClient.read("c1")).controls).toBeNull();

    captureFetch(200, JSON.stringify({ conversation: null, steps: [] }));
    expect((await httpRatingClient.read("c1")).controls).toBeUndefined();
  });

  it("throws the server's refusal as an ApiError", async () => {
    captureFetch(409, JSON.stringify({ error: "RATING_DISABLED", message: "off" }));
    await expect(httpRatingClient.rateConversation("c1", { score: 1 })).rejects.toBeInstanceOf(ApiError);
  });
});
