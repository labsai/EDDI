/* ──────────────────────────────────────────────
   @eddi/core — conversation ratings (the owner's side)
   The person chatting rates the conversation, and — when the agent asks for
   it — single agent replies. Only the conversation's owner may rate; the
   server checks it, along with the agent version's `ratingConfig`, the rating
   window and the score/tag/comment rules (docs/conversation-ratings.md).

   Unlike most EDDI failures, the rating endpoints answer
   `{"error": "<CODE>", "message": "…"}` — the stable code is in `error`.

   Derived from the Chat UI's `api/rating-api.ts`. The copy a client shows for
   each code is the client's.
   ────────────────────────────────────────────── */

import { ApiError, encodeSegment, type HttpClient } from "./http";

export type RatingScale = "THUMBS" | "FIVE_STAR";

/**
 * What GET /agents/{agentId}/profile says about ratings (`rating`), or null
 * when the deployed version does not ask for any. Mirrors the backend's
 * `IRestAgentProfile.RatingProfile`.
 */
export interface RatingProfile {
  /** The scale of the conversation rating. Reply ratings are always thumbs. */
  scale: RatingScale;
  conversationRating: boolean;
  /** The card's question; null uses the client's own wording. */
  promptText: string | null;
  askOnEnd: boolean;
  /** Also offer the card after this many agent replies; 0 never. */
  askAfterTurns: number;
  messageRating: boolean;
  /** Whether a comment is accepted at all. */
  comment: boolean;
  commentMaxLength: number;
  /** Offer the comment box only below this normalized score; 0 always. */
  commentAskBelowScore: number;
  reasonTags: string[];
  ratingWindowHours: number;
}

/** A rating as the server stored it (the owner's view — no triage fields). */
export interface StoredRating {
  id?: string;
  conversationId?: string;
  /** -1 for the conversation as a whole, else the rated reply's step. */
  stepIndex: number;
  scale?: RatingScale;
  score: number;
  normalizedScore?: number;
  tags: string[];
  comment: string | null;
}

export interface ConversationRatings {
  conversation: StoredRating | null;
  steps: StoredRating[];
  /**
   * The rating controls of the version this conversation runs on — null when
   * it takes no ratings, undefined when the server did not say (an older one).
   */
  controls?: RatingProfile | null;
}

/** What a client sends. */
export interface RatingInput {
  score: number;
  tags?: string[];
  comment?: string | null;
}

/** The stepIndex of a rating of the conversation as a whole. */
export const CONVERSATION_STEP = -1;

const MAX_COMMENT = 4000;

/* ─── Profile ─────────────────────────────────── */

function bool(value: unknown, fallback: boolean): boolean {
  return typeof value === "boolean" ? value : fallback;
}

function num(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

/**
 * The profile's `rating` object, checked field by field — a newer or older
 * server can send more, less, or nothing. Null renders no rating controls.
 */
export function parseRatingProfile(raw: unknown): RatingProfile | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  const scale: RatingScale = r.scale === "FIVE_STAR" ? "FIVE_STAR" : "THUMBS";
  const tags = Array.isArray(r.reasonTags)
    ? r.reasonTags.filter((tag): tag is string => typeof tag === "string" && tag.trim() !== "")
    : [];
  const profile: RatingProfile = {
    scale,
    conversationRating: bool(r.conversationRating, true),
    promptText: typeof r.promptText === "string" && r.promptText.trim() ? r.promptText.trim() : null,
    askOnEnd: bool(r.askOnEnd, true),
    askAfterTurns: Math.max(0, Math.floor(num(r.askAfterTurns, 0))),
    messageRating: bool(r.messageRating, false),
    comment: bool(r.comment, false),
    commentMaxLength: Math.min(MAX_COMMENT, Math.max(1, Math.floor(num(r.commentMaxLength, 1000)))),
    commentAskBelowScore: Math.min(1, Math.max(0, num(r.commentAskBelowScore, 0))),
    reasonTags: tags,
    ratingWindowHours: Math.max(0, num(r.ratingWindowHours, 0)),
  };
  return profile.conversationRating || profile.messageRating ? profile : null;
}

/** A score on 0..1, the same on every scale (thumbs 0/1, stars (s−1)/4). */
export function normalizeScore(scale: RatingScale, score: number): number {
  return scale === "FIVE_STAR" ? (score - 1) / 4 : score;
}

/** Whether the comment box is offered for this score. */
export function commentOffered(profile: RatingProfile, scale: RatingScale, score: number): boolean {
  if (!profile.comment) return false;
  if (profile.commentAskBelowScore <= 0) return true;
  return normalizeScore(scale, score) < profile.commentAskBelowScore;
}

/* ─── Errors ──────────────────────────────────── */

/** The server's stable code (`error`), or null. */
export function ratingErrorCode(err: unknown): string | null {
  if (!(err instanceof ApiError) || !err.body.trim()) return null;
  try {
    const parsed = JSON.parse(err.body) as { error?: unknown };
    return typeof parsed.error === "string" ? parsed.error : null;
  } catch {
    return null;
  }
}

/* ─── Client ──────────────────────────────────── */

/** The rating operations; a demo can supply an in-memory twin. */
export interface RatingClient {
  read(conversationId: string): Promise<ConversationRatings>;
  rateConversation(conversationId: string, input: RatingInput): Promise<StoredRating | null>;
  rateStep(conversationId: string, stepIndex: number, input: RatingInput): Promise<StoredRating | null>;
  withdrawConversation(conversationId: string): Promise<void>;
  withdrawStep(conversationId: string, stepIndex: number): Promise<void>;
}

export function normalizeRatings(raw: ConversationRatings | null): ConversationRatings {
  const hasControls = !!raw && Object.prototype.hasOwnProperty.call(raw, "controls");
  return {
    conversation: raw?.conversation ?? null,
    steps: Array.isArray(raw?.steps) ? raw.steps : [],
    controls: hasControls ? parseRatingProfile((raw as { controls?: unknown }).controls) : undefined,
  };
}

function conversationPath(conversationId: string): string {
  return `/agents/${encodeSegment(conversationId)}/rating`;
}

function stepPath(conversationId: string, stepIndex: number): string {
  return `/agents/${encodeSegment(conversationId)}/steps/${stepIndex}/rating`;
}

/**
 * The real backend over one {@link HttpClient}. `channel` is how the client
 * names itself to the server (the Chat UI sends "chat-ui").
 */
export function createRatingClient(http: HttpClient, channel: string): RatingClient {
  const jsonPut = (input: RatingInput): RequestInit => {
    const payload: Record<string, unknown> = { score: input.score, channel };
    if (input.tags?.length) payload.tags = input.tags;
    const comment = input.comment?.trim();
    if (comment) payload.comment = comment;
    return { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) };
  };

  return {
    async read(conversationId) {
      return normalizeRatings(
        await http.requestJson<ConversationRatings>(conversationPath(conversationId), undefined, "Failed to read ratings"),
      );
    },
    rateConversation(conversationId, input) {
      return http.requestJson<StoredRating>(conversationPath(conversationId), jsonPut(input), "Failed to rate the conversation");
    },
    rateStep(conversationId, stepIndex, input) {
      return http.requestJson<StoredRating>(stepPath(conversationId, stepIndex), jsonPut(input), "Failed to rate the reply");
    },
    async withdrawConversation(conversationId) {
      await http.request(conversationPath(conversationId), { method: "DELETE" }, "Failed to withdraw the rating");
    },
    async withdrawStep(conversationId, stepIndex) {
      await http.request(stepPath(conversationId, stepIndex), { method: "DELETE" }, "Failed to withdraw the rating");
    },
  };
}
