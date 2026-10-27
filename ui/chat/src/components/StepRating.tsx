/* ──────────────────────────────────────────────
   StepRating — thumbs under one agent reply
   One tap rates; tapping the pressed thumb again withdraws. Thumbs down opens
   a small inline form for a reason and a comment when the agent accepts them —
   optional, the rating already counts. Shown on hover/focus on desktop (and
   always once pressed), always on touch screens (CSS).
   ────────────────────────────────────────────── */

import { useEffect, useId, useRef, useState } from "react";
import { ThumbsDown, ThumbsUp } from "lucide-react";
import { commentOffered, type RatingInput, type RatingProfile, type StoredRating } from "@/api/rating-api";
import { t } from "@/i18n";

interface StepRatingProps {
  stepIndex: number;
  /** The stored rating of this step, or undefined. */
  rating?: StoredRating;
  profile: RatingProfile;
  onRate: (stepIndex: number, input: RatingInput | null) => Promise<boolean>;
}

export function StepRating({ stepIndex, rating, profile, onRate }: StepRatingProps) {
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [tags, setTags] = useState<string[]>([]);
  const [comment, setComment] = useState("");
  const formId = useId();
  const firstFieldRef = useRef<HTMLButtonElement | HTMLTextAreaElement | null>(null);

  const score = rating?.score;
  const hasDetails = profile.reasonTags.length > 0 || commentOffered(profile, "THUMBS", 0);

  useEffect(() => {
    if (detailsOpen) firstFieldRef.current?.focus();
  }, [detailsOpen]);

  const rate = (value: 0 | 1) => {
    if (score === value) {
      setDetailsOpen(false);
      void onRate(stepIndex, null);
      return;
    }
    void onRate(stepIndex, { score: value });
    if (value === 0 && hasDetails) {
      setTags([]);
      setComment("");
      setDetailsOpen(true);
    } else {
      setDetailsOpen(false);
    }
  };

  const send = async () => {
    setDetailsOpen(false);
    await onRate(stepIndex, { score: 0, tags, comment });
  };

  const toggleTag = (tag: string) =>
    setTags((current) => (current.includes(tag) ? current.filter((x) => x !== tag) : [...current, tag]));

  return (
    <div
      className={`step-rating${score !== undefined ? " step-rating--rated" : ""}`}
      role="group"
      aria-label={t("rating.reply.group")}
      data-testid={`step-rating-${stepIndex}`}
    >
      <div className="step-rating__buttons">
        <button
          type="button"
          className="step-rating__btn"
          aria-pressed={score === 1}
          aria-label={t("rating.reply.up")}
          title={t("rating.reply.up")}
          onClick={() => rate(1)}
          data-testid="step-rating-up"
        >
          <ThumbsUp size="1em" aria-hidden="true" />
        </button>
        <button
          type="button"
          className="step-rating__btn"
          aria-pressed={score === 0}
          aria-label={t("rating.reply.down")}
          title={t("rating.reply.down")}
          onClick={() => rate(0)}
          aria-expanded={hasDetails ? detailsOpen : undefined}
          aria-controls={hasDetails && detailsOpen ? formId : undefined}
          data-testid="step-rating-down"
        >
          <ThumbsDown size="1em" aria-hidden="true" />
        </button>
      </div>

      {detailsOpen && (
        <div
          id={formId}
          className="step-rating__details"
          role="group"
          aria-label={t("rating.reply.detailsLabel")}
          data-testid="step-rating-details"
          onKeyDown={(e) => {
            if (e.key === "Escape") {
              e.stopPropagation();
              setDetailsOpen(false);
            }
          }}
        >
          <p className="step-rating__question">{t("rating.reply.whatWentWrong")}</p>
          {profile.reasonTags.length > 0 && (
            <div className="rating-tags">
              {profile.reasonTags.map((tag, i) => (
                <button
                  key={tag}
                  type="button"
                  className="rating-tags__chip"
                  aria-pressed={tags.includes(tag)}
                  onClick={() => toggleTag(tag)}
                  ref={i === 0 ? (el) => { firstFieldRef.current = el; } : undefined}
                >
                  {tag}
                </button>
              ))}
            </div>
          )}
          {commentOffered(profile, "THUMBS", 0) && (
            <textarea
              className="rating-comment"
              aria-label={t("rating.comment.label")}
              placeholder={t("rating.comment.placeholder")}
              maxLength={profile.commentMaxLength}
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              rows={2}
              ref={profile.reasonTags.length === 0 ? (el) => { firstFieldRef.current = el; } : undefined}
              data-testid="step-rating-comment"
            />
          )}
          <div className="step-rating__actions">
            <button type="button" className="rating-btn rating-btn--primary" onClick={send} data-testid="step-rating-send">
              {t("rating.send")}
            </button>
            <button type="button" className="rating-btn" onClick={() => setDetailsOpen(false)} data-testid="step-rating-skip">
              {t("rating.skip")}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
