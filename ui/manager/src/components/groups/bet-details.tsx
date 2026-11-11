import { useState } from "react";
import { useTranslation } from "react-i18next";
import { CalendarClock, FlaskConical } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { isBetDue } from "@/lib/bet";
import type { Bet } from "@/lib/api/groups";

interface BetDetailsProps {
  taskId: string;
  bet: Bet;
  /** The caller may not edit the group: no verdict buttons. */
  readOnly?: boolean;
  pending?: boolean;
  /** Close the review (no date) or reopen it for `rescheduleTo` (an ISO date). */
  onReview: (taskId: string, rescheduleTo?: string) => void;
  /** Injected so a test can pin "now". */
  now?: number;
}

function day(iso: string): string {
  return iso.slice(0, 10);
}

/**
 * The bet a backlog task carries: the claim, what judges it, and the date
 * somebody looks, with the verdict buttons when it is due.
 */
export function BetDetails({ taskId, bet, readOnly, pending, onReview, now }: BetDetailsProps) {
  const { t } = useTranslation();
  const [rescheduling, setRescheduling] = useState(false);
  const [nextDate, setNextDate] = useState("");
  const due = isBetDue(bet, now);

  return (
    <div className="mt-1.5 space-y-1 rounded-md border border-dashed border-primary/40 bg-primary/5 p-2" data-testid={`bet-${taskId}`}>
      <div className="flex flex-wrap items-center gap-1.5">
        <Badge variant="outline" className="gap-1 px-1.5 py-0 text-[9px]">
          <FlaskConical className="h-2.5 w-2.5" />
          {t("groupLearning.betBadge", "Bet")}
        </Badge>
        {bet.reviewedAt ? (
          <Badge variant="success" className="px-1.5 py-0 text-[9px]" data-testid={`bet-reviewed-${taskId}`}>
            {t("groupLearning.betReviewed", "Reviewed {{date}}", { date: day(bet.reviewedAt) })}
          </Badge>
        ) : (
          <Badge variant={due ? "warning" : "secondary"} className="gap-1 px-1.5 py-0 text-[9px]" data-testid={`bet-review-${taskId}`}>
            <CalendarClock className="h-2.5 w-2.5" />
            {due ? t("groupLearning.betDue", "Due for review") : t("groupLearning.betReviewOn", "Review {{date}}", { date: day(bet.reviewDate) })}
          </Badge>
        )}
      </div>
      <p className="text-[11px] text-foreground" data-testid={`bet-hypothesis-${taskId}`}>
        {bet.hypothesis}
      </p>
      <p className="text-[10px] text-muted-foreground">
        <span className="font-mono">{bet.metric}</span>
        {bet.target && <span> · {t("groupLearning.betTargetValue", "Target: {{target}}", { target: bet.target })}</span>}
      </p>
      {!readOnly && !bet.reviewedAt && due && (
        <div className="flex flex-wrap items-center gap-2 pt-1">
          <Button size="sm" variant="outline" disabled={pending} onClick={() => onReview(taskId)} data-testid={`bet-mark-reviewed-${taskId}`}>
            {t("groupLearning.betMarkReviewed", "Mark reviewed")}
          </Button>
          {rescheduling ? (
            <>
              <input
                type="date"
                value={nextDate}
                onChange={(e) => setNextDate(e.target.value)}
                aria-label={t("groupLearning.betRescheduleTo", "Review again on")}
                className="rounded-lg border border-input bg-background px-2 py-1 text-xs"
                data-testid={`bet-reschedule-date-${taskId}`}
              />
              <Button
                size="sm"
                disabled={pending || !nextDate}
                onClick={() => {
                  onReview(taskId, nextDate);
                  setRescheduling(false);
                  setNextDate("");
                }}
                data-testid={`bet-reschedule-confirm-${taskId}`}
              >
                {t("groupLearning.betReschedule", "Reschedule")}
              </Button>
            </>
          ) : (
            <Button size="sm" variant="ghost" onClick={() => setRescheduling(true)} data-testid={`bet-reschedule-${taskId}`}>
              {t("groupLearning.betReschedule", "Reschedule")}
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
