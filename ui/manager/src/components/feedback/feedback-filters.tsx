import { useTranslation } from "react-i18next";
import { Input } from "@/components/ui/input";
import { PERIODS, type FeedbackFilters, type Period } from "./feedback-utils";

const selectClass =
  "h-10 rounded-lg border border-input bg-background px-3 text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-ring";

/**
 * The "All ratings" filter bar. Every value lives in the URL (the page owns
 * `onChange`), so a filtered view is a link that can be shared.
 */
export function FeedbackFilterBar({
  filters,
  onChange,
}: {
  filters: FeedbackFilters;
  onChange: (key: keyof FeedbackFilters, value: string) => void;
}) {
  const { t } = useTranslation();
  const periodLabels: Record<Period, string> = {
    "7d": t("feedback.period7d", "Last 7 days"),
    "30d": t("feedback.period30d", "Last 30 days"),
    "90d": t("feedback.period90d", "Last 90 days"),
    all: t("feedback.periodAll", "All time"),
  };

  return (
    <div className="flex flex-wrap items-end gap-3" data-testid="feedback-filters">
      <label className="flex flex-col gap-1 text-xs text-muted-foreground">
        {t("feedback.filterPeriod", "Period")}
        <select
          className={selectClass}
          value={filters.period}
          onChange={(e) => onChange("period", e.target.value)}
          data-testid="feedback-filter-period"
        >
          {PERIODS.map((p) => (
            <option key={p} value={p}>
              {periodLabels[p]}
            </option>
          ))}
        </select>
      </label>

      <label className="flex flex-col gap-1 text-xs text-muted-foreground">
        {t("feedback.filterVersion", "Agent version")}
        <Input
          className="w-28"
          inputMode="numeric"
          value={filters.agentVersion}
          onChange={(e) => onChange("agentVersion", e.target.value.replace(/\D/g, ""))}
          placeholder={t("feedback.anyVersion", "Any")}
          data-testid="feedback-filter-version"
        />
      </label>

      <label className="flex flex-col gap-1 text-xs text-muted-foreground">
        {t("feedback.filterEnvironment", "Environment")}
        <select
          className={selectClass}
          value={filters.environment}
          onChange={(e) => onChange("environment", e.target.value)}
          data-testid="feedback-filter-environment"
        >
          <option value="">{t("feedback.anyEnvironment", "Any environment")}</option>
          <option value="production">production</option>
          <option value="test">test</option>
        </select>
      </label>

      <label className="flex flex-col gap-1 text-xs text-muted-foreground">
        {t("feedback.filterKind", "Rated")}
        <select
          className={selectClass}
          value={filters.kind}
          onChange={(e) => onChange("kind", e.target.value)}
          data-testid="feedback-filter-kind"
        >
          <option value="">{t("feedback.kindAny", "Conversations and replies")}</option>
          <option value="conversation">{t("feedback.kindConversation", "Whole conversations")}</option>
          <option value="message">{t("feedback.kindMessage", "Single replies")}</option>
        </select>
      </label>

      <label className="flex flex-col gap-1 text-xs text-muted-foreground">
        {t("feedback.filterScore", "Score")}
        <select
          className={selectClass}
          value={filters.score}
          onChange={(e) => onChange("score", e.target.value)}
          data-testid="feedback-filter-score"
        >
          <option value="">{t("feedback.scoreAny", "Any score")}</option>
          <option value="low">{t("feedback.scoreLow", "Low (half or less)")}</option>
          <option value="high">{t("feedback.scoreHigh", "High (four stars or more)")}</option>
        </select>
      </label>

      <label className="flex flex-col gap-1 text-xs text-muted-foreground">
        {t("feedback.filterComment", "Comment")}
        <select
          className={selectClass}
          value={filters.comment}
          onChange={(e) => onChange("comment", e.target.value)}
          data-testid="feedback-filter-comment"
        >
          <option value="">{t("feedback.commentAny", "With or without")}</option>
          <option value="yes">{t("feedback.commentYes", "With a comment")}</option>
          <option value="no">{t("feedback.commentNo", "Without a comment")}</option>
        </select>
      </label>

      <label className="flex flex-col gap-1 text-xs text-muted-foreground">
        {t("feedback.filterStatus", "Review status")}
        <select
          className={selectClass}
          value={filters.status}
          onChange={(e) => onChange("status", e.target.value)}
          data-testid="feedback-filter-status"
        >
          <option value="">{t("feedback.statusAny", "Any status")}</option>
          <option value="NEW">{t("feedback.statusNew", "New")}</option>
          <option value="REVIEWED">{t("feedback.statusReviewed", "Reviewed")}</option>
        </select>
      </label>

      <label className="flex flex-col gap-1 text-xs text-muted-foreground">
        {t("feedback.filterTag", "Reason")}
        <Input
          className="w-40"
          value={filters.tag}
          onChange={(e) => onChange("tag", e.target.value)}
          placeholder={t("feedback.anyReason", "Any reason")}
          data-testid="feedback-filter-tag"
        />
      </label>
    </div>
  );
}
