import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { AlertTriangle, Ban, CheckCircle2, ChevronDown, ChevronRight, Loader2, XCircle } from "lucide-react";
import { ClusterDrawer } from "@/components/cluster/cluster-drawer";
import { Button } from "@/components/ui/button";
import { useAgentTestRun, useCancelAgentTestRun } from "@/hooks/use-agent-tests";
import { getErrorMessage } from "@/lib/api-client";
import { formatDuration, formatUsd } from "@/lib/utils";
import { renderValue } from "@/lib/agent-test-utils";
import type {
  AgentTestRun,
  AttemptResult,
  CaseResult,
  ExpectationFailure,
  TurnResult,
} from "@/lib/api/agent-tests";
import { RunOutcomeNotice, RunStatusBadge } from "./run-status";

function StatusIcon({ passed }: { passed: boolean }) {
  const { t } = useTranslation();
  return passed ? (
    <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-600" aria-label={t("agentTests.result.passedIcon", "Passed")} />
  ) : (
    <XCircle className="h-4 w-4 shrink-0 text-destructive" aria-label={t("agentTests.result.failedIcon", "Failed")} />
  );
}

// ─── Failure: expected vs actual ────────────────────────────────────────────

function FailureRow({ failure }: { failure: ExpectationFailure }) {
  const { t } = useTranslation();
  const unmocked = failure.expectation === "unmockedTool";
  return (
    <div
      className={`rounded-md border p-2 ${unmocked ? "border-amber-500/50 bg-amber-500/10" : "border-destructive/30 bg-destructive/5"}`}
      data-testid="expectation-failure"
      data-failure={failure.expectation}
    >
      <p className="mb-1.5 flex items-center gap-1.5 text-xs font-semibold text-foreground">
        {unmocked && <AlertTriangle className="h-3.5 w-3.5 text-amber-600" aria-hidden="true" />}
        <code className="rounded bg-background/70 px-1">{failure.expectation}</code>
        {unmocked && (
          <span className="font-normal text-muted-foreground">
            {t("agentTests.result.unmockedExplain", "A tool was called without a mock — it was not executed and the case fails.")}
          </span>
        )}
      </p>
      <div className="grid gap-2 sm:grid-cols-2">
        <div>
          <p className="mb-0.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
            {t("agentTests.result.expected", "Expected")}
          </p>
          <pre
            className="max-h-48 overflow-auto whitespace-pre-wrap break-words rounded bg-background p-2 font-mono text-xs"
            data-testid="failure-expected"
          >
            {renderValue(failure.expected)}
          </pre>
        </div>
        <div>
          <p className="mb-0.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
            {t("agentTests.result.actual", "Actual")}
          </p>
          <pre
            className="max-h-48 overflow-auto whitespace-pre-wrap break-words rounded bg-background p-2 font-mono text-xs"
            data-testid="failure-actual"
          >
            {failure.actual === null || failure.actual === undefined ? t("agentTests.result.nothing", "(nothing)") : renderValue(failure.actual)}
          </pre>
        </div>
      </div>
    </div>
  );
}

// ─── Turn ───────────────────────────────────────────────────────────────────

function TurnRow({ turn, unmocked }: { turn: TurnResult; unmocked: string[] }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(!turn.passed);
  const failures = turn.failures ?? [];
  // Tools this turn called that the run refused for lack of a mock.
  const refused = new Set(
    failures.filter((f) => f.expectation === "unmockedTool").map((f) => String(f.expected).replace(/^a mock for '(.*)'$/, "$1"))
  );

  return (
    <li className="rounded-lg border border-border bg-background/50" data-testid="result-turn" data-passed={turn.passed}>
      <button
        type="button"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        className="flex w-full items-center gap-2 p-2 text-start"
      >
        {open ? <ChevronDown className="h-3.5 w-3.5 shrink-0" aria-hidden="true" /> : <ChevronRight className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />}
        <StatusIcon passed={turn.passed} />
        <span className="shrink-0 text-xs font-semibold text-muted-foreground">
          {t("agentTests.result.turn", { index: turn.index, defaultValue: "Turn {{index}}" })}
        </span>
        <span className="min-w-0 flex-1 truncate text-xs text-foreground">“{turn.input}”</span>
        <span className="shrink-0 text-[11px] text-muted-foreground">
          {formatUsd(turn.costUsd)} · {formatDuration(turn.durationMs)}
        </span>
      </button>
      {open && (
        <div className="space-y-2 border-t border-border p-2">
          {failures.map((f, i) => (
            <FailureRow key={i} failure={f} />
          ))}
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
            <dt className="text-muted-foreground">{t("agentTests.result.output", "Output")}</dt>
            <dd className="break-words text-foreground" data-testid="turn-output">
              {turn.output || <em className="text-muted-foreground">{t("agentTests.result.noOutput", "(none)")}</em>}
            </dd>
            <dt className="text-muted-foreground">{t("agentTests.result.actions", "Actions")}</dt>
            <dd className="flex flex-wrap gap-1">
              {(turn.actions ?? []).length === 0 ? (
                <em className="text-muted-foreground">{t("agentTests.result.noActions", "(none)")}</em>
              ) : (
                turn.actions!.map((a, i) => (
                  <code key={`${a}-${i}`} className="rounded bg-secondary px-1">
                    {a}
                  </code>
                ))
              )}
            </dd>
            <dt className="text-muted-foreground">{t("agentTests.result.toolCalls", "Tool calls")}</dt>
            <dd className="flex flex-wrap gap-1" data-testid="turn-tool-calls">
              {(turn.toolCalls ?? []).length === 0 ? (
                <em className="text-muted-foreground">{t("agentTests.result.noToolCalls", "(none)")}</em>
              ) : (
                turn.toolCalls!.map((c, i) => {
                  const isUnmocked = refused.has(c) || unmocked.includes(c);
                  return (
                    <code
                      key={`${c}-${i}`}
                      className={
                        isUnmocked
                          ? "inline-flex items-center gap-1 rounded bg-amber-500/20 px-1 font-semibold text-amber-800 ring-1 ring-amber-500/50 dark:text-amber-300"
                          : "rounded bg-secondary px-1"
                      }
                      data-unmocked={isUnmocked || undefined}
                      title={isUnmocked ? t("agentTests.result.unmockedTool", "Called without a mock — not executed") : undefined}
                    >
                      {isUnmocked && <AlertTriangle className="h-3 w-3" aria-hidden="true" />}
                      {c}
                    </code>
                  );
                })
              )}
            </dd>
            {turn.conversationState && (
              <>
                <dt className="text-muted-foreground">{t("agentTests.result.state", "State after")}</dt>
                <dd>
                  <code className="rounded bg-secondary px-1">{turn.conversationState}</code>
                </dd>
              </>
            )}
          </dl>
        </div>
      )}
    </li>
  );
}

// ─── Attempt / case ─────────────────────────────────────────────────────────

function AttemptBlock({ attempt, showHeader }: { attempt: AttemptResult; showHeader: boolean }) {
  const { t } = useTranslation();
  const unmocked = attempt.unmockedToolCalls ?? [];
  return (
    <div className="space-y-2" data-testid="result-attempt" data-passed={attempt.passed}>
      {showHeader && (
        <p className="flex items-center gap-2 text-xs font-medium text-foreground">
          <StatusIcon passed={attempt.passed} />
          {t("agentTests.result.repetition", { index: attempt.repetition, defaultValue: "Repetition {{index}}" })}
          <span className="font-normal text-muted-foreground">
            {formatUsd(attempt.costUsd)} · {formatDuration(attempt.durationMs)}
          </span>
        </p>
      )}
      {attempt.error && (
        <p className="rounded-md border border-destructive/30 bg-destructive/5 p-2 text-xs text-foreground" data-testid="attempt-error">
          {attempt.error}
        </p>
      )}
      {unmocked.length > 0 && (
        <p
          className="flex flex-wrap items-center gap-1.5 rounded-md border border-amber-500/50 bg-amber-500/10 p-2 text-xs text-foreground"
          data-testid="attempt-unmocked"
        >
          <AlertTriangle className="h-3.5 w-3.5 text-amber-600" aria-hidden="true" />
          {t("agentTests.result.unmockedCalls", "Tool calls with no mock (not executed):")}
          {unmocked.map((u, i) => (
            <code key={`${u}-${i}`} className="rounded bg-amber-500/20 px-1 font-semibold">
              {u}
            </code>
          ))}
        </p>
      )}
      <ol className="space-y-1.5">
        {(attempt.turns ?? []).map((turn) => (
          <TurnRow key={turn.index} turn={turn} unmocked={unmocked} />
        ))}
      </ol>
    </div>
  );
}

function CaseBlock({ result }: { result: CaseResult }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(!result.passed);
  const attempts = result.attempts ?? [];
  // A passing case with repetitions still shows every attempt, so a flaky
  // 4-of-5 is visible; failing attempts come first.
  const ordered = [...attempts].sort((a, b) => Number(a.passed) - Number(b.passed) || a.repetition - b.repetition);
  const needed = Math.ceil(result.passRate * result.repetitions - 1e-9);

  return (
    <section className="rounded-xl border border-border bg-card" data-testid="result-case" data-passed={result.passed}>
      <button
        type="button"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        className="flex w-full items-center gap-2 p-3 text-start"
      >
        {open ? <ChevronDown className="h-4 w-4 shrink-0" aria-hidden="true" /> : <ChevronRight className="h-4 w-4 shrink-0" aria-hidden="true" />}
        <StatusIcon passed={result.passed} />
        <span className="min-w-0 flex-1 truncate text-sm font-semibold text-foreground">{result.name}</span>
        <span className="shrink-0 text-xs text-muted-foreground" data-testid="case-pass-count">
          {t("agentTests.result.repetitionsPassed", {
            passed: result.passedRepetitions,
            total: result.repetitions,
            needed,
            defaultValue: "{{passed}}/{{total}} passed (need {{needed}})",
          })}
        </span>
      </button>
      {open && (
        <div className="space-y-4 border-t border-border p-3">
          {ordered.length === 0 && (
            <p className="text-xs italic text-muted-foreground">{t("agentTests.result.noAttempts", "No conversation was recorded for this case.")}</p>
          )}
          {ordered.map((a) => (
            <AttemptBlock key={a.repetition} attempt={a} showHeader={result.repetitions > 1 || attempts.length > 1} />
          ))}
        </div>
      )}
    </section>
  );
}

// ─── Sheet ──────────────────────────────────────────────────────────────────

function Stat({ label, value, testId }: { label: string; value: React.ReactNode; testId?: string }) {
  return (
    <div className="rounded-lg bg-secondary/60 p-2.5">
      <p className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">{label}</p>
      <p className="mt-0.5 text-sm font-semibold text-foreground" data-testid={testId}>
        {value}
      </p>
    </div>
  );
}

function announcesItself(run: AgentTestRun): boolean {
  return run.status === "ABORTED" || run.status === "ERROR" || run.status === "CANCELLED" || run.costCeilingReached === true;
}

export function RunResultBody({ run }: { run: AgentTestRun }) {
  const { t } = useTranslation();
  const cases = run.cases ?? [];
  return (
    <div className="space-y-4" data-testid="run-result">
      <div className="flex flex-wrap items-center gap-2">
        {/* ABORTED/ERROR/CANCELLED/ceiling are announced by their notice; the rest here. */}
        <span role={announcesItself(run) ? undefined : "status"}>
          <RunStatusBadge status={run.status} />
        </span>
        <span className="text-xs text-muted-foreground">
          {run.suiteName ?? run.suiteId}
          {run.suiteVersion !== undefined && ` · ${t("agentTests.suiteVersion", { version: run.suiteVersion, defaultValue: "suite v{{version}}" })}`}
          {run.agentVersion !== undefined && ` · ${t("agentTests.agentVersion", { version: run.agentVersion, defaultValue: "agent v{{version}}" })}`}
        </span>
      </div>

      <RunOutcomeNotice run={run} />

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <Stat
          label={t("agentTests.result.cases", "Conversations")}
          value={`${run.casesPassed ?? 0}/${run.casesTotal ?? cases.length}`}
          testId="stat-cases"
        />
        <Stat
          label={t("agentTests.result.cost", "Cost")}
          value={
            run.maxCostPerRun !== undefined
              ? `${formatUsd(run.totalCostUsd ?? 0)} / ${formatUsd(run.maxCostPerRun)}`
              : formatUsd(run.totalCostUsd ?? 0)
          }
          testId="stat-cost"
        />
        <Stat
          label={t("agentTests.result.duration", "Duration")}
          value={run.durationMs !== undefined ? formatDuration(run.durationMs) : "—"}
          testId="stat-duration"
        />
        <Stat label={t("agentTests.result.models", "Models")} value={(run.models ?? []).join(", ") || "—"} />
      </div>

      {run.status === "RUNNING" && (
        <div className="flex flex-wrap items-center gap-3">
          <p className="flex items-center gap-2 text-sm text-muted-foreground" data-testid="run-in-progress">
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
            {t("agentTests.result.inProgress", "Running — results appear here when the run finishes.")}
          </p>
          <CancelRunButton runId={run.id} />
        </div>
      )}

      <div className="space-y-2">
        {cases.map((c, i) => (
          <CaseBlock key={`${c.name}-${i}`} result={c} />
        ))}
      </div>
    </div>
  );
}

/**
 * Stops a RUNNING run. The backend only flags it: the run ends after the turn in
 * flight is abandoned and reads CANCELLED on the next poll, so the button stays
 * disabled from the click until then.
 */
function CancelRunButton({ runId }: { runId: string }) {
  const { t } = useTranslation();
  const cancel = useCancelAgentTestRun();
  const requested = cancel.isPending || cancel.isSuccess;
  return (
    <Button
      variant="outline"
      size="sm"
      disabled={requested}
      onClick={() =>
        cancel.mutate(runId, {
          onSuccess: () => toast.success(t("agentTests.cancelRequested", "Cancellation requested — the run stops in a moment.")),
          // 409: already finished, or running on another node — the message says which.
          onError: (err) => toast.error(getErrorMessage(err)),
        })
      }
      data-testid="run-cancel-btn"
    >
      {cancel.isPending ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> : <Ban className="h-4 w-4" aria-hidden="true" />}
      {t("agentTests.cancel", "Cancel run")}
    </Button>
  );
}

/** A run in a side sheet: fetched by id and kept live while it is RUNNING. */
export function RunResultSheet({
  runId,
  onClose,
  fallback,
}: {
  runId: string | null;
  onClose: () => void;
  /** The list's copy of the run, shown until the full record loads. */
  fallback?: AgentTestRun;
}) {
  const { t } = useTranslation();
  const { data, isError, error } = useAgentTestRun(runId);
  const run = data ?? fallback;
  return (
    <ClusterDrawer
      open={runId !== null}
      onClose={onClose}
      title={t("agentTests.result.title", "Test run")}
      testId="run-result-sheet"
      widthClass="max-w-3xl"
    >
      {run ? (
        <RunResultBody run={run} />
      ) : isError ? (
        <p className="text-sm text-destructive" role="alert">
          {error instanceof Error ? error.message : t("common.error", "Error")}
        </p>
      ) : (
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" aria-label={t("common.loading", "Loading")} />
      )}
    </ClusterDrawer>
  );
}
