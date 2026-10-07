import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { Hand } from "lucide-react";
import { useApprovalStatus } from "@/hooks/use-hitl";

export interface PauseNoticeProps {
  conversationId: string;
  /** When the pause started (`hitlPausedAt`), when the stream or snapshot said so. */
  pausedAt?: string | null;
  /** Shown until approval-status supplies the real reason. */
  fallbackReason?: string | null;
  /** Always shown instead of the reason (the surface has its own sentence for the pause). */
  headline?: string | null;
  /** Called once when the pause that was seen is decided elsewhere (or times out). */
  onResolved: () => void;
  /** Where the pause can be decided. Defaults to the conversation's review page. */
  reviewHref?: string;
  /** `data-testid` prefix: `<base>-banner`, `<base>-review`, `<base>-pending`. */
  testIdBase?: string;
  /** Override for the review link's test id. */
  reviewTestId?: string;
  /** Hide the link (a surface that cannot navigate to the Manager). */
  hideReview?: boolean;
}

/**
 * The read-only face of a pause, for surfaces that cannot decide it (the
 * Manager chat panel, the Workforce thread): what is pending, and a link to the
 * page that can decide it. It watches the pause with the same verify-the-pause
 * rule as the deciding surfaces, so it updates promptly when the pause is
 * decided elsewhere instead of staying on "awaiting approval", and never
 * mistakes a not-yet-stored read for a resolution.
 */
export function PauseNotice({
  conversationId,
  pausedAt,
  fallbackReason,
  headline,
  onResolved,
  reviewHref,
  testIdBase = "pause-notice",
  reviewTestId,
  hideReview,
}: PauseNoticeProps) {
  const { t } = useTranslation();
  const status = useApprovalStatus(conversationId, true, pausedAt ?? null, {
    verifyPause: true,
    onResolved,
  });
  // A pause that was never seen stored and whose window ran out while the
  // conversation reads settled was decided before the first read: resolved too.
  const latestState = status.latest?.state;
  const decidedBeforeSeen =
    status.detailsError && !!latestState && latestState !== "AWAITING_HUMAN" && latestState !== "IN_PROGRESS";
  const onResolvedRef = useRef(onResolved);
  useEffect(() => {
    onResolvedRef.current = onResolved;
  });
  useEffect(() => {
    if (decidedBeforeSeen) onResolvedRef.current();
  }, [decidedBeforeSeen]);

  const details = status.data?.pauseDetails;
  const tools = details?.type === "TOOL_CALL" ? details.calls.map((c) => c.toolName).filter(Boolean) : [];
  const reason =
    headline ||
    status.data?.pauseReason ||
    fallbackReason ||
    t("hitl.chatPaused", "This conversation is awaiting human approval.");
  const href = reviewHref ?? `/manage/conversationview/${conversationId}?awaiting=1`;

  return (
    <div
      className="flex flex-wrap items-center gap-2 border-t border-amber-500/30 bg-amber-500/5 px-4 py-2.5 text-xs"
      data-testid={`${testIdBase}-banner`}
    >
      <Hand className="h-4 w-4 shrink-0 text-amber-500" aria-hidden="true" />
      <span className="text-amber-600 dark:text-amber-400">{reason}</span>
      {tools.length > 0 && (
        <span className="font-mono text-muted-foreground" data-testid={`${testIdBase}-tools`}>
          {t("hitl.noticeWaitingFor", "Waiting for approval: {{tools}}", { tools: tools.join(", ") })}
        </span>
      )}
      {status.detailsPending && (
        <span className="text-muted-foreground" data-testid={`${testIdBase}-pending`}>
          {t("hitl.noticeSaving", "Saving the pause…")}
        </span>
      )}
      {!hideReview && (
        <Link
          to={href}
          className="ms-auto rounded-md bg-amber-500/10 px-2.5 py-1 font-medium text-amber-600 hover:bg-amber-500/20 transition-colors dark:text-amber-400"
          data-testid={reviewTestId ?? `${testIdBase}-review`}
        >
          {t("hitl.review", "Review")}
        </Link>
      )}
    </div>
  );
}
