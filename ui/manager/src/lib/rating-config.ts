import type { AgentRatingConfig } from "@/lib/api/agents";
import { RATING_LIMITS } from "@/lib/api/ratings";

/** What is wrong with a field — the section renders the message. */
export type RatingConfigError =
  | "window"
  | "turns"
  | "prompt"
  | "maxLength"
  | "askBelow"
  | "tagCount"
  | "tagLength"
  | "tagDuplicate";

/**
 * Client-side mirror of the backend's `RatingConfigValidator`: the same limits,
 * so an agent is never sent with values the save would refuse. Keyed by field.
 */
export type RatingConfigErrors = Partial<
  Record<"ratingWindowHours" | "askAfterTurns" | "promptText" | "maxLength" | "askBelowScore" | "reasonTags", RatingConfigError>
>;

export function ratingConfigErrors(config: AgentRatingConfig | undefined): RatingConfigErrors {
  const errors: RatingConfigErrors = {};
  if (!config) return errors;
  const window = config.ratingWindowHours;
  if (window !== undefined && (!Number.isInteger(window) || window < 0)) {
    errors.ratingWindowHours = "window";
  }
  const turns = config.conversationRating?.askAfterTurns;
  if (turns !== undefined && (!Number.isInteger(turns) || turns < 0)) {
    errors.askAfterTurns = "turns";
  }
  const prompt = config.conversationRating?.promptText;
  if (prompt && prompt.length > RATING_LIMITS.maxPromptLength) {
    errors.promptText = "prompt";
  }
  const maxLength = config.comment?.maxLength;
  if (maxLength !== undefined && (!Number.isInteger(maxLength) || maxLength < 1 || maxLength > RATING_LIMITS.maxCommentLength)) {
    errors.maxLength = "maxLength";
  }
  const askBelow = config.comment?.askBelowScore;
  if (askBelow !== undefined && (Number.isNaN(askBelow) || askBelow < 0 || askBelow > 1)) {
    errors.askBelowScore = "askBelow";
  }
  const tags = config.reasonTags ?? [];
  if (tags.length > RATING_LIMITS.maxReasonTags) {
    errors.reasonTags = "tagCount";
  } else if (tags.some((tag) => !tag.trim() || tag.length > RATING_LIMITS.maxTagLength)) {
    errors.reasonTags = "tagLength";
  } else if (new Set(tags).size !== tags.length) {
    errors.reasonTags = "tagDuplicate";
  }
  return errors;
}

/** Whether ratings are switched on (the backend's default for `enabled` is false). */
export function ratingsEnabled(config: AgentRatingConfig | undefined): boolean {
  return config?.enabled === true;
}
