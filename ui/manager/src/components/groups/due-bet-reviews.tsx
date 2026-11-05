import { useTranslation } from "react-i18next";
import { CalendarClock } from "lucide-react";
import { BetDetails } from "@/components/groups/bet-details";
import { useDueBetReviews, useReviewBet } from "@/hooks/use-group-workspace";

/**
 * Bets whose review date has arrived. Renders nothing while none is due, so a
 * team that files no bets sees no new section.
 */
export function DueBetReviews({ groupId, readOnly }: { groupId: string; readOnly: boolean }) {
  const { t } = useTranslation();
  const due = useDueBetReviews(groupId);
  const review = useReviewBet(groupId);
  const tasks = due.data ?? [];
  if (tasks.length === 0) return null;
  return (
    <div className="rounded-xl border border-warning/40 bg-warning/5 p-4" data-testid="workspace-due-reviews">
      <h3 className="mb-1 flex items-center gap-2 text-sm font-semibold text-foreground">
        <CalendarClock className="h-4 w-4 text-warning" />
        {t("groupLearning.dueTitle", "Bets due for review ({{count}})", { count: tasks.length })}
      </h3>
      <p className="mb-2 text-xs text-muted-foreground">
        {t("groupLearning.dueNote", "These bets have reached their review date. Look at what came of them, then close or reschedule the review.")}
      </p>
      <div className="space-y-2">
        {tasks.map((task) =>
          task.bet ? (
            <div key={task.id} data-testid={`due-review-${task.id}`}>
              <span className="text-xs font-medium text-foreground">{task.subject}</span>
              <BetDetails
                taskId={task.id}
                bet={task.bet}
                readOnly={readOnly}
                pending={review.isPending}
                onReview={(taskId, rescheduleTo) => review.mutate({ taskId, rescheduleTo })}
              />
            </div>
          ) : null,
        )}
      </div>
    </div>
  );
}
