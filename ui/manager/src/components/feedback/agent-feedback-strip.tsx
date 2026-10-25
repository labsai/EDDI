import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { ChevronRight, MessageSquareHeart } from "lucide-react";
import { useMayOpenScreen } from "@/hooks/use-auth";
import { useAttentionCount, useRatingSummary } from "@/hooks/use-ratings";
import { percent, periodRange } from "./feedback-utils";

/**
 * A compact line of the agent's feedback over the last 30 days, linking to the
 * Feedback page filtered to it. Shown when the agent asks for ratings, or when
 * it has ratings from an earlier version; a caller who may not read them (no
 * EDIT) gets nothing, not an error.
 */
export function AgentFeedbackStrip({ agentId, ratingsEnabled }: { agentId: string; ratingsEnabled: boolean }) {
  const { t } = useTranslation();
  const [now] = useState(() => Date.now());
  // Nothing is fetched for a role that may not open the Feedback page, and the
  // attention count (which polls) waits until the summary proved the caller
  // may read this agent's ratings — a refusal is not retried every 30 seconds.
  const mayOpen = useMayOpenScreen()("/manage/feedback");
  const summary = useRatingSummary({ agentId, ...periodRange("30d", now) }, "none", mayOpen);
  const attention = useAttentionCount(agentId, mayOpen && summary.isSuccess);
  const bucket = summary.data?.buckets[0];

  if (!mayOpen || summary.isError || (!ratingsEnabled && !bucket?.count)) return null;
  const satisfaction = bucket?.csat ?? bucket?.thumbsUpRate ?? null;

  return (
    <Link
      to={`/manage/feedback?agentId=${encodeURIComponent(agentId)}&tab=all`}
      className="flex flex-wrap items-center gap-x-5 gap-y-1 rounded-xl border border-border bg-card px-4 py-3 text-sm transition-colors hover:border-primary/40"
      data-testid="agent-feedback-strip"
    >
      <span className="flex items-center gap-2 font-medium">
        <MessageSquareHeart className="h-4 w-4 text-primary" aria-hidden="true" />
        {t("feedback.stripTitle", "Feedback, last 30 days")}
      </span>
      <span className="text-muted-foreground">
        {t("feedback.kpiRatings", "Ratings")}: <span className="font-semibold text-foreground tabular-nums" data-testid="strip-count">{bucket?.count ?? 0}</span>
      </span>
      <span className="text-muted-foreground">
        {t("feedback.kpiAverage", "Average score")}: <span className="font-semibold text-foreground tabular-nums">{percent(bucket?.avgNormalized)}</span>
      </span>
      <span className="text-muted-foreground">
        {t("feedback.kpiSatisfied", "Satisfied")}: <span className="font-semibold text-foreground tabular-nums">{percent(satisfaction)}</span>
      </span>
      {!!attention.data && (
        <span className="text-amber-600 dark:text-amber-400" data-testid="strip-attention">
          {t("feedback.attentionCount", { n: attention.data, defaultValue: "Ratings needing attention: {{n}}" })}
        </span>
      )}
      <ChevronRight className="ms-auto h-4 w-4 text-muted-foreground rtl:rotate-180" aria-hidden="true" />
    </Link>
  );
}
