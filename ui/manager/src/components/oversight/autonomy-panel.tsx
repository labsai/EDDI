import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { ShieldCheck } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import { TierBadge } from "@/components/oversight/approval-analytics";
import { percent } from "@/components/oversight/format";
import { useMayUse } from "@/hooks/use-auth";
import { oversightErrorCode, useApplyRecommendation, useDismissRecommendation, useLiftSuspension } from "@/hooks/use-hitl-oversight";
import { ROLE_ADMIN, ROLE_EDITOR } from "@/lib/roles";
import { OVERSIGHT_LIMITS, type CategoryView } from "@/lib/api/hitl-oversight";
import type { UseQueryResult } from "@tanstack/react-query";

/**
 * Earned autonomy: open recommendations with their evidence and a preview of
 * the change ("Apply" writes a new agent version; it does not deploy), and the
 * active suspensions with their trigger and a "Lift" that needs a note.
 * Promotion progress is shown only here — never on an approval card, where it
 * would anchor the approver.
 */
export function AutonomyPanel({ states }: { states: UseQueryResult<CategoryView[]> }) {
  const { t } = useTranslation();
  if (states.isLoading) {
    return <Skeleton className="h-32 w-full" data-testid="oversight-autonomy-loading" />;
  }
  if (states.isError && !states.data) {
    return <ErrorState message={t("common.error", "Something went wrong")} onRetry={() => void states.refetch()} retryLabel={t("common.retry", "Retry")} />;
  }
  const actionable = (states.data ?? []).filter(
    (v) => v.tier === "SUSPENDED" || v.state.recommendation?.status === "OPEN",
  );
  if (actionable.length === 0) {
    return (
      <EmptyState
        icon={ShieldCheck}
        title={t("oversight.autonomyEmptyTitle", "Nothing to decide")}
        description={t(
          "oversight.autonomyEmptyDescription",
          "Recommendations appear when a category named in hitlConfig.oversight.autonomyPolicy has earned them; suspensions when a reviewer flags a sample.",
        )}
      />
    );
  }
  return (
    <div className="space-y-3" data-testid="oversight-autonomy">
      {actionable.map((view) => (
        <CategoryCard key={`${view.agentId}|${view.category}`} view={view} />
      ))}
    </div>
  );
}

function CategoryCard({ view }: { view: CategoryView }) {
  const { t } = useTranslation();
  const apply = useApplyRecommendation();
  const dismiss = useDismissRecommendation();
  const lift = useLiftSuspension();
  // Apply, dismiss and lift take a gate away: the backend admits eddi-admin and
  // eddi-editor only (an approver alone gets 403), so they are not offered to anyone else.
  const mayChange = useMayUse()([ROLE_ADMIN, ROLE_EDITOR]);
  const [note, setNote] = useState("");
  const [confirmNoGate, setConfirmNoGate] = useState(false);
  const recommendation = view.state.recommendation?.status === "OPEN" ? view.state.recommendation : null;
  const suspension = view.state.suspension?.active ? view.state.suspension : null;
  const testId = `oversight-category-card-${view.category}`;

  const failed = (err: unknown) => {
    const code = oversightErrorCode(err);
    if (code === "EVIDENCE_CHANGED") toast.error(t("oversight.evidenceChanged", "The evidence no longer supports this recommendation."));
    else if (code === "AGENT_CHANGED") toast.error(t("oversight.agentChanged", "The agent has a newer version; reload and review again."));
    else toast.error(t("oversight.actionFailed", "That did not work; reload and try again."));
  };

  const doApply = async () => {
    if (!recommendation) return;
    if (recommendation.leavesNoGate && !confirmNoGate) {
      setConfirmNoGate(true);
      return;
    }
    try {
      const result = await apply.mutateAsync({
        agentId: view.agentId,
        category: view.category,
        recommendationId: recommendation.id,
        expectedAgentVersion: recommendation.basedOnAgentVersion ?? 0,
      });
      toast.success(
        t("oversight.applied", {
          version: result?.state?.recommendation?.resultingAgentVersion ?? "",
          defaultValue: "Saved as agent version {{version}}. Deploy it to take effect.",
        }),
      );
    } catch (err) {
      failed(err);
    }
  };

  const withNote = async (action: "dismiss" | "lift") => {
    try {
      if (action === "dismiss" && recommendation) {
        await dismiss.mutateAsync({ agentId: view.agentId, category: view.category, recommendationId: recommendation.id, note: note.trim() });
      } else {
        await lift.mutateAsync({ agentId: view.agentId, category: view.category, note: note.trim() });
      }
      setNote("");
    } catch (err) {
      failed(err);
    }
  };

  const evidence = recommendation?.evidence ?? view.evidence;
  return (
    <div className="space-y-3 rounded-xl border border-border bg-card p-4" data-testid={testId}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-mono text-sm text-foreground">{view.category}</span>
        <TierBadge tier={view.tier} />
      </div>
      {suspension && (
        <p className="rounded-lg bg-warning/10 px-3 py-2 text-sm text-warning" data-testid={`${testId}-suspension`}>
          {t("oversight.suspendedSince", {
            pattern: suspension.pattern,
            trigger: suspension.trigger,
            defaultValue: "'{{pattern}}' calls need approval again ({{trigger}}).",
          })}
          {suspension.triggerRef && <span className="ms-1 font-mono text-xs">{suspension.triggerRef}</span>}
        </p>
      )}
      {evidence && (
        <p className="text-sm text-muted-foreground" data-testid={`${testId}-evidence`}>
          {t("oversight.evidence", {
            n: evidence.n,
            approved: evidence.approved,
            edited: evidence.edited,
            rejected: evidence.rejected,
            rate: percent(evidence.uneditedRate),
            defaultValue: "{{n}} human decisions: {{approved}} approved, {{edited}} edited, {{rejected}} rejected — {{rate}} unedited.",
          })}
        </p>
      )}
      {recommendation && (
        <div className="space-y-2" data-testid={`${testId}-recommendation`}>
          <p className="text-sm font-medium text-foreground">
            {recommendation.kind === "PROMOTE"
              ? t("oversight.promoteTitle", "Recommended: stop asking, sample instead")
              : t("oversight.revertTitle", "Recommended: make the suspension permanent")}
          </p>
          <ul className="list-disc space-y-1 ps-5 text-sm" data-testid={`${testId}-changes`}>
            {recommendation.changes.map((change) => (
              <li key={change}>{change}</li>
            ))}
          </ul>
          {recommendation.proposedHitlConfig && (
            <details>
              <summary className="cursor-pointer text-xs text-muted-foreground">{t("oversight.preview", "Configuration after the change")}</summary>
              <pre className="mt-1 max-h-64 overflow-auto rounded bg-muted p-2 font-mono text-xs">
                {JSON.stringify(recommendation.proposedHitlConfig, null, 2)}
              </pre>
            </details>
          )}
          {confirmNoGate && (
            <p className="rounded-lg bg-warning/10 px-3 py-2 text-sm text-warning" data-testid={`${testId}-no-gate-warning`}>
              {t("oversight.noGateWarning", "After this the agent has no gated tools. Click Apply again to confirm.")}
            </p>
          )}
        </div>
      )}
      <textarea
        className="w-full rounded-md border border-input bg-background p-2 text-sm"
        rows={2}
        maxLength={OVERSIGHT_LIMITS.maxNoteLength}
        placeholder={t("oversight.actionNotePlaceholder", "Note (required to dismiss or lift)")}
        aria-label={t("oversight.noteLabel", "Note")}
        value={note}
        onChange={(e) => setNote(e.target.value)}
        data-testid={`${testId}-note`}
      />
      <div className="flex flex-wrap gap-2">
        {mayChange && recommendation?.applicable && (
          <Button size="sm" disabled={apply.isPending} onClick={() => void doApply()} data-testid={`${testId}-apply`}>
            {t("oversight.apply", { version: (recommendation.basedOnAgentVersion ?? 0) + 1, defaultValue: "Apply → version {{version}}" })}
          </Button>
        )}
        {mayChange && recommendation && (
          <Button size="sm" variant="outline" disabled={!note.trim() || dismiss.isPending} onClick={() => void withNote("dismiss")} data-testid={`${testId}-dismiss`}>
            {t("oversight.dismiss", "Dismiss")}
          </Button>
        )}
        {mayChange && suspension && (
          <Button size="sm" variant="outline" disabled={!note.trim() || lift.isPending} onClick={() => void withNote("lift")} data-testid={`${testId}-lift`}>
            {t("oversight.lift", "Lift suspension")}
          </Button>
        )}
      </div>
    </div>
  );
}
