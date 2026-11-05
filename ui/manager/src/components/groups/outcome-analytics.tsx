import { useTranslation } from "react-i18next";
import { useOutcomeAnalytics } from "@/hooks/use-group-outcomes";

/**
 * What came of the team's approvals: decisions joined with the outcomes their
 * work reported, per approval category. Needs the approver or admin role, so a
 * caller without it (or a group with no outcomes) simply sees nothing.
 */
export function OutcomeAnalyticsPanel({ groupId }: { groupId: string }) {
  const { t } = useTranslation();
  const analytics = useOutcomeAnalytics(groupId);
  const data = analytics.data;
  if (!data || data.categories.length === 0) return null;
  return (
    <div className="rounded-xl border border-border bg-card p-4" data-testid="workspace-outcome-analytics">
      <h3 className="mb-1 text-sm font-semibold text-foreground">{t("groupLearning.analyticsTitle", "What came of approvals")}</h3>
      <p className="mb-3 text-xs text-muted-foreground">
        {t("groupLearning.analyticsNote", "This team's approval decisions of the last 30 days, joined with the outcomes their work reported.")}
      </p>
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead className="text-start text-[10px] uppercase tracking-wider text-muted-foreground">
            <tr>
              <th className="px-2 py-1 text-start">{t("oversight.colCategory", "Category")}</th>
              <th className="px-2 py-1 text-start">{t("oversight.colDecisions", "Decisions")}</th>
              <th className="px-2 py-1 text-start">{t("groupLearning.colWithOutcome", "With an outcome")}</th>
              <th className="px-2 py-1 text-start">{t("groupLearning.colByKind", "Outcomes by kind")}</th>
            </tr>
          </thead>
          <tbody>
            {data.categories.map((row) => (
              <tr key={row.category} className="border-t border-border" data-testid={`outcome-category-${row.category}`}>
                <td className="px-2 py-1 font-mono">{row.category}</td>
                <td className="px-2 py-1">{row.decisions}</td>
                <td className="px-2 py-1">{row.decisionsWithOutcomes}</td>
                <td className="px-2 py-1">
                  {Object.entries(row.byKind)
                    .map(([kind, count]) => `${kind} (${count})`)
                    .join(", ") || "—"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {data.truncated && (
        <p className="mt-2 text-[10px] text-muted-foreground">
          {t("oversight.truncated", "This period holds more decisions than one summary reads; narrow it for exact numbers.")}
        </p>
      )}
    </div>
  );
}
