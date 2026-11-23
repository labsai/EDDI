import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import {
  AlertCircle,
  Ban,
  CalendarClock,
  CheckCircle2,
  ChevronDown,
  Hand,
  ListChecks,
  Loader2,
  PauseCircle,
  Repeat,
} from "lucide-react";
import { cn, formatUsd } from "@eddi/app-kit/lib/utils";
import { Badge } from "@eddi/ui";
import { Button } from "@eddi/ui";
import type { IngestionRun, SourceWork } from "@/lib/api/ingestion-sources";
import { formatUpcomingTime } from "@/lib/date-format";

/** How many runs the history shows before "Show more". */
const COLLAPSED_RUNS = 5;

/**
 * A run in flight, with the counters its last progress write recorded.
 *
 * The backend writes them every couple of seconds while the run works, and the
 * history is polled meanwhile — so this moves, where it used to be a spinner
 * until the run ended.
 */
export function RunProgress({
  run,
  expectedTotal,
  expectedTotalIsEstimate,
  timeBudgetMinutes,
  testId,
}: {
  run: IngestionRun;
  /**
   * How many documents the run will look at, when that can be known (the files
   * of an upload source) or estimated (what the previous run saw). Absent means
   * the bar runs indeterminate rather than inventing a total.
   */
  expectedTotal?: number;
  expectedTotalIsEstimate?: boolean;
  timeBudgetMinutes?: number;
  testId: string;
}) {
  const { t } = useTranslation();
  const now = useNow(1000);
  const startedAt = run.startedAt ? Date.parse(run.startedAt) : NaN;
  const elapsedMs = Number.isFinite(startedAt) ? Math.max(0, now - startedAt) : undefined;

  const total = expectedTotal && expectedTotal > 0 ? expectedTotal : undefined;
  const fraction = total ? Math.min(1, run.documentsSeen / total) : undefined;

  return (
    <div
      className="space-y-2 rounded-lg border border-primary/40 bg-primary/5 p-3 text-xs"
      data-testid={`${testId}-progress`}
      aria-live="polite"
    >
      <div className="flex flex-wrap items-center gap-2">
        <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-primary" aria-hidden="true" />
        <span className="font-semibold text-foreground">
          {t("ragEditor.runs.inProgress", "Run in progress")}
        </span>
        <TriggerBadge trigger={run.trigger} />
        {elapsedMs !== undefined && (
          <span className="ms-auto text-muted-foreground" data-testid={`${testId}-progress-elapsed`}>
            {timeBudgetMinutes
              ? t("ragEditor.runs.elapsedOfBudget", "{{elapsed}} of a {{budget}} min budget", {
                  elapsed: formatElapsed(t, elapsedMs),
                  budget: timeBudgetMinutes,
                })
              : formatElapsed(t, elapsedMs)}
          </span>
        )}
      </div>

      <div
        className="h-1.5 w-full overflow-hidden rounded-full bg-muted"
        role="progressbar"
        aria-label={t("ragEditor.runs.progressLabel", "Ingestion progress")}
        aria-valuemin={0}
        aria-valuemax={total}
        aria-valuenow={total ? Math.min(run.documentsSeen, total) : undefined}
      >
        {fraction !== undefined ? (
          <div
            className="h-full rounded-full bg-primary transition-[width] duration-500"
            style={{ width: `${Math.max(2, fraction * 100)}%` }}
            data-testid={`${testId}-progress-bar`}
          />
        ) : (
          <div className="h-full w-1/3 animate-pulse rounded-full bg-primary/70" />
        )}
      </div>

      <p className="text-muted-foreground" data-testid={`${testId}-progress-summary`}>
        {total
          ? expectedTotalIsEstimate
            ? t(
                "ragEditor.runs.progressOfEstimate",
                "{{seen}} of about {{total}} documents looked at (the last run saw {{total}})",
                { seen: run.documentsSeen, total },
              )
            : t("ragEditor.runs.progressOfTotal", "{{seen}} of {{total}} documents looked at", {
                seen: run.documentsSeen,
                total,
              })
          : t("ragEditor.runs.progressSeen", "{{seen}} documents looked at so far", {
              seen: run.documentsSeen,
            })}
      </p>

      <RunCounters run={run} />
    </div>
  );
}

/**
 * The source's past runs, newest first. Each row opens to everything the run
 * recorded — the summary line alone hid failures, deletions and cost.
 */
export function RunHistory({
  runs,
  isLoading,
  testId,
}: {
  runs: IngestionRun[];
  isLoading: boolean;
  testId: string;
}) {
  const { t } = useTranslation();
  const [showAll, setShowAll] = useState(false);
  const [open, setOpen] = useState<string | null>(null);

  if (isLoading) {
    return <p className="text-xs text-muted-foreground">{t("common.loading", "Loading…")}</p>;
  }
  if (runs.length === 0) {
    return (
      <p className="text-xs italic text-muted-foreground" data-testid={`${testId}-no-runs`}>
        {t("ragEditor.sources.noRuns", "This source has not run yet.")}
      </p>
    );
  }

  const visible = showAll ? runs : runs.slice(0, COLLAPSED_RUNS);
  return (
    <div className="space-y-1" data-testid={`${testId}-runs`}>
      <span className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        {t("ragEditor.sources.history", "Recent runs")}
      </span>
      {visible.map((run) => {
        const isOpen = open === run.runId;
        return (
          <div
            key={run.runId}
            className="rounded-md border border-border/60 text-xs"
            data-testid={`${testId}-run-${run.runId}`}
          >
            <button
              type="button"
              className="flex w-full flex-wrap items-center gap-2 px-2 py-1.5 text-start hover:bg-secondary/60"
              aria-expanded={isOpen}
              onClick={() => setOpen(isOpen ? null : run.runId)}
              data-testid={`${testId}-run-${run.runId}-toggle`}
            >
              <StatusIcon run={run} />
              <span className="text-muted-foreground">
                {run.startedAt ? new Date(run.startedAt).toLocaleString() : "—"}
              </span>
              <TriggerBadge trigger={run.trigger} />
              <span className="ms-auto text-muted-foreground">{summaryOf(t, run)}</span>
              <ChevronDown
                className={cn(
                  "h-3 w-3 shrink-0 text-muted-foreground transition-transform",
                  isOpen && "rotate-180",
                )}
                aria-hidden="true"
              />
            </button>
            {isOpen && <RunDetails run={run} testId={`${testId}-run-${run.runId}`} />}
          </div>
        );
      })}
      {runs.length > COLLAPSED_RUNS && (
        <Button
          variant="link"
          size="sm"
          onClick={() => setShowAll(!showAll)}
          data-testid={`${testId}-runs-more`}
        >
          {showAll
            ? t("ragEditor.runs.showFewer", "Show fewer")
            : t("ragEditor.runs.showAll", "Show all {{total}} runs", { total: runs.length })}
        </Button>
      )}
    </div>
  );
}

function RunDetails({ run, testId }: { run: IngestionRun; testId: string }) {
  const { t } = useTranslation();
  const started = run.startedAt ? Date.parse(run.startedAt) : NaN;
  const finished = run.finishedAt ? Date.parse(run.finishedAt) : NaN;
  const duration =
    Number.isFinite(started) && Number.isFinite(finished) ? finished - started : undefined;

  return (
    <div className="space-y-2 border-t border-border/60 px-2 py-2" data-testid={`${testId}-details`}>
      <RunCounters run={run} />
      <dl className="grid grid-cols-1 gap-x-4 gap-y-1 text-muted-foreground sm:grid-cols-2">
        {duration !== undefined && (
          <Detail label={t("ragEditor.runs.duration", "Duration")} value={formatElapsed(t, duration)} />
        )}
        {run.finishedAt && (
          <Detail
            label={t("ragEditor.runs.finishedAt", "Finished")}
            value={new Date(run.finishedAt).toLocaleString()}
          />
        )}
        {run.costUsd > 0 && (
          <Detail label={t("ragEditor.runs.cost", "Estimated cost")} value={formatUsd(run.costUsd)} />
        )}
        {run.stopReason && (
          <Detail
            label={t("ragEditor.runs.stopReason", "How it ended")}
            value={stopReasonText(t, run.stopReason, run.resumeAfter)}
            testId={`${testId}-stop-reason`}
          />
        )}
      </dl>
      {run.error && (
        <p
          className="flex items-start gap-1.5 text-[11px] text-destructive"
          role="alert"
          data-testid={`${testId}-error`}
        >
          <AlertCircle className="mt-0.5 h-3 w-3 shrink-0" />
          {run.error}
        </p>
      )}
    </div>
  );
}

/** Every counter a run records, as one compact grid. */
function RunCounters({ run }: { run: IngestionRun }) {
  const { t } = useTranslation();
  return (
    <dl className="grid grid-cols-3 gap-x-4 gap-y-1 text-muted-foreground sm:grid-cols-4 md:grid-cols-7">
      <Counter label={t("ragEditor.sources.statSeen", "Seen")} value={run.documentsSeen} />
      <Counter label={t("ragEditor.runs.ingested", "Ingested")} value={run.documentsIngested} />
      <Counter label={t("ragEditor.sources.statUnchanged", "Unchanged")} value={run.documentsUnchanged} />
      <Counter label={t("ragEditor.runs.skipped", "Skipped")} value={run.documentsSkipped ?? 0} />
      <Counter
        label={t("ragEditor.sources.statFailed", "Failed")}
        value={run.documentsFailed}
        tone={run.documentsFailed > 0 ? "error" : undefined}
      />
      <Counter label={t("ragEditor.runs.removed", "Removed")} value={run.documentsTombstoned} />
      <Counter label={t("ragEditor.runs.chunks", "Chunks stored")} value={run.segmentsStored} />
      {(run.documentsThrottled ?? 0) > 0 && (
        <Counter
          label={t("ragEditor.runs.throttled", "Waiting (rate limit)")}
          value={run.documentsThrottled ?? 0}
        />
      )}
    </dl>
  );
}

function Counter({ label, value, tone }: { label: string; value: number; tone?: "error" }) {
  return (
    <div>
      <dt className="text-[10px] uppercase tracking-wide">{label}</dt>
      <dd className={cn("font-semibold text-foreground", tone === "error" && "text-destructive")}>
        {value}
      </dd>
    </div>
  );
}

function Detail({ label, value, testId }: { label: string; value: string; testId?: string }) {
  return (
    <div className="flex gap-2">
      <dt>{label}:</dt>
      <dd className="text-foreground" data-testid={testId}>
        {value}
      </dd>
    </div>
  );
}

function StatusIcon({ run }: { run: IngestionRun }) {
  if (run.status === "RUNNING") {
    return <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-muted-foreground" />;
  }
  if (run.status === "CANCELLED") {
    return <Ban className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />;
  }
  if (run.status === "COMPLETED" && run.stopReason === "THROTTLED") {
    // Paused by the provider, not failed: the rest waits for the next run.
    return <PauseCircle className="h-3.5 w-3.5 shrink-0 text-warn-600 dark:text-warn-400" />;
  }
  if (run.status === "COMPLETED") {
    // A completed run that lost documents is not the same green tick as a clean one.
    return run.documentsFailed > 0 ? (
      <AlertCircle className="h-3.5 w-3.5 shrink-0 text-warn-600 dark:text-warn-400" />
    ) : (
      <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-emerald-600 dark:text-emerald-400" />
    );
  }
  return <AlertCircle className="h-3.5 w-3.5 shrink-0 text-destructive" />;
}

function TriggerBadge({ trigger }: { trigger?: IngestionRun["trigger"] }) {
  const { t } = useTranslation();
  if (trigger === "SCHEDULED") {
    return (
      <Badge variant="outline" className="gap-1 py-0 text-[10px]">
        <CalendarClock className="h-3 w-3" aria-hidden="true" />
        {t("ragEditor.runs.triggerScheduled", "Scheduled")}
      </Badge>
    );
  }
  if (trigger === "MANUAL") {
    return (
      <Badge variant="outline" className="gap-1 py-0 text-[10px]">
        <Hand className="h-3 w-3" aria-hidden="true" />
        {t("ragEditor.runs.triggerManual", "Manual")}
      </Badge>
    );
  }
  if (trigger === "CONTINUATION") {
    // A run that drained what an earlier listing queued, and listed nothing.
    return (
      <Badge variant="outline" className="gap-1 py-0 text-[10px]">
        <Repeat className="h-3 w-3" aria-hidden="true" />
        {t("ragEditor.runs.triggerContinuation", "Continued")}
      </Badge>
    );
  }
  return null;
}

/**
 * What the source's work table holds: the documents its listing queued, by
 * state. Shown only while there is something to say — nothing for a source
 * that never queued anything, or on a server older than the work table.
 */
export function WorkQueueStatus({
  work,
  canRetry,
  onRetryFailed,
  retrying,
  retryError,
  testId,
}: {
  work: SourceWork | undefined;
  canRetry: boolean;
  onRetryFailed: () => void;
  retrying: boolean;
  retryError: boolean;
  testId: string;
}) {
  const { t } = useTranslation();
  if (!work) {
    return null;
  }
  const total = work.queued + work.deferred + work.inFlight + work.done + work.failed;
  if (total === 0) {
    return null;
  }
  const parts: { key: string; text: string; tone?: string }[] = [
    { key: "queued", text: t("ragEditor.runs.workQueued", "{{n}} queued", { n: work.queued }) },
    { key: "inFlight", text: t("ragEditor.runs.workInFlight", "{{n}} in progress", { n: work.inFlight }) },
    { key: "deferred", text: t("ragEditor.runs.workDeferred", "{{n}} waiting", { n: work.deferred }) },
    { key: "done", text: t("ragEditor.runs.workDone", "{{n}} done", { n: work.done }) },
    {
      key: "failed",
      text: t("ragEditor.runs.workFailed", "{{n}} failed", { n: work.failed }),
      tone: work.failed > 0 ? "text-destructive" : undefined,
    },
  ];
  const next = work.nextAttemptAt ? Date.parse(work.nextAttemptAt) : NaN;
  return (
    <div className="space-y-1.5 rounded-md border border-border px-3 py-2" data-testid={`${testId}-work`}>
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs">
        <ListChecks className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
        <span className="font-medium text-foreground">{t("ragEditor.runs.workTitle", "Work queue")}</span>
        {parts.map((part) => (
          <span
            key={part.key}
            className={cn("text-muted-foreground", part.tone)}
            data-testid={`${testId}-work-${part.key}`}
          >
            {part.text}
          </span>
        ))}
        {Number.isFinite(next) && work.deferred > 0 && (
          <span className="text-muted-foreground" data-testid={`${testId}-work-next`}>
            {t("ragEditor.runs.workNextAttempt", "next attempt {{time}}", { time: formatUpcomingTime(next) })}
          </span>
        )}
        {canRetry && work.failed > 0 && (
          <Button
            variant="outline"
            size="sm"
            className="ms-auto h-6 px-2 text-xs"
            onClick={onRetryFailed}
            disabled={retrying}
            data-testid={`${testId}-work-retry`}
          >
            {t("ragEditor.runs.workRetryFailed", "Retry failed")}
          </Button>
        )}
      </div>
      <p className="text-[11px] text-muted-foreground">
        {t(
          "ragEditor.runs.workHint",
          "Documents the last listing found, embedded in the background. A rate limit or an error makes a document wait; it is never failed or removed for that.",
        )}
      </p>
      {retryError && (
        <p className="text-[11px] text-destructive" data-testid={`${testId}-work-retry-error`}>
          {t("ragEditor.runs.workRetryFailedError", "Could not queue the failed documents again.")}
        </p>
      )}
    </div>
  );
}

function summaryOf(t: TFunction, run: IngestionRun): string {
  const base = t("ragEditor.sources.runSummary", "{{ingested}} ingested · {{unchanged}} unchanged", {
    ingested: run.documentsIngested,
    unchanged: run.documentsUnchanged,
  });
  const withFailed =
    run.documentsFailed > 0
      ? `${base} · ${t("ragEditor.runs.failedCount", "{{failed}} failed", { failed: run.documentsFailed })}`
      : base;
  const waiting = run.documentsThrottled ?? 0;
  return waiting > 0
    ? `${withFailed} · ${t("ragEditor.runs.throttledCount", "{{waiting}} waiting", { waiting })}`
    : withFailed;
}

/** The crawler's stop reasons, said as what they mean for the knowledge base. */
function stopReasonText(t: TFunction, reason: string, resumeAfter?: string | null): string {
  switch (reason) {
    case "COMPLETED":
      return t("ragEditor.runs.stopCompleted", "Covered the whole source");
    case "PAGE_LIMIT":
      return t("ragEditor.runs.stopPageLimit", "Stopped at the page limit — nothing was treated as deleted");
    case "FETCH_LIMIT":
      return t("ragEditor.runs.stopFetchLimit", "Stopped at the fetch limit — nothing was treated as deleted");
    case "BYTE_LIMIT":
      return t("ragEditor.runs.stopByteLimit", "Stopped at the size limit — nothing was treated as deleted");
    case "TIME_LIMIT":
      return t("ragEditor.runs.stopTimeLimit", "Stopped at its time budget — nothing was treated as deleted");
    case "CANCELLED":
      return t(
        "ragEditor.runs.stopCancelled",
        "Stopped early — cancelled, superseded, or its chunk budget was reached",
      );
    case "THROTTLED": {
      // No automatic resume: the next run is the source's schedule, which waits for
      // this time, or a manual one. The date is shown when it is not today: a daily
      // quota usually comes back tomorrow.
      const resume = resumeAfter ? Date.parse(resumeAfter) : NaN;
      return Number.isFinite(resume)
        ? t(
            "ragEditor.runs.stopThrottledUntil",
            "Paused by the provider's rate limit — it takes requests again from {{time}}. The next run picks up the rest; nothing was treated as failed or deleted",
            { time: formatUpcomingTime(resume) },
          )
        : t(
            "ragEditor.runs.stopThrottled",
            "Paused by the provider's quota. The next run picks up the rest; nothing was treated as failed or deleted",
          );
    }
    default:
      return reason;
  }
}

function formatElapsed(t: TFunction, ms: number): string {
  const totalSeconds = Math.round(ms / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) {
    return t("ragEditor.runs.hoursMinutes", "{{hours}} h {{minutes}} min", { hours, minutes });
  }
  if (minutes > 0) {
    return t("ragEditor.runs.minutesSeconds", "{{minutes}} min {{seconds}} s", { minutes, seconds });
  }
  return t("ragEditor.runs.seconds", "{{seconds}} s", { seconds });
}

/** The current time, re-read every `intervalMs` so an elapsed counter ticks. */
function useNow(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(id);
  }, [intervalMs]);
  return now;
}
