import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Wallet } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useGroupDescriptors } from "@/hooks/use-groups";
import { useIsAdminOrUnknown } from "@/hooks/use-auth";
import { useTeamBudgets } from "@/hooks/use-team-budget";
import { groupGroupsByName } from "@/lib/api/groups";
import { TEAM_BUDGET_MAX_LISTED_GROUPS, type TeamBudgetSnapshot } from "@/lib/api/team-budget";
import { formatUsd } from "@/lib/utils";
import { BudgetBar } from "@/components/workforce/team-budget-card";
import { AdjustBudgetDialog, TransferBudgetDialog } from "./team-budget-dialogs";

/**
 * Every team that has a monthly budget, with this month's numbers, and (for an
 * administrator) the adjust and transfer dialogs. Groups come from the group list;
 * the budgets from one batch call, which leaves out groups the caller cannot view.
 */
export function TeamBudgetsTable() {
  const { t } = useTranslation();
  const isAdmin = useIsAdminOrUnknown();
  // Plain descriptors are enough (only ids are needed); the batch endpoint takes at most
  // TEAM_BUDGET_MAX_LISTED_GROUPS ids, so the newest groups are listed.
  const groups = useGroupDescriptors(TEAM_BUDGET_MAX_LISTED_GROUPS, 0, "");
  const ids = useMemo(() => groupGroupsByName(groups.data ?? []).map((g) => g.id), [groups.data]);
  const budgets = useTeamBudgets(ids);
  const [adjusting, setAdjusting] = useState<TeamBudgetSnapshot | null>(null);
  const [transferFrom, setTransferFrom] = useState<string | null>(null);

  const teams = (budgets.data?.teams ?? []).filter((s) => s.capUsd !== null);
  if (groups.isError || budgets.isError) {
    return null;
  }

  return (
    <section className="space-y-3 rounded-xl border border-border bg-card p-4" data-testid="team-budgets-table" aria-labelledby="team-budgets-heading">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 id="team-budgets-heading" className="flex items-center gap-1.5 text-base font-semibold text-foreground">
            <Wallet className="h-4 w-4" aria-hidden="true" />
            {t("teamBudget.tableTitle", "Team budgets")}
          </h2>
          <p className="text-sm text-muted-foreground">
            {t("teamBudget.tableDescription", "Each team's share of the tenant's monthly budget. A team starts no new work once its cap is spent.")}
          </p>
        </div>
        {isAdmin && teams.length > 1 && (
          <Button size="sm" variant="outline" onClick={() => setTransferFrom("")} data-testid="team-budget-open-transfer">
            {t("teamBudget.transfer", "Transfer budget")}
          </Button>
        )}
      </div>

      {teams.length === 0 ? (
        <p className="text-sm text-muted-foreground" data-testid="team-budgets-empty">
          {t("teamBudget.noBudgets", "No team has a monthly budget yet. Set teamBudget on a group to give it one.")}
        </p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-xs text-muted-foreground">
                <th scope="col" className="py-1 text-start font-medium">{t("teamBudget.team", "Team")}</th>
                <th scope="col" className="w-40 py-1 text-start font-medium">{t("teamBudget.spent", "Spent")}</th>
                <th scope="col" className="py-1 text-end font-medium">{t("teamBudget.cap", "Cap")}</th>
                <th scope="col" className="py-1 text-end font-medium">{t("teamBudget.remaining", "Remaining")}</th>
                {isAdmin && <th scope="col" className="py-1" />}
              </tr>
            </thead>
            <tbody>
              {teams.map((s) => (
                <tr key={s.groupId} className="border-t border-border" data-testid={`team-budget-row-${s.groupId}`}>
                  <td className="py-2 font-medium">
                    {s.groupName || s.groupId}
                    {s.exhausted && (
                      <span className="ms-2 rounded-full bg-warn-500/10 px-2 py-0.5 text-xs text-warn-700 dark:text-warn-400">
                        {t("teamBudget.exhausted", "Budget spent")}
                      </span>
                    )}
                  </td>
                  <td className="py-2">
                    <BudgetBar snapshot={s} />
                    <span className="text-xs text-muted-foreground">{formatUsd(s.spentUsd)}</span>
                  </td>
                  <td className="py-2 text-end">{formatUsd(s.capUsd ?? 0)}</td>
                  <td className="py-2 text-end">{formatUsd(s.remainingUsd ?? 0)}</td>
                  {isAdmin && (
                    <td className="py-2 text-end">
                      <Button size="sm" variant="ghost" onClick={() => setAdjusting(s)} data-testid={`team-budget-adjust-${s.groupId}`}>
                        {t("teamBudget.adjust", "Adjust this month")}
                      </Button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <AdjustBudgetDialog snapshot={adjusting} onClose={() => setAdjusting(null)} />
      <TransferBudgetDialog open={transferFrom !== null} teams={teams} initialFromId={transferFrom ?? undefined} onClose={() => setTransferFrom(null)} />
    </section>
  );
}
