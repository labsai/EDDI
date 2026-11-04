import { describe, it, expect, afterEach } from "vitest";
import { ApiError, setBaseUrl } from "@eddi/core";
import { httpRatingClient, ratingErrorMessage, RATING_CHANNEL } from "./rating-api";
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

describe("ratingErrorMessage", () => {
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
});
