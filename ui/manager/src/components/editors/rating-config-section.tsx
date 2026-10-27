import { memo, useEffect, useId, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Star } from "lucide-react";
import type { Agent, AgentRatingConfig } from "@/lib/api/agents";
import type { AgentEdit } from "@/lib/agent-draft";
import { ChipInput } from "@/components/shared/chip-input";
import { RATING_LIMITS } from "@/lib/api/ratings";
import { ratingConfigErrors, type RatingConfigError } from "@/lib/rating-config";
import { EditorSection } from "./editor-section";

const fieldClass =
  "w-full rounded-md border border-input bg-background px-2 py-1.5 text-xs focus:outline-none focus-visible:ring-2 focus-visible:ring-ring aria-[invalid=true]:border-destructive";

/**
 * `ratingConfig` — whether and how the people chatting are asked for a rating.
 * Every field the backend knows, with its save-time limits mirrored here
 * (`ratingConfigErrors`) so the page refuses to save what the server would
 * reject. Off unless switched on; absent fields take the backend defaults.
 */
export const RatingConfigSection = memo(function RatingConfigSection({
  agent,
  onChange,
  disabled = false,
  modified = false,
  onValidityChange,
}: {
  agent: Agent;
  onChange: AgentEdit;
  disabled?: boolean;
  modified?: boolean;
  /** Told whether the section holds a value the backend would refuse. */
  onValidityChange?: (invalid: boolean) => void;
}) {
  const { t } = useTranslation();
  const id = useId();
  const config = useMemo<AgentRatingConfig>(() => agent.ratingConfig ?? {}, [agent.ratingConfig]);
  const errors = useMemo(() => ratingConfigErrors(agent.ratingConfig), [agent.ratingConfig]);
  const invalid = Object.keys(errors).length > 0;
  const [pendingTag, setPendingTag] = useState("");

  useEffect(() => {
    onValidityChange?.(invalid);
    return () => onValidityChange?.(false);
  }, [invalid, onValidityChange]);

  const enabled = config.enabled === true;
  const conversation = config.conversationRating ?? {};
  const comment = config.comment ?? {};
  // A field holding an invalid value stays visible even after its switch is
  // turned off: the value is still saved and still blocks the save, so the
  // editor must be able to see and correct it.
  const showSettings = enabled || invalid;
  const showConversation = conversation.enabled !== false || !!errors.promptText || !!errors.askAfterTurns;
  const showComment = comment.enabled === true || !!errors.maxLength || !!errors.askBelowScore;

  function patch(next: AgentRatingConfig) {
    onChange({ ...agent, ratingConfig: next });
  }
  /** A number field: empty removes it (backend default), anything else is kept as typed so it can be flagged. */
  const num = (value: string): number | undefined => (value.trim() === "" ? undefined : Number(value));

  const message = (error: RatingConfigError | undefined): string | null => {
    switch (error) {
      case "window":
        return t("agentDetail.rating.errWindow", "0 (no limit) or a whole number of hours.");
      case "turns":
        return t("agentDetail.rating.errTurns", "0 (never) or a whole number of replies.");
      case "prompt":
        return t("agentDetail.rating.errPrompt", "At most 500 characters.");
      case "maxLength":
        return t("agentDetail.rating.errMaxLength", "Between 1 and 4000 characters.");
      case "askBelow":
        return t("agentDetail.rating.errAskBelow", "Between 0 and 1 (a normalized score).");
      case "tagCount":
        return t("agentDetail.rating.errTagCount", "At most 20 reasons.");
      case "tagLength":
        return t("agentDetail.rating.errTagLength", "Each reason must be 1 to 60 characters.");
      case "tagDuplicate":
        return t("agentDetail.rating.errTagDuplicate", "Each reason may appear only once.");
      default:
        return null;
    }
  };

  const errorText = (field: keyof typeof errors) => {
    const text = message(errors[field]);
    return text ? (
      <p id={`${id}-${field}-error`} className="mt-1 text-[10px] text-destructive" data-testid={`rating-error-${field}`}>
        {text}
      </p>
    ) : null;
  };

  return (
    <EditorSection
      label={t("agentDetail.rating.title", "Ratings")}
      icon={Star}
      accent="text-amber-500"
      variant="card"
      modified={modified}
      defaultOpen={enabled}
    >
      <div className="space-y-4" data-testid="rating-config-section">
        <p className="text-[10px] leading-relaxed text-muted-foreground">
          {t(
            "agentDetail.rating.description",
            "Let the people chatting rate the conversation and, if you like, single replies. You see the results on the Feedback page. Covers conversations on the version this change creates and later ones.",
          )}
        </p>

        <label className="inline-flex items-center gap-2 text-xs font-medium text-foreground">
          <input
            type="checkbox"
            checked={enabled}
            onChange={() => patch({ ...config, enabled: !enabled })}
            disabled={disabled}
            className="h-3.5 w-3.5 rounded border-input accent-primary"
            data-testid="rating-enabled"
          />
          {t("agentDetail.rating.enable", "Ask people to rate their conversations")}
        </label>

        {showSettings && (
          <div className="space-y-4 ps-5">
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="block text-[10px] text-muted-foreground">
                {t("agentDetail.rating.scale", "Scale")}
                <select
                  className={fieldClass}
                  value={config.scale ?? "THUMBS"}
                  onChange={(e) => patch({ ...config, scale: e.target.value as AgentRatingConfig["scale"] })}
                  disabled={disabled}
                  data-testid="rating-scale"
                >
                  <option value="THUMBS">{t("agentDetail.rating.scaleThumbs", "Thumbs up / down")}</option>
                  <option value="FIVE_STAR">{t("agentDetail.rating.scaleStars", "One to five stars")}</option>
                </select>
              </label>
              <label className="block text-[10px] text-muted-foreground">
                {t("agentDetail.rating.window", "Accept ratings for (hours after the last message, 0 = always)")}
                <input
                  type="number"
                  min={0}
                  step={1}
                  className={fieldClass}
                  value={config.ratingWindowHours ?? ""}
                  placeholder={String(RATING_LIMITS.defaultWindowHours)}
                  onChange={(e) => patch({ ...config, ratingWindowHours: num(e.target.value) })}
                  disabled={disabled}
                  aria-invalid={!!errors.ratingWindowHours}
                  aria-describedby={errors.ratingWindowHours ? `${id}-ratingWindowHours-error` : undefined}
                  data-testid="rating-window"
                />
                {errorText("ratingWindowHours")}
              </label>
            </div>

            <fieldset className="space-y-2 rounded-lg border border-border p-3">
              <legend className="px-1 text-xs font-medium">{t("agentDetail.rating.conversation", "Rating the whole conversation")}</legend>
              <label className="inline-flex items-center gap-2 text-xs">
                <input
                  type="checkbox"
                  checked={conversation.enabled !== false}
                  onChange={() => patch({ ...config, conversationRating: { ...conversation, enabled: conversation.enabled === false } })}
                  disabled={disabled}
                  className="h-3.5 w-3.5 accent-primary"
                  data-testid="rating-conversation-enabled"
                />
                {t("agentDetail.rating.conversationEnable", "Offer a rating card for the conversation")}
              </label>
              {showConversation && (
                <div className="space-y-2">
                  <label className="block text-[10px] text-muted-foreground">
                    {t("agentDetail.rating.prompt", "Question on the card (empty for the standard wording)")}
                    <input
                      className={fieldClass}
                      value={conversation.promptText ?? ""}
                      maxLength={RATING_LIMITS.maxPromptLength + 50}
                      placeholder={t("agentDetail.rating.promptPlaceholder", "How did we do?")}
                      onChange={(e) =>
                        patch({ ...config, conversationRating: { ...conversation, promptText: e.target.value.trim() ? e.target.value : null } })
                      }
                      disabled={disabled}
                      aria-invalid={!!errors.promptText}
                      data-testid="rating-prompt"
                    />
                    {errorText("promptText")}
                  </label>
                  <label className="inline-flex items-center gap-2 text-xs">
                    <input
                      type="checkbox"
                      checked={conversation.askOnEnd !== false}
                      onChange={() => patch({ ...config, conversationRating: { ...conversation, askOnEnd: conversation.askOnEnd === false } })}
                      disabled={disabled}
                      className="h-3.5 w-3.5 accent-primary"
                      data-testid="rating-ask-on-end"
                    />
                    {t("agentDetail.rating.askOnEnd", "Ask when the conversation ends")}
                  </label>
                  <label className="block text-[10px] text-muted-foreground">
                    {t("agentDetail.rating.askAfterTurns", "Also ask after this many agent replies (0 = never)")}
                    <input
                      type="number"
                      min={0}
                      step={1}
                      className={fieldClass}
                      value={conversation.askAfterTurns ?? ""}
                      placeholder="0"
                      onChange={(e) => patch({ ...config, conversationRating: { ...conversation, askAfterTurns: num(e.target.value) } })}
                      disabled={disabled}
                      aria-invalid={!!errors.askAfterTurns}
                      data-testid="rating-ask-after-turns"
                    />
                    {errorText("askAfterTurns")}
                  </label>
                </div>
              )}
            </fieldset>

            <label className="inline-flex items-center gap-2 text-xs">
              <input
                type="checkbox"
                checked={config.messageRating?.enabled === true}
                onChange={() => patch({ ...config, messageRating: { enabled: config.messageRating?.enabled !== true } })}
                disabled={disabled}
                className="h-3.5 w-3.5 accent-primary"
                data-testid="rating-message-enabled"
              />
              {t("agentDetail.rating.messageEnable", "Thumbs under each agent reply")}
            </label>

            <fieldset className="space-y-2 rounded-lg border border-border p-3">
              <legend className="px-1 text-xs font-medium">{t("agentDetail.rating.comment", "Comment")}</legend>
              <label className="inline-flex items-center gap-2 text-xs">
                <input
                  type="checkbox"
                  checked={comment.enabled === true}
                  onChange={() => patch({ ...config, comment: { ...comment, enabled: comment.enabled !== true } })}
                  disabled={disabled}
                  className="h-3.5 w-3.5 accent-primary"
                  data-testid="rating-comment-enabled"
                />
                {t("agentDetail.rating.commentEnable", "Accept a free-text comment")}
              </label>
              {showComment && (
                <div className="grid gap-3 sm:grid-cols-2">
                  <label className="block text-[10px] text-muted-foreground">
                    {t("agentDetail.rating.maxLength", "Longest comment (characters, at most 4000)")}
                    <input
                      type="number"
                      min={1}
                      max={RATING_LIMITS.maxCommentLength}
                      className={fieldClass}
                      value={comment.maxLength ?? ""}
                      placeholder={String(RATING_LIMITS.defaultCommentLength)}
                      onChange={(e) => patch({ ...config, comment: { ...comment, maxLength: num(e.target.value) } })}
                      disabled={disabled}
                      aria-invalid={!!errors.maxLength}
                      data-testid="rating-comment-max"
                    />
                    {errorText("maxLength")}
                  </label>
                  <label className="block text-[10px] text-muted-foreground">
                    {t("agentDetail.rating.askBelow", "Open the comment box only below this score (0–1, 0 = always)")}
                    <input
                      type="number"
                      min={0}
                      max={1}
                      step={0.05}
                      className={fieldClass}
                      value={comment.askBelowScore ?? ""}
                      placeholder="0"
                      onChange={(e) => patch({ ...config, comment: { ...comment, askBelowScore: num(e.target.value) } })}
                      disabled={disabled}
                      aria-invalid={!!errors.askBelowScore}
                      data-testid="rating-comment-ask-below"
                    />
                    {errorText("askBelowScore")}
                  </label>
                </div>
              )}
            </fieldset>

            <div>
              <span className="mb-1 block text-[10px] text-muted-foreground">
                {t("agentDetail.rating.tags", "Reasons people can pick (up to 20, each up to 60 characters)")}
              </span>
              <ChipInput
                values={config.reasonTags ?? []}
                onChange={(reasonTags) => patch({ ...config, reasonTags })}
                pending={pendingTag}
                onPendingChange={setPendingTag}
                inputLabel={t("agentDetail.rating.tagInput", "Add a reason")}
                placeholder={t("agentDetail.rating.tagPlaceholder", "e.g. Wrong answer")}
                readOnly={disabled}
                invalid={!!errors.reasonTags}
                errorId={errors.reasonTags ? `${id}-reasonTags-error` : undefined}
                testId="rating-tags"
              />
              {errorText("reasonTags")}
            </div>

            <p className="text-[10px] text-muted-foreground">
              {t(
                "agentDetail.rating.disclosure",
                "The chat tells people that their rating and comment go to the team running this assistant. Comments are shown to everyone who can edit the agent; only administrators see who rated.",
              )}
            </p>
          </div>
        )}
      </div>
    </EditorSection>
  );
});
