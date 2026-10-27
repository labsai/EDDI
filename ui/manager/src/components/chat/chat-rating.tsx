import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Star, ThumbsDown, ThumbsUp, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { normalizeScore, type OwnRating, type RatingInput, type RatingProfile } from "@/lib/api/ratings";

const toggleClass = (pressed: boolean) =>
  cn(
    "inline-flex h-7 w-7 items-center justify-center rounded-md transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
    pressed ? "bg-primary/15 text-primary" : "text-muted-foreground",
  );

function Reasons({ profile, tags, onToggle }: { profile: RatingProfile; tags: string[]; onToggle: (tag: string) => void }) {
  if (profile.reasonTags.length === 0) return null;
  return (
    <div className="flex flex-wrap gap-1.5">
      {profile.reasonTags.map((tag) => (
        <button
          key={tag}
          type="button"
          aria-pressed={tags.includes(tag)}
          onClick={() => onToggle(tag)}
          className={cn(
            "rounded-full border px-2.5 py-0.5 text-xs",
            tags.includes(tag) ? "border-primary bg-primary/10" : "border-border hover:border-primary/50",
          )}
        >
          {tag}
        </button>
      ))}
    </div>
  );
}

/**
 * Thumbs under an agent reply in the Manager chat — one tap rates, tapping the
 * pressed thumb withdraws, thumbs down offers reasons and a comment.
 */
export function ChatStepRating({
  stepIndex,
  rating,
  profile,
  onRate,
}: {
  stepIndex: number;
  rating?: OwnRating;
  profile: RatingProfile;
  onRate: (stepIndex: number, input: RatingInput | null) => Promise<boolean>;
}) {
  const { t } = useTranslation();
  const [details, setDetails] = useState(false);
  const [tags, setTags] = useState<string[]>([]);
  const [comment, setComment] = useState("");
  const score = rating?.score;
  const hasDetails = profile.reasonTags.length > 0 || profile.comment;

  const rate = (value: 0 | 1) => {
    if (score === value) {
      setDetails(false);
      void onRate(stepIndex, null);
      return;
    }
    void onRate(stepIndex, { score: value });
    setDetails(value === 0 && hasDetails);
  };

  return (
    <div className="flex flex-col gap-1.5" role="group" aria-label={t("chat.rating.reply", "Rate this reply")} data-testid={`chat-step-rating-${stepIndex}`}>
      <div className="flex items-center gap-0.5">
        <button type="button" className={toggleClass(score === 1)} aria-pressed={score === 1} aria-label={t("chat.rating.up", "Good reply")} onClick={() => rate(1)} data-testid="chat-rating-up">
          <ThumbsUp className="h-3.5 w-3.5" aria-hidden="true" />
        </button>
        <button type="button" className={toggleClass(score === 0)} aria-pressed={score === 0} aria-label={t("chat.rating.down", "Bad reply")} onClick={() => rate(0)} data-testid="chat-rating-down">
          <ThumbsDown className="h-3.5 w-3.5" aria-hidden="true" />
        </button>
      </div>
      {details && (
        <div className="space-y-2 rounded-lg border border-border bg-card p-2" data-testid="chat-rating-details">
          <Reasons profile={profile} tags={tags} onToggle={(tag) => setTags((l) => (l.includes(tag) ? l.filter((x) => x !== tag) : [...l, tag]))} />
          {profile.comment && (
            <textarea
              className="w-full rounded-md border border-input bg-background px-2 py-1 text-xs"
              rows={2}
              maxLength={profile.commentMaxLength}
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              placeholder={t("chat.rating.commentPlaceholder", "Anything else? (optional)")}
              aria-label={t("chat.rating.comment", "Comment")}
            />
          )}
          <div className="flex gap-2">
            <Button
              size="sm"
              onClick={() => {
                setDetails(false);
                void onRate(stepIndex, { score: 0, tags, comment });
              }}
              data-testid="chat-rating-send"
            >
              {t("chat.rating.send", "Send")}
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setDetails(false)}>
              {t("chat.rating.skip", "Skip")}
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * "How did we do?" — the conversation rating card of the Manager chat. One tap
 * on a score rates; reasons and a comment follow; then a thanks state.
 */
export function ChatRatingCard({
  profile,
  current,
  onRate,
  onClose,
}: {
  profile: RatingProfile;
  current: OwnRating | null;
  onRate: (input: RatingInput | null) => Promise<boolean>;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [phase, setPhase] = useState<"rate" | "details" | "thanks">(current ? "thanks" : "rate");
  const [score, setScore] = useState<number | null>(current?.score ?? null);
  const [tags, setTags] = useState<string[]>(current?.tags ?? []);
  const [comment, setComment] = useState(current?.comment ?? "");
  const commentOffered = (value: number) =>
    profile.comment && (profile.commentAskBelowScore <= 0 || normalizeScore(profile.scale, value) < profile.commentAskBelowScore);

  const pick = async (value: number) => {
    setScore(value);
    if (!(await onRate({ score: value, tags, comment }))) {
      setScore(current?.score ?? null);
      return;
    }
    setPhase(profile.reasonTags.length > 0 || commentOffered(value) ? "details" : "thanks");
  };

  return (
    <section className="relative mx-4 my-3 rounded-xl border border-primary/30 bg-primary/5 p-4 pe-10" aria-label={t("chat.rating.card", "Rate this conversation")} data-testid="chat-rating-card">
      <button
        type="button"
        onClick={onClose}
        className="absolute end-2 top-2 inline-flex h-7 w-7 items-center justify-center rounded-md text-muted-foreground hover:bg-muted"
        aria-label={t("common.close", "Close")}
        data-testid="chat-rating-card-close"
      >
        <X className="h-4 w-4" aria-hidden="true" />
      </button>
      {phase === "thanks" ? (
        <div className="space-y-2" data-testid="chat-rating-thanks">
          <p className="text-sm font-medium">{t("chat.rating.thanks", "Thanks — your feedback goes to the team running this assistant.")}</p>
          <div className="flex gap-2">
            <Button size="sm" variant="outline" onClick={() => setPhase("rate")}>
              {t("chat.rating.change", "Change rating")}
            </Button>
            <Button
              size="sm"
              variant="ghost"
              onClick={async () => {
                if (await onRate(null)) {
                  setScore(null);
                  setPhase("rate");
                }
              }}
            >
              {t("chat.rating.remove", "Remove rating")}
            </Button>
          </div>
        </div>
      ) : (
        <div className="space-y-3">
          <p className="text-sm font-medium" id="chat-rating-question">
            {profile.promptText ?? t("chat.rating.question", "How did we do?")}
          </p>
          {profile.scale === "FIVE_STAR" ? (
            <div role="radiogroup" aria-labelledby="chat-rating-question" className="flex gap-1">
              {[1, 2, 3, 4, 5].map((value) => (
                <button
                  key={value}
                  type="button"
                  role="radio"
                  aria-checked={score === value}
                  aria-label={t("feedback.starsOf", { n: value, defaultValue: "{{n}} of 5 stars" })}
                  onClick={() => void pick(value)}
                  className={cn("inline-flex h-9 w-9 items-center justify-center rounded-md hover:bg-muted", score !== null && value <= score ? "text-primary" : "text-muted-foreground")}
                  data-testid={`chat-rating-star-${value}`}
                >
                  <Star className={cn("h-5 w-5", score !== null && value <= score && "fill-current")} aria-hidden="true" />
                </button>
              ))}
            </div>
          ) : (
            <div className="flex gap-1">
              <button type="button" className={toggleClass(score === 1)} aria-pressed={score === 1} aria-label={t("chat.rating.up", "Good reply")} onClick={() => void pick(1)} data-testid="chat-rating-card-up">
                <ThumbsUp className="h-4 w-4" aria-hidden="true" />
              </button>
              <button type="button" className={toggleClass(score === 0)} aria-pressed={score === 0} aria-label={t("chat.rating.down", "Bad reply")} onClick={() => void pick(0)} data-testid="chat-rating-card-down">
                <ThumbsDown className="h-4 w-4" aria-hidden="true" />
              </button>
            </div>
          )}
          {phase === "details" && score !== null && (
            <div className="space-y-2" data-testid="chat-rating-card-details">
              <Reasons profile={profile} tags={tags} onToggle={(tag) => setTags((l) => (l.includes(tag) ? l.filter((x) => x !== tag) : [...l, tag]))} />
              {commentOffered(score) && (
                <textarea
                  className="w-full rounded-md border border-input bg-background px-2 py-1 text-xs"
                  rows={3}
                  maxLength={profile.commentMaxLength}
                  value={comment}
                  onChange={(e) => setComment(e.target.value)}
                  placeholder={t("chat.rating.commentPlaceholder", "Anything else? (optional)")}
                  aria-label={t("chat.rating.comment", "Comment")}
                />
              )}
              <div className="flex gap-2">
                <Button size="sm" onClick={async () => (await onRate({ score, tags, comment })) && setPhase("thanks")} data-testid="chat-rating-card-send">
                  {t("chat.rating.send", "Send")}
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setPhase("thanks")}>
                  {t("chat.rating.skip", "Skip")}
                </Button>
              </div>
            </div>
          )}
          <p className="text-xs text-muted-foreground">{t("chat.rating.disclosure", "Your rating and comment are shared with the team running this assistant.")}</p>
        </div>
      )}
    </section>
  );
}
