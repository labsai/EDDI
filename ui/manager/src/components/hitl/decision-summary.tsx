import { useTranslation } from "react-i18next";
import { CheckCircle2, XCircle } from "lucide-react";
import type { SubmittedDecision } from "@/lib/hitl-decision";

/**
 * The compact stand-in for an approval card once the decision is submitted:
 * what was agreed to, per call or task, while the resumed work runs. Shared by
 * every surface that decides, so they all acknowledge a decision the same way.
 */
export function DecisionSummary({ decision }: { decision: SubmittedDecision }) {
  const { t } = useTranslation();
  const approved = decision.verdict === "APPROVED";
  return (
    <div
      className="min-w-0 max-w-full rounded-xl border border-border bg-muted/30 p-3 text-sm"
      data-testid="approval-decision-summary"
      data-verdict={decision.verdict}
    >
      <p className="flex items-center gap-1.5 font-medium text-foreground">
        {approved ? (
          <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-600" aria-hidden="true" />
        ) : (
          <XCircle className="h-4 w-4 shrink-0 text-destructive" aria-hidden="true" />
        )}
        {approved
          ? t("hitl.decisionSummaryApproved", "You approved this request")
          : t("hitl.decisionSummaryRejected", "You rejected this request")}
      </p>
      {decision.phaseName && (
        <p className="mt-1 ps-5 text-xs text-muted-foreground" data-testid="approval-decision-summary-phase">
          {t("hitl.phase", "Phase")}: {decision.phaseName}
        </p>
      )}
      {decision.items.length > 0 && (
        <ul className="mt-1.5 space-y-0.5 ps-5 text-xs">
          {decision.items.map((item) => (
            <li
              key={item.id}
              className="flex flex-wrap items-center gap-1.5"
              data-testid={`approval-decision-summary-call-${item.id}`}
              data-outcome={item.outcome}
            >
              <span className="font-mono text-foreground">{item.label}</span>
              <span className={item.outcome === "APPROVED" ? "text-emerald-600" : "text-destructive"}>
                {item.outcome === "APPROVED"
                  ? t("hitl.decisionSummaryCallApproved", "approved")
                  : t("hitl.decisionSummaryCallRejected", "rejected")}
              </span>
              {item.amended && (
                <span className="text-muted-foreground">({t("hitl.decisionSummaryAmended", "amended")})</span>
              )}
            </li>
          ))}
        </ul>
      )}
      {decision.note && (
        <p className="mt-1.5 break-words text-xs text-muted-foreground" data-testid="approval-decision-summary-note">
          {t("hitl.decisionSummaryNote", "Note: {{note}}", { note: decision.note })}
        </p>
      )}
    </div>
  );
}
