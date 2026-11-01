import { AlertTriangle, Ban, CheckCircle2, Loader2, OctagonX, XCircle } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Badge } from "@/components/ui/badge";
import type { AgentTestRun, RunStatus } from "@/lib/api/agent-tests";

const VARIANT: Record<RunStatus, "success" | "destructive" | "warning" | "secondary"> = {
  PASSED: "success",
  FAILED: "destructive",
  ERROR: "destructive",
  ABORTED: "warning",
  CANCELLED: "warning",
  RUNNING: "secondary",
};

const ICON = {
  PASSED: CheckCircle2,
  FAILED: XCircle,
  ERROR: OctagonX,
  ABORTED: AlertTriangle,
  CANCELLED: Ban,
  RUNNING: Loader2,
} as const;

export function RunStatusBadge({ status }: { status: RunStatus }) {
  const { t } = useTranslation();
  const Icon = ICON[status];
  const label = {
    PASSED: t("agentTests.status.PASSED", "Passed"),
    FAILED: t("agentTests.status.FAILED", "Failed"),
    ERROR: t("agentTests.status.ERROR", "Error"),
    ABORTED: t("agentTests.status.ABORTED", "Aborted"),
    CANCELLED: t("agentTests.status.CANCELLED", "Cancelled"),
    RUNNING: t("agentTests.status.RUNNING", "Running"),
  }[status];
  return (
    <Badge variant={VARIANT[status]} className="gap-1" data-testid={`run-status-${status}`}>
      <Icon className={status === "RUNNING" ? "h-3 w-3 animate-spin" : "h-3 w-3"} aria-hidden="true" />
      {label}
    </Badge>
  );
}

/**
 * What a non-green outcome means and what to do about it. ERROR, ABORTED and CANCELLED are
 * not test failures — nothing about the agent was judged — so they say so, and
 * name the next step, rather than showing only the raw `error` text.
 */
export function RunOutcomeNotice({ run }: { run: AgentTestRun }) {
  const { t } = useTranslation();

  if (run.status === "ABORTED") {
    return (
      <Notice tone="warning" testId="run-notice-aborted" title={t("agentTests.notice.abortedTitle", "This run was aborted — the agent was not judged")}>
        <p>
          {t(
            "agentTests.notice.abortedBody",
            "The node running this test stopped before it finished, so the run was closed and its test conversations were deleted. Start the run again."
          )}
        </p>
        {run.error && <Reason text={run.error} />}
      </Notice>
    );
  }

  if (run.status === "CANCELLED") {
    return (
      <Notice tone="warning" testId="run-notice-cancelled" title={t("agentTests.notice.cancelledTitle", "This run was cancelled — the agent was not fully judged")}>
        <p>
          {t(
            "agentTests.notice.cancelledBody",
            "The run was stopped before it finished. Cases that completed keep their results; the rest count as failed. Start the run again for a full verdict."
          )}
        </p>
        {run.error && <Reason text={run.error} />}
      </Notice>
    );
  }

  if (run.status === "ERROR") {
    return (
      <Notice tone="danger" testId="run-notice-error" title={t("agentTests.notice.errorTitle", "The run could not execute — the agent was not judged")}>
        <p>
          {t(
            "agentTests.notice.errorBody",
            "Something stopped the harness itself, not an expectation. Fix the cause below (for example the deployment to the test environment) and run again."
          )}
        </p>
        {run.error && <Reason text={run.error} />}
      </Notice>
    );
  }

  if (run.costCeilingReached) {
    return (
      <Notice tone="warning" testId="run-notice-ceiling" title={t("agentTests.notice.ceilingTitle", "The cost ceiling stopped this run")}>
        <p>
          {t(
            "agentTests.notice.ceilingBody",
            "The summed cost of all turns reached the suite's ceiling, so the remaining turns never ran and the run counts as failed. Raise “Cost ceiling per run” in the suite, or shorten it."
          )}
        </p>
      </Notice>
    );
  }
  return null;
}

function Reason({ text }: { text: string }) {
  const { t } = useTranslation();
  return (
    <p className="mt-2 break-words rounded bg-background/60 px-2 py-1 font-mono text-xs" data-testid="run-notice-reason">
      <span className="font-sans font-semibold">{t("agentTests.notice.reason", "Reason:")}</span> {text}
    </p>
  );
}

function Notice({
  tone,
  title,
  testId,
  children,
}: {
  tone: "warning" | "danger";
  title: string;
  testId: string;
  children: React.ReactNode;
}) {
  const classes =
    tone === "danger"
      ? "border-destructive/40 bg-destructive/10"
      : "border-amber-500/40 bg-amber-500/10";
  const Icon = tone === "danger" ? OctagonX : AlertTriangle;
  return (
    <div role="status" className={`rounded-lg border p-3 text-sm ${classes}`} data-testid={testId}>
      <p className="flex items-center gap-2 font-semibold text-foreground">
        <Icon className="h-4 w-4 shrink-0" aria-hidden="true" />
        {title}
      </p>
      <div className="mt-1 text-xs text-muted-foreground">{children}</div>
    </div>
  );
}
