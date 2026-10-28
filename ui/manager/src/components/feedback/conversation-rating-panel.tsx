import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { MessageSquareHeart } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { RatingScore } from "./rating-score";
import type { RatingRow } from "@/lib/api/ratings";

/** "Reviewed" / "New" — the triage state of a rating. */
export function ReviewStatusBadge({ row }: { row: RatingRow }) {
  const { t } = useTranslation();
  return row.reviewStatus === "REVIEWED" ? (
    <Badge variant="success" title={row.reviewedBy ?? undefined}>
      {t("feedback.statusReviewed", "Reviewed")}
    </Badge>
  ) : (
    <Badge variant="warning">{t("feedback.statusNew", "New")}</Badge>
  );
}

/**
 * The conversation's own rating at the top of the conversation view, for a
 * caller allowed to see it (the rating list answers only them). Reply ratings
 * are shown at their steps by the page.
 */
export function ConversationRatingPanel({ rows }: { rows: RatingRow[] }) {
  const { t } = useTranslation();
  const conversation = rows.find((r) => r.kind === "conversation");
  const replies = rows.filter((r) => r.kind === "message").length;
  if (!conversation && replies === 0) return null;

  return (
    <section className="rounded-xl border bg-card p-5 shadow-sm" data-testid="conversation-rating-panel">
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <MessageSquareHeart className="h-5 w-5 text-primary" aria-hidden="true" />
        <h2 className="text-lg font-semibold text-foreground">{t("feedback.conversationRating", "Rating")}</h2>
        <Link
          to={`/manage/feedback?agentId=${encodeURIComponent(conversation?.agentId ?? rows[0]?.agentId ?? "")}&tab=all`}
          className="ms-auto text-xs text-primary hover:underline"
        >
          {t("feedback.openFeedback", "Open in Feedback")}
        </Link>
      </div>
      {conversation ? (
        <div className="space-y-2" data-testid="conversation-rating">
          <div className="flex flex-wrap items-center gap-3">
            <RatingScore scale={conversation.scale} score={conversation.score} />
            <ReviewStatusBadge row={conversation} />
            {conversation.tags.map((tag) => (
              <Badge key={tag} variant="secondary">
                {tag}
              </Badge>
            ))}
          </div>
          {conversation.comment && <p className="whitespace-pre-wrap break-words text-sm">{conversation.comment}</p>}
          {conversation.reviewNote && <p className="text-xs text-muted-foreground">{conversation.reviewNote}</p>}
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">{t("feedback.noConversationRating", "The conversation itself was not rated.")}</p>
      )}
      {replies > 0 && (
        <p className="mt-2 text-xs text-muted-foreground" data-testid="conversation-reply-ratings">
          {t("feedback.replyRatings", { n: replies, defaultValue: "Rated replies: {{n}} (shown at their steps below)" })}
        </p>
      )}
    </section>
  );
}

/** A reply rating under the agent bubble it rates. */
export function StepRatingBadge({ row }: { row: RatingRow }) {
  return (
    <div className="flex flex-wrap items-center gap-2 ps-9" data-testid={`step-rating-${row.stepIndex}`}>
      <RatingScore scale={row.scale} score={row.score} />
      <ReviewStatusBadge row={row} />
      {row.tags.map((tag) => (
        <Badge key={tag} variant="secondary">
          {tag}
        </Badge>
      ))}
      {row.comment && <span className="text-xs text-muted-foreground">{row.comment}</span>}
    </div>
  );
}
