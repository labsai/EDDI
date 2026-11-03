import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Wallet } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useIsAdminOrUnknown } from "@/hooks/use-auth";
import { useTeamBudget, useTeamBudgetHistory } from "@/hooks/use-team-budget";
import { spentFraction, type TeamBudgetSnapshot } from "@/lib/api/team-budget";
import { cn, formatUsd } from "@/lib/utils";
import { AdjustBudgetDialog } from "@/components/quotas/team-budget-dialogs";

/** The cap/spent bar shared by the card and the quotas table. */
export function BudgetBar({ snapshot }: { snapshot: Pick<TeamBudgetSnapshot, "capUsd" | "spentUsd" | "exhausted"> }) {
  const fraction = spentFraction(snapshot);
  const warn = snapshot.exhausted || fraction >= 0.8;
  return (
    <div
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(fraction * 100)}
      className="h-2 w-full overflow-hidden rounded-full bg-secondary"
    >
      <div className={cn("h-full rounded-full", warn ? "bg-warn-500" : "bg-primary")} style={{ width: `${fraction * 100}%` }} />
    </div>
  );
}

/**
 * A group's monthly budget (docs/team-budgets.md). Renders nothing for a group
 * without a `teamBudget`, or when the caller may not view it: the card is an
 * extra, never a reason for the page around it to fail.
 */
export function TeamBudgetCard({ groupId }: { groupId: string }) {
  const { t } = useTranslation();
  const isAdmin = useIsAdminOrUnknown();
  const { data: budget, isError } = useTeamBudget(groupId);
  const [showHistory, setShowHistory] = useState(false);
  const [adjusting, setAdjusting] = useState<TeamBudgetSnapshot | null>(null);
  const history = useTeamBudgetHistory(groupId, showHistory);

  if (isError) {
    return null;
  }
  if (!budget || budget.capUsd === null) {
    return null;
  }

  return (
    <section className="space-y-3 rounded-xl border border-border bg-card p-4" data-testid="team-budget-card" aria-label={t("teamBudget.title", "Team budget")}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
          <Wallet className="h-4 w-4" aria-hidden="true" />
          {t("teamBudget.title", "Team budget")} · {budget.month}
        </h3>
        <div className="flex items-center gap-2">
          {budget.exhausted && (
            <span className="rounded-full bg-warn-500/10 px-2 py-0.5 text-xs font-medium text-warn-700 dark:text-warn-400" data-testid="team-budget-exhausted">
              {t("teamBudget.exhausted", "Budget spent")}
            </span>
          )}
          {budget.limitedBy === "TENANT" && (
            <span className="rounded-full bg-warn-500/10 px-2 py-0.5 text-xs text-warn-700 dark:text-warn-400" data-testid="team-budget-tenant-limited">
              {t("teamBudget.limitedByTenant", "Limited by the tenant budget")}
            </span>
          )}
          {isAdmin && (
            <Button size="sm" variant="outline" onClick={() => setAdjusting(budget)} data-testid="team-budget-adjust">
              {t("teamBudget.adjust", "Adjust this month")}
            </Button>
          )}
        </div>
      </div>
      <BudgetBar snapshot={budget} />
      <dl className="grid grid-cols-3 gap-3 text-sm">
        <div>
          <dt className="text-xs text-muted-foreground">{t("teamBudget.cap", "Cap")}</dt>
          <dd className="font-medium" data-testid="team-budget-cap">{formatUsd(budget.capUsd)}</dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">{t("teamBudget.spent", "Spent")}</dt>
          <dd className="font-medium" data-testid="team-budget-spent">{formatUsd(budget.spentUsd)}</dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">{t("teamBudget.remaining", "Remaining")}</dt>
          <dd className="font-medium" data-testid="team-budget-remaining">{formatUsd(budget.remainingUsd ?? 0)}</dd>
        </div>
      </dl>
      <p className="text-xs text-muted-foreground">{t("teamBudget.resetsOn", "Resets {{date}}", { date: new Date(budget.resetsAt).toLocaleDateString() })}</p>

      <button
        type="button"
        className="text-xs font-medium text-primary hover:underline"
        aria-expanded={showHistory}
        onClick={() => setShowHistory((v) => !v)}
        data-testid="team-budget-history-toggle"
      >
        {t("teamBudget.history", "History")}
      </button>
      {showHistory && (
        <div className="overflow-x-auto" data-testid="team-budget-history">
          {history.isError || (history.data && history.data.length === 0) ? (
            <p className="text-xs text-muted-foreground">{t("teamBudget.historyEmpty", "No earlier months yet.")}</p>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-xs text-muted-foreground">
                  <th scope="col" className="py-1 text-start font-medium">{t("teamBudget.month", "Month")}</th>
                  <th scope="col" className="py-1 text-end font-medium">{t("teamBudget.spent", "Spent")}</th>
                  <th scope="col" className="py-1 text-end font-medium">{t("teamBudget.adjustments", "Adjustments")}</th>
                </tr>
              </thead>
              <tbody>
                {(history.data ?? []).map((m) => (
                  <tr key={m.month} className="border-t border-border">
                    <td className="py-1">{m.month}</td>
                    <td className="py-1 text-end">{formatUsd(m.spentUsd)}</td>
                    <td className="py-1 text-end">{m.adjustmentUsd === 0 ? "-" : formatUsd(m.adjustmentUsd)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
      <AdjustBudgetDialog snapshot={adjusting} onClose={() => setAdjusting(null)} />
    </section>
  );
}
