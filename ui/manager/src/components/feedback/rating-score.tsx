import { useTranslation } from "react-i18next";
import { Star, ThumbsDown, ThumbsUp } from "lucide-react";
import { cn } from "@/lib/utils";
import type { RatingScale } from "@/lib/api/ratings";

/**
 * A score as people gave it: filled stars out of five, or a thumb. Low scores
 * read in the destructive tone so a list of them can be scanned.
 */
export function RatingScore({ scale, score, className }: { scale: RatingScale; score: number; className?: string }) {
  const { t } = useTranslation();
  if (scale === "THUMBS") {
    const up = score === 1;
    const Icon = up ? ThumbsUp : ThumbsDown;
    return (
      <span
        className={cn("inline-flex items-center", up ? "text-emerald-600 dark:text-emerald-400" : "text-destructive", className)}
        role="img"
        aria-label={up ? t("feedback.thumbsUp", "Thumbs up") : t("feedback.thumbsDown", "Thumbs down")}
        data-testid="rating-score"
      >
        <Icon className="h-4 w-4" aria-hidden="true" />
      </span>
    );
  }
  return (
    <span
      className={cn("inline-flex items-center gap-0.5", score <= 2 ? "text-destructive" : "text-primary", className)}
      role="img"
      aria-label={t("feedback.starsOf", { n: score, defaultValue: "{{n}} of 5 stars" })}
      data-testid="rating-score"
    >
      {[1, 2, 3, 4, 5].map((n) => (
        <Star key={n} className={cn("h-3.5 w-3.5", n <= score ? "fill-current" : "opacity-30")} aria-hidden="true" />
      ))}
    </span>
  );
}
