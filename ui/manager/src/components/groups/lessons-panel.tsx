import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { BookOpenCheck } from "lucide-react";
import { ErrorState } from "@/components/shared/error-state";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  useConsolidationState,
  useGroupLessons,
  useLessonHistory,
  useRunConsolidation,
  useScheduleConsolidation,
  useSharedLessons,
  useUnscheduleConsolidation,
} from "@/hooks/use-group-lessons";
import { getErrorMessage } from "@/lib/api-client";
import type { ConsolidationResult } from "@/lib/api/group-lessons";
import { listTimeZones } from "@/lib/api/schedules";

/** The viewer's own IANA zone, as the workspace cadence form defaults to, or UTC. */
function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  } catch {
    return "UTC";
  }
}

function timeZoneOptions(current: string): string[] {
  return [...new Set(["UTC", browserTimeZone(), current, ...listTimeZones()])].sort((a, b) =>
    a === "UTC" ? -1 : b === "UTC" ? 1 : a.localeCompare(b),
  );
}

const inputCls = "rounded-lg border border-input bg-background px-3 py-1.5 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring";

function Section({ title, testId, children }: { title: string; testId: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5" data-testid={testId}>
      <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">{title}</h4>
      {children}
    </div>
  );
}

/**
 * A team's retro lessons: its own, what other teams share with it, the
 * consolidation job (schedule, preview, run) and what consolidation removed.
 * Rendered only for a group whose configuration carries a `lessonConfig`.
 */
export function LessonsPanel({ groupId, readOnly }: { groupId: string; readOnly: boolean }) {
  const { t } = useTranslation();
  const own = useGroupLessons(groupId);
  const shared = useSharedLessons(groupId);
  const state = useConsolidationState(groupId);
  const [showHistory, setShowHistory] = useState(false);
  const history = useLessonHistory(groupId, showHistory);
  const schedule = useScheduleConsolidation(groupId);
  const unschedule = useUnscheduleConsolidation(groupId);
  const run = useRunConsolidation(groupId);
  const [cron, setCron] = useState("0 3 * * 1");
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ConsolidationResult | null>(null);

  const [timeZone, setTimeZone] = useState(browserTimeZone);
  const timeZones = useMemo(() => timeZoneOptions(timeZone), [timeZone]);

  function doRun(dryRun: boolean) {
    setError(null);
    run.mutate(dryRun, {
      onSuccess: (r) => {
        setResult(r);
        if (dryRun) return;
        // A skip (too few lessons, over the cost cap, a transient model failure) ran nothing.
        if (r.status === "SKIPPED") toast.warning(t("groupLessons.runSkipped", "Consolidation skipped: {{reason}}", { reason: r.message }));
        else toast.success(t("groupLessons.runDone", "Consolidation finished"));
      },
      onError: (err) => setError(getErrorMessage(err)),
    });
  }

  function doSchedule() {
    setError(null);
    schedule.mutate(
      { cronExpression: cron.trim(), timeZone: timeZone.trim() || "UTC" },
      { onSuccess: () => toast.success(t("groupLessons.scheduled", "Consolidation scheduled")), onError: (err) => setError(getErrorMessage(err)) },
    );
  }

  const ownLessons = own.data ?? [];
  const sharedGroups = shared.data?.groups ?? [];
  const notShared = shared.data?.notShared ?? [];
  const cs = state.data;
  const loadError = (testId: string, query: { error: unknown; refetch: () => unknown }) => (
    <div data-testid={testId}>
      <ErrorState
        message={t("groupLessons.loadError", "Could not load this section.")}
        error={query.error}
        onRetry={() => void query.refetch()}
        retryLabel={t("common.retry", "Retry")}
      />
    </div>
  );

  return (
    <div className="space-y-4 rounded-xl border border-border bg-card p-4" data-testid="workspace-lessons">
      <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
        <BookOpenCheck className="h-4 w-4 text-primary" />
        {t("groupLessons.title", "Team lessons ({{count}})", { count: ownLessons.length })}
      </h3>

      <Section title={t("groupLessons.ownTitle", "This team's lessons")} testId="lessons-own">
        {own.isError ? (
          loadError("lessons-own-error", own)
        ) : own.isPending ? null : ownLessons.length === 0 ? (
          <p className="text-xs text-muted-foreground" data-testid="lessons-own-empty">
            {t("groupLessons.ownEmpty", "No lessons yet. A RETRO phase stores them after a discussion.")}
          </p>
        ) : (
          <ul className="space-y-1">
            {ownLessons.map((lesson) => (
              <li key={lesson.key} className="text-xs text-foreground" data-testid={`lesson-${lesson.key}`}>
                {lesson.text}
              </li>
            ))}
          </ul>
        )}
      </Section>

      <Section title={t("groupLessons.sharedTitle", "Shared with this team")} testId="lessons-shared">
        {shared.isError && loadError("lessons-shared-error", shared)}
        {shared.isSuccess && sharedGroups.length === 0 && notShared.length === 0 && (
          <p className="text-xs text-muted-foreground" data-testid="lessons-shared-empty">
            {t("groupLessons.sharedEmpty", "Nothing is shared with this team. List teams in readFrom, and have them list this team in shareWith.")}
          </p>
        )}
        {sharedGroups.map((group) => (
          <div key={group.groupId} data-testid={`shared-group-${group.groupId}`}>
            <p className="text-xs font-medium text-foreground">{group.name}</p>
            <ul className="ps-3">
              {group.lessons.map((lesson) => (
                <li key={lesson.key} className="text-xs text-muted-foreground">
                  {lesson.text}
                </li>
              ))}
            </ul>
          </div>
        ))}
        {notShared.length > 0 && (
          <p className="text-xs text-warning" data-testid="lessons-not-shared">
            {t("groupLessons.notShared", "Not shared with this team: {{ids}}", { ids: notShared.join(", ") })}
          </p>
        )}
      </Section>

      <Section title={t("groupLessons.consolidationTitle", "Consolidation")} testId="lessons-consolidation">
        {state.isError ? (
          loadError("consolidation-error-state", state)
        ) : !cs ? null : !cs.configured ? (
          <p className="text-xs text-muted-foreground" data-testid="consolidation-off">
            {t("groupLessons.consolidationOff", "Not configured. Add lessonConfig.consolidation to the group configuration (a model and a cost cap).")}
          </p>
        ) : (
          <>
            <div className="flex flex-wrap items-center gap-2 text-xs">
              <Badge variant={cs.scheduled ? "success" : "secondary"} data-testid="consolidation-schedule-state">
                {cs.scheduled
                  ? t("groupLessons.scheduledAt", "Scheduled: {{cron}} ({{zone}})", { cron: cs.cronExpression, zone: cs.timeZone || "UTC" })
                  : t("groupLessons.notScheduled", "Not scheduled")}
              </Badge>
              {!cs.hasModel && (
                <Badge variant="warning" data-testid="consolidation-no-model">
                  {t("groupLessons.noModel", "No model configured")}
                </Badge>
              )}
            </div>
            {!readOnly && (
              <div className="flex flex-wrap items-center gap-2">
                <input
                  value={cron}
                  onChange={(e) => setCron(e.target.value)}
                  aria-label={t("groupLessons.cron", "Cron expression")}
                  className={`${inputCls} w-40 font-mono`}
                  data-testid="consolidation-cron"
                />
                <select
                  value={timeZone}
                  onChange={(e) => setTimeZone(e.target.value)}
                  aria-label={t("groupLessons.timeZone", "Time zone")}
                  title={t("groupLessons.timeZone", "Time zone")}
                  className={inputCls}
                  data-testid="consolidation-timezone"
                >
                  {timeZones.map((zone) => (
                    <option key={zone} value={zone}>
                      {zone}
                    </option>
                  ))}
                </select>
                <Button size="sm" onClick={doSchedule} disabled={!cron.trim() || schedule.isPending} data-testid="consolidation-schedule">
                  {t("groupLessons.schedule", "Schedule")}
                </Button>
                {cs.scheduled && (
                  <Button size="sm" variant="ghost" onClick={() => unschedule.mutate()} disabled={unschedule.isPending} data-testid="consolidation-unschedule">
                    {t("groupLessons.unschedule", "Remove schedule")}
                  </Button>
                )}
                <Button size="sm" variant="outline" onClick={() => doRun(true)} disabled={run.isPending} data-testid="consolidation-preview">
                  {t("groupLessons.preview", "Preview")}
                </Button>
                <Button size="sm" variant="outline" onClick={() => doRun(false)} disabled={run.isPending} data-testid="consolidation-run">
                  {t("groupLessons.runNow", "Run now")}
                </Button>
              </div>
            )}
            {error && (
              <p className="text-xs text-destructive" data-testid="consolidation-error">
                {error}
              </p>
            )}
            {result && (
              <div className="space-y-1 rounded-lg border border-border bg-secondary/20 p-2" data-testid="consolidation-result">
                <p className="text-xs text-foreground">{result.message}</p>
                <p className="text-[10px] text-muted-foreground">
                  {t("groupLessons.resultStats", "{{lessons}} lessons, {{outcomes}} outcomes considered, about ${{cost}}", {
                    lessons: result.lessonsConsidered,
                    outcomes: result.outcomesConsidered,
                    cost: result.estimatedCost.toFixed(4),
                  })}
                </p>
                <ul className="space-y-1">
                  {result.actions.map((action, i) => (
                    <li key={i} className="text-[11px]" data-testid={`consolidation-action-${i}`}>
                      <Badge variant={action.type === "RETIRE" ? "warning" : "secondary"} className="me-1 px-1.5 py-0 text-[9px]">
                        {action.type === "RETIRE" ? t("groupLessons.retire", "Retire") : t("groupLessons.merge", "Merge")}
                      </Badge>
                      <span className="font-mono">{action.keys.join(" ← ")}</span>
                      {action.text && <span className="block ps-3 text-muted-foreground">{action.text}</span>}
                      {action.reason && <span className="block ps-3 text-muted-foreground">{action.reason}</span>}
                      <span className="block ps-3 text-[10px]">
                        {action.applied
                          ? t("groupLessons.applied", "Applied")
                          : action.note ?? (result.dryRun ? t("groupLessons.proposed", "Proposed") : "")}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </>
        )}
      </Section>

      <Section title={t("groupLessons.historyTitle", "History")} testId="lessons-history">
        <button type="button" className="text-xs font-medium text-primary hover:underline" onClick={() => setShowHistory((v) => !v)} data-testid="lessons-history-toggle">
          {showHistory ? t("groupLessons.hideHistory", "Hide what consolidation removed") : t("groupLessons.showHistory", "Show what consolidation removed")}
        </button>
        {showHistory &&
          (history.isError ? (
            loadError("lessons-history-error", history)
          ) : history.isPending ? null : (history.data ?? []).length === 0 ? (
            <p className="text-xs text-muted-foreground" data-testid="lessons-history-empty">
              {t("groupLessons.historyEmpty", "Nothing has been merged or retired.")}
            </p>
          ) : (
            <ul className="space-y-1.5">
              {(history.data ?? []).map((entry) => (
                <li key={entry.id} className="text-xs" data-testid={`history-${entry.id}`}>
                  <Badge variant={entry.action === "RETIRED" ? "warning" : "secondary"} className="me-1 px-1.5 py-0 text-[9px]">
                    {entry.action === "RETIRED" ? t("groupLessons.retired", "Retired") : t("groupLessons.merged", "Merged")}
                  </Badge>
                  <span className="text-foreground">{entry.lessonValue}</span>
                  {entry.reason && <span className="block ps-3 text-[10px] text-muted-foreground">{entry.reason}</span>}
                  <span className="block ps-3 text-[10px] text-muted-foreground">{entry.at.slice(0, 10)}</span>
                </li>
              ))}
            </ul>
          ))}
      </Section>
    </div>
  );
}
