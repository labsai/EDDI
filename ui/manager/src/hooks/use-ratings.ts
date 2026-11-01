import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ratingKeys } from "@/lib/query-keys";
import { isApiError } from "@/lib/api-client";
import {
  getAttentionCount,
  listRatings,
  markRatingsReviewed,
  reviewRating,
  summarizeRatings,
  type RatingGroupBy,
  type RatingQuery,
  type RatingSort,
  type ReviewStatus,
} from "@/lib/api/ratings";

/** How often the attention count refreshes — the same cadence as the approvals badge. */
export const ATTENTION_POLL_MS = 30_000;

/**
 * Whether an error is the backend asking for an agent (400 `AGENT_ID_REQUIRED`):
 * a non-administrator listed every agent's ratings. The page answers with a
 * prompt to pick one, not an error box.
 */
export function isAgentRequired(error: unknown): boolean {
  return isApiError(error) && error.status === 400 && (error as { code?: string }).code === "AGENT_ID_REQUIRED";
}

/** Whether a triage failed because the person re-rated meanwhile (409 `RATING_CHANGED`). */
export function isRatingChanged(error: unknown): boolean {
  return isApiError(error) && error.status === 409;
}

/** A page of ratings. `enabled` is false while the scope is not settled (a non-admin without an agent). */
export function useRatingList(query: RatingQuery, sort: RatingSort, index: number, limit: number, enabled = true) {
  return useQuery({
    queryKey: ratingKeys.list(query, sort, index, limit),
    queryFn: () => listRatings(query, sort, index, limit),
    enabled,
    placeholderData: keepPreviousData,
    retry: false,
  });
}

export function useRatingSummary(query: RatingQuery, groupBy: RatingGroupBy, enabled = true) {
  return useQuery({
    queryKey: ratingKeys.summary(query, groupBy),
    queryFn: () => summarizeRatings(query, groupBy),
    enabled,
    retry: false,
  });
}

/**
 * NEW ratings that are low or carry a comment. Shared by the sidebar badge and
 * the dashboard tile — one entry, one poll.
 */
export function useAttentionCount(agentId?: string, enabled = true) {
  return useQuery({
    queryKey: ratingKeys.attention(agentId),
    queryFn: () => getAttentionCount(agentId),
    enabled,
    refetchInterval: ATTENTION_POLL_MS,
    retry: false,
  });
}

/** The ratings of one conversation (the conversation view). */
export function useConversationRatings(conversationId: string | undefined, agentId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: ratingKeys.conversation(conversationId, agentId),
    queryFn: () => listRatings({ conversationId, agentId }, "newest", 0, 100),
    enabled: enabled && !!conversationId,
    retry: false,
    staleTime: 30_000,
  });
}

/** Triage one rating. Every outcome — 409 included — refreshes the rating queries. */
export function useReviewRating() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reviewStatus, reviewNote }: { id: string; reviewStatus?: ReviewStatus; reviewNote?: string }) =>
      reviewRating(id, { reviewStatus, reviewNote }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ratingKeys.all }),
  });
}

/** Mark every NEW rating matching a filter reviewed. */
export function useMarkRatingsReviewed() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (query: RatingQuery) => markRatingsReviewed(query),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ratingKeys.all }),
  });
}
