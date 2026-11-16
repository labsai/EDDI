/* ──────────────────────────────────────────────
   RatingCard — "How did we do?"
   One tap on a score rates the conversation; reason tags and a comment are
   optional and appear only after the score. Then a thanks state that says
   where the feedback goes, with the rating still editable. Sits in the
   transcript like PausedCard and never blocks the composer.
   ────────────────────────────────────────────── */

import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { Star, ThumbsDown, ThumbsUp, X } from "lucide-react";
import { commentOffered, type RatingInput, type RatingProfile, type StoredRating } from "@eddi/core";
import { useConversationText } from "../react/hooks";

interface RatingCardProps {
  profile: RatingProfile;
  /** The stored conversation rating, or null. */
  current: StoredRating | null;
  onSubmit: (input: RatingInput) => Promise<boolean>;
  onWithdraw: () => Promise<boolean>;
  onClose: () => void;
}

type Phase = "rate" | "details" | "thanks";

const STARS = [1, 2, 3, 4, 5] as const;

export function RatingCard({ profile, current, onSubmit, onWithdraw, onClose }: RatingCardProps) {
  const t = useConversationText();
  const [phase, setPhase] = useState<Phase>(current ? "thanks" : "rate");
  const [score, setScore] = useState<number | null>(current?.score ?? null);
  const [tags, setTags] = useState<string[]>(current?.tags ?? []);
  const [comment, setComment] = useState(current?.comment ?? "");
  const [busy, setBusy] = useState(false);
  // Set by the person's own interaction. A rating that arrives later (the restore
  // read finished after the card opened) fills the card only while untouched.
  const touched = useRef(false);
  const starRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const headingRef = useRef<HTMLParagraphElement | null>(null);

  const scale = profile.scale;
  const showComment = score !== null && commentOffered(profile, scale, score);
  const hasDetails = profile.reasonTags.length > 0 || showComment;

  // Move focus to the thanks line, so a keyboard or screen-reader user learns
  // the rating went through.
  useEffect(() => {
    if (phase === "thanks") headingRef.current?.focus();
  }, [phase]);

  useEffect(() => {
    if (!current || touched.current) return;
    setScore(current.score);
    setTags(current.tags);
    setComment(current.comment ?? "");
    setPhase("thanks");
  }, [current]);

  const pick = async (value: number) => {
    touched.current = true;
    setBusy(true);
    setScore(value);
    const ok = await onSubmit({ score: value, tags, comment });
    setBusy(false);
    if (!ok) {
      setScore(current?.score ?? null);
      return;
    }
    const detailsFor = profile.reasonTags.length > 0 || commentOffered(profile, scale, value);
    setPhase(detailsFor ? "details" : "thanks");
  };

  const send = async () => {
    if (score === null) return;
    touched.current = true;
    setBusy(true);
    const ok = await onSubmit({ score, tags, comment });
    setBusy(false);
    if (ok) setPhase("thanks");
  };

  const withdraw = async () => {
    touched.current = true;
    setBusy(true);
    const ok = await onWithdraw();
    setBusy(false);
    if (ok) {
      setScore(null);
      setTags([]);
      setComment("");
      setPhase("rate");
    }
  };

  const toggleTag = (tag: string) => {
    touched.current = true;
    setTags((list) => (list.includes(tag) ? list.filter((x) => x !== tag) : [...list, tag]));
  };

  // Arrow keys move between the stars (roving focus); Enter or Space rates.
  const onStarKey = (e: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const step = e.key === "ArrowRight" || e.key === "ArrowUp" ? 1 : e.key === "ArrowLeft" || e.key === "ArrowDown" ? -1 : 0;
    if (!step) return;
    e.preventDefault();
    const next = (index + step + STARS.length) % STARS.length;
    starRefs.current[next]?.focus();
  };

  const question = profile.promptText ?? t("conversation.rating.card.question");

  return (
    <section className="rating-card" aria-label={t("conversation.rating.card.label")} data-testid="rating-card">
      <button
        type="button"
        className="rating-card__close"
        onClick={onClose}
        aria-label={t("conversation.rating.card.close")}
        title={t("conversation.rating.card.close")}
        data-testid="rating-card-close"
      >
        <X size="1em" aria-hidden="true" />
      </button>

      {phase === "thanks" ? (
        <div data-testid="rating-card-thanks">
          <p className="rating-card__title" tabIndex={-1} ref={headingRef}>
            {t("conversation.rating.card.thanks")}
          </p>
          <p className="rating-card__note">{t("conversation.rating.card.sharedWithTeam")}</p>
          <div className="rating-card__actions">
            <button type="button" className="rating-btn" onClick={() => setPhase("rate")} disabled={busy} data-testid="rating-card-change">
              {t("conversation.rating.card.change")}
            </button>
            <button type="button" className="rating-btn rating-btn--quiet" onClick={withdraw} disabled={busy} data-testid="rating-card-withdraw">
              {t("conversation.rating.card.withdraw")}
            </button>
          </div>
        </div>
      ) : (
        <>
          <p className="rating-card__title" id="rating-card-question">
            {question}
          </p>
          {scale === "FIVE_STAR" ? (
            <div className="rating-stars" role="radiogroup" aria-labelledby="rating-card-question" data-testid="rating-stars">
              {STARS.map((value, i) => {
                const selected = score !== null && value <= score;
                const focusable = score === null ? i === 0 : value === score;
                return (
                  <button
                    key={value}
                    type="button"
                    role="radio"
                    aria-checked={score === value}
                    aria-label={t("conversation.rating.card.stars", { n: value })}
                    tabIndex={focusable ? 0 : -1}
                    className={`rating-stars__star${selected ? " rating-stars__star--on" : ""}`}
                    onClick={() => void pick(value)}
                    onKeyDown={(e) => onStarKey(e, i)}
                    disabled={busy}
                    ref={(el) => {
                      starRefs.current[i] = el;
                    }}
                    data-testid={`rating-star-${value}`}
                  >
                    <Star size="1em" aria-hidden="true" />
                  </button>
                );
              })}
            </div>
          ) : (
            <div className="rating-thumbs" role="group" aria-labelledby="rating-card-question" data-testid="rating-thumbs">
              <button
                type="button"
                className="rating-thumbs__btn"
                aria-pressed={score === 1}
                aria-label={t("conversation.rating.card.up")}
                onClick={() => void pick(1)}
                disabled={busy}
                data-testid="rating-thumb-up"
              >
                <ThumbsUp size="1em" aria-hidden="true" />
              </button>
              <button
                type="button"
                className="rating-thumbs__btn"
                aria-pressed={score === 0}
                aria-label={t("conversation.rating.card.down")}
                onClick={() => void pick(0)}
                disabled={busy}
                data-testid="rating-thumb-down"
              >
                <ThumbsDown size="1em" aria-hidden="true" />
              </button>
            </div>
          )}

          {phase === "details" && hasDetails && (
            <div className="rating-card__details" data-testid="rating-card-details">
              {profile.reasonTags.length > 0 && (
                <div className="rating-tags" role="group" aria-label={t("conversation.rating.tags.label")}>
                  {profile.reasonTags.map((tag) => (
                    <button
                      key={tag}
                      type="button"
                      className="rating-tags__chip"
                      aria-pressed={tags.includes(tag)}
                      onClick={() => toggleTag(tag)}
                    >
                      {tag}
                    </button>
                  ))}
                </div>
              )}
              {showComment && (
                <textarea
                  className="rating-comment"
                  aria-label={t("conversation.rating.comment.label")}
                  placeholder={t("conversation.rating.comment.placeholder")}
                  maxLength={profile.commentMaxLength}
                  value={comment}
                  onChange={(e) => {
                    touched.current = true;
                    setComment(e.target.value);
                  }}
                  rows={3}
                  data-testid="rating-card-comment"
                />
              )}
              <div className="rating-card__actions">
                <button type="button" className="rating-btn rating-btn--primary" onClick={send} disabled={busy} data-testid="rating-card-send">
                  {t("conversation.rating.send")}
                </button>
                <button type="button" className="rating-btn" onClick={() => setPhase("thanks")} disabled={busy} data-testid="rating-card-skip">
                  {t("conversation.rating.skip")}
                </button>
              </div>
            </div>
          )}

          <p className="rating-card__note">{t("conversation.rating.card.disclosure")}</p>
        </>
      )}
    </section>
  );
}
