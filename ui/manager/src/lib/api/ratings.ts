import { api } from "../api-client";
import { seg } from "./path-segment";

/**
 * Conversation ratings — the maintainers' `/ratings` endpoints (list, summary,
 * export, triage) and the owner's `/agents/{conversationId}/rating` endpoints the
 * Manager's own chat uses. See docs/conversation-ratings.md.
 *
 * Scoping is the backend's: an administrator sees every agent and the rater's
 * userId; anyone else must pass `agentId` and hold EDIT on it (400
 * `AGENT_ID_REQUIRED` / 403 otherwise) and never sees a user reference.
 */

export type RatingScale = "THUMBS" | "FIVE_STAR";
export type ReviewStatus = "NEW" | "REVIEWED";
export type RatingKind = "conversation" | "message";
export type RatingGroupBy = "none" | "day" | "week" | "agentVersion" | "channel";
export type RatingSort = "newest" | "lowest";

/** The stepIndex of a rating of the conversation as a whole. */
export const CONVERSATION_STEP = -1;

/** Mirrors the backend's limits (RatingConfigValidator, ConversationRating). */
export const RATING_LIMITS = {
  maxCommentLength: 4000,
  maxReasonTags: 20,
  maxTagLength: 60,
  maxPromptLength: 500,
  maxReviewNoteLength: 2000,
  defaultCommentLength: 1000,
  defaultWindowHours: 168,
} as const;

/** A bucket with fewer ratings than this is too small to read much into. */
export const LOW_SAMPLE_THRESHOLD = 10;

/** One rating as a maintainer sees it (`IRestConversationRatingAdmin.RatingRow`). */
export interface RatingRow {
  id: string;
  conversationId: string;
  stepIndex: number;
  kind: RatingKind;
  agentId: string | null;
  agentVersion: number | null;
  environment: string | null;
  channel: string | null;
  scale: RatingScale;
  score: number;
  normalizedScore: number;
  tags: string[];
  comment: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  reviewStatus: ReviewStatus | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
  /** Administrators only; null for everyone else. */
  userId: string | null;
  canOpenTranscript: boolean;
}

export interface RatingPage {
  total: number;
  index: number;
  limit: number;
  ratings: RatingRow[];
}

export interface RatingSummary {
  bucket: string;
  count: number;
  avgNormalized: number | null;
  distribution: { thumbsUp: number; thumbsDown: number; stars: Record<string, number> };
  csat: number | null;
  thumbsUpRate: number | null;
  commentCount: number;
  topTags: { tag: string; count: number }[];
}

export interface RatingSummaries {
  groupBy: RatingGroupBy;
  buckets: RatingSummary[];
}

/** The filter every `/ratings` endpoint takes (`RatingQueryParams`). */
export interface RatingQuery {
  agentId?: string;
  agentVersion?: number;
  environment?: string;
  /** ISO instant or date; inclusive. */
  from?: string;
  /** ISO instant or date; exclusive (a date includes that day). */
  to?: string;
  minScore?: number;
  maxScore?: number;
  kind?: RatingKind;
  tag?: string;
  hasComment?: boolean;
  reviewStatus?: ReviewStatus;
  needsAttention?: boolean;
  conversationId?: string;
}

/** The query string of a rating filter, empty values left out. */
export function ratingQueryString(query: RatingQuery, extra: Record<string, string | number | undefined> = {}): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries({ ...query, ...extra })) {
    if (value === undefined || value === null || value === "") continue;
    params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : "";
}

export function listRatings(query: RatingQuery, sort: RatingSort = "newest", index = 0, limit = 20): Promise<RatingPage> {
  return api.get<RatingPage>(`/ratings${ratingQueryString(query, { sort, index, limit })}`);
}

export function summarizeRatings(query: RatingQuery, groupBy: RatingGroupBy = "none"): Promise<RatingSummaries> {
  return api.get<RatingSummaries>(`/ratings/summary${ratingQueryString(query, { groupBy })}`);
}

export async function getAttentionCount(agentId?: string): Promise<number> {
  const result = await api.get<{ count: number }>(`/ratings/attention-count${ratingQueryString({ agentId })}`);
  return result?.count ?? 0;
}

/** Triage one rating. The reviewer is always the caller (server-side). */
export function reviewRating(id: string, review: { reviewStatus?: ReviewStatus; reviewNote?: string }): Promise<RatingRow> {
  return api.patch<RatingRow>(`/ratings/${seg(id)}`, review);
}

/** Mark every NEW rating matching the filter reviewed. */
export async function markRatingsReviewed(query: RatingQuery): Promise<number> {
  const result = await api.post<{ reviewed: number }>(`/ratings/review${ratingQueryString(query)}`);
  return result?.reviewed ?? 0;
}

/**
 * Download the CSV export. Raw `fetch` (blob body and a header to read), so it
 * spreads `api.getAuthHeader()` itself.
 *
 * @returns whether the server's 100,000-row cap cut the export short
 */
export async function downloadRatingsCsv(query: RatingQuery): Promise<{ truncated: boolean }> {
  const res = await fetch(`${api.getBaseUrl()}/ratings/export${ratingQueryString(query)}`, {
    headers: { ...api.getAuthHeader(), Accept: "text/csv" },
  });
  if (!res.ok) {
    throw new Error(`Export failed: ${res.status} ${res.statusText}`);
  }
  const truncated = res.headers.get("X-Ratings-Truncated") === "true";
  const blob = await res.blob();
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = "conversation-ratings.csv";
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(a.href);
  return { truncated };
}

// ── Owner endpoints (the Manager's chat) ──────────────────────────

/** What a client sends when rating. */
export interface RatingInput {
  score: number;
  tags?: string[];
  comment?: string | null;
}

/** A rating as its owner sees it (no triage). */
export interface OwnRating {
  stepIndex: number;
  score: number;
  tags: string[];
  comment: string | null;
}

export interface OwnRatings {
  conversation: OwnRating | null;
  steps: OwnRating[];
  /**
   * The rating controls of the version this conversation runs on — null when
   * it takes no ratings, undefined when the server did not say (an older one).
   */
  controls?: RatingProfile | null;
}

/** How the Manager's chat names itself to the server. */
export const MANAGER_RATING_CHANNEL = "manager";

function ratingBody(input: RatingInput) {
  const comment = input.comment?.trim();
  return {
    score: input.score,
    channel: MANAGER_RATING_CHANNEL,
    ...(input.tags?.length ? { tags: input.tags } : {}),
    ...(comment ? { comment } : {}),
  };
}

export async function readOwnRatings(conversationId: string): Promise<OwnRatings> {
  const result = await api.get<OwnRatings>(`/agents/${seg(conversationId)}/rating`);
  const controls = result && Object.prototype.hasOwnProperty.call(result, "controls") ? (result.controls ?? null) : undefined;
  return { conversation: result?.conversation ?? null, steps: result?.steps ?? [], controls };
}

export function rateConversation(conversationId: string, input: RatingInput): Promise<OwnRating> {
  return api.put<OwnRating>(`/agents/${seg(conversationId)}/rating`, ratingBody(input));
}

export function rateStep(conversationId: string, stepIndex: number, input: RatingInput): Promise<OwnRating> {
  return api.put<OwnRating>(`/agents/${seg(conversationId)}/steps/${stepIndex}/rating`, ratingBody(input));
}

export async function withdrawConversationRating(conversationId: string): Promise<void> {
  await api.delete(`/agents/${seg(conversationId)}/rating`);
}

export async function withdrawStepRating(conversationId: string, stepIndex: number): Promise<void> {
  await api.delete(`/agents/${seg(conversationId)}/steps/${stepIndex}/rating`);
}

// ── Profile ───────────────────────────────────────────────────────

/** The client-relevant part of `ratingConfig` (`GET /agents/{id}/profile` → `rating`). */
export interface RatingProfile {
  scale: RatingScale;
  conversationRating: boolean;
  promptText: string | null;
  askOnEnd: boolean;
  askAfterTurns: number;
  messageRating: boolean;
  comment: boolean;
  commentMaxLength: number;
  commentAskBelowScore: number;
  reasonTags: string[];
  ratingWindowHours: number;
}

/** Normalized 0..1 score (thumbs 0/1; stars (s−1)/4). */
export function normalizeScore(scale: RatingScale, score: number): number {
  return scale === "FIVE_STAR" ? (score - 1) / 4 : score;
}
