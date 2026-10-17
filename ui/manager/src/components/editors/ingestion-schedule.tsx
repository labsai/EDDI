import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { AlertCircle, CalendarClock, CheckCircle2, Clock, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { Input } from "@/components/ui/input";
import { useCronPreview, useSourceSchedule } from "@/hooks/use-ingestion-sources";
import { CRON_PRESETS } from "@/lib/ingestion-schedule";
import type { ScheduleFire } from "@/lib/api/ingestion-sources";

/** How long the cron check waits after the last keystroke. */
const PREVIEW_DEBOUNCE_MS = 400;

const CUSTOM = "__custom__";

/**
 * A source's schedule: a preset or a custom cron, checked against the server's
 * own rules as it is typed, with the next fire times it would produce.
 *
 * The cron used to be a bare text field whose only feedback was a 400 on save —
 * and a cron that saved could still be one that fires every minute or never.
 */
export function CronField({
  value,
  onChange,
  disabled,
  hint,
  testId,
}: {
  value: string | undefined;
  onChange: (cron: string | undefined) => void;
  disabled?: boolean;
  hint?: string;
  testId: string;
}) {
  const { t } = useTranslation();
  const current = value ?? "";
  const matchingPreset = CRON_PRESETS.find((p) => p.cron === current.trim());
  const [custom, setCustom] = useState(!matchingPreset);
  // The last value this field itself sent. A value that arrives from anywhere else
  // — Discard, a version switch, an edit in the JSON tab — re-derives the mode, or
  // a preset could keep showing the custom input and a custom cron could hide it.
  // A value the operator typed keeps the custom input open even when it happens
  // to match a preset.
  const emitted = useRef<string | null>(null);
  const isPreset = Boolean(matchingPreset);
  useEffect(() => {
    if (emitted.current === current) return;
    emitted.current = null;
    setCustom(!isPreset);
  }, [current, isPreset]);
  const emit = (next: string | undefined) => {
    emitted.current = next ?? "";
    onChange(next);
  };
  const debounced = useDebounced(current, PREVIEW_DEBOUNCE_MS);
  const preview = useCronPreview(debounced);

  return (
    <div className="space-y-1">
      <span className="text-xs font-medium text-foreground/80">
        {t("ragEditor.sources.cron", "Schedule (cron)")}
      </span>
      <select
        value={custom ? CUSTOM : (matchingPreset?.cron ?? CUSTOM)}
        onChange={(e) => {
          if (e.target.value === CUSTOM) {
            setCustom(true);
            return;
          }
          setCustom(false);
          emit(e.target.value || undefined);
        }}
        disabled={disabled}
        aria-label={t("ragEditor.sources.cron", "Schedule (cron)")}
        className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
        data-testid={`${testId}-cron-preset`}
      >
        {CRON_PRESETS.map((preset) => (
          <option key={preset.cron || "manual"} value={preset.cron}>
            {t(preset.labelKey, preset.fallback)}
          </option>
        ))}
        <option value={CUSTOM}>{t("ragEditor.schedule.presetCustom", "Custom cron…")}</option>
      </select>
      {custom && (
        <Input
          value={current}
          onChange={(e) => emit(e.target.value || undefined)}
          disabled={disabled}
          placeholder="0 2 * * *"
          aria-label={t("ragEditor.schedule.customCron", "Cron expression")}
          className="font-mono"
          data-testid={`${testId}-cron`}
        />
      )}
      {current.trim() !== "" && (
        <CronPreviewLine
          isLoading={preview.isFetching || debounced !== current}
          error={
            preview.data && !preview.data.valid
              ? (preview.data.error ?? t("ragEditor.schedule.invalid", "This cron would be refused."))
              : undefined
          }
          nextFires={preview.data?.valid ? preview.data.nextFires : undefined}
          testId={testId}
        />
      )}
      {hint && <span className="block text-[11px] text-muted-foreground">{hint}</span>}
    </div>
  );
}

function CronPreviewLine({
  isLoading,
  error,
  nextFires,
  testId,
}: {
  isLoading: boolean;
  error?: string;
  nextFires?: string[];
  testId: string;
}) {
  const { t } = useTranslation();
  if (isLoading) {
    return (
      <p className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
        <Loader2 className="h-3 w-3 animate-spin" aria-hidden="true" />
        {t("ragEditor.schedule.checking", "Checking…")}
      </p>
    );
  }
  if (error) {
    return (
      <p className="flex items-start gap-1.5 text-[11px] text-destructive" role="alert" data-testid={`${testId}-cron-error`}>
        <AlertCircle className="mt-0.5 h-3 w-3 shrink-0" />
        {error}
      </p>
    );
  }
  if (!nextFires || nextFires.length === 0) return null;
  return (
    <p className="flex items-start gap-1.5 text-[11px] text-muted-foreground" data-testid={`${testId}-cron-next`}>
      <CalendarClock className="mt-0.5 h-3 w-3 shrink-0" aria-hidden="true" />
      {t("ragEditor.schedule.nextRuns", "Next runs (your local time): {{times}}", {
        times: nextFires.map((fire) => formatFireTime(fire)).join(" · "),
      })}
    </p>
  );
}

/**
 * What the scheduler is actually doing with a saved source: when it fires next,
 * how its last fires went, and whether it has given up. The cron on the card says
 * what was asked for; this says what is happening.
 */
export function ScheduleStatus({
  kbId,
  sourceId,
  version,
  hasCron,
  enabled,
  isRunning,
  testId,
}: {
  kbId: string;
  sourceId: string;
  version: number;
  hasCron: boolean;
  enabled: boolean;
  isRunning: boolean;
  testId: string;
}) {
  const { t, i18n } = useTranslation();
  const schedule = useSourceSchedule(kbId, sourceId, version, hasCron, isRunning);

  if (!hasCron) {
    return null;
  }
  if (schedule.isLoading) {
    return <p className="text-xs text-muted-foreground">{t("common.loading", "Loading…")}</p>;
  }
  if (schedule.isError) {
    return (
      <StatusRow tone="error" testId={`${testId}-schedule-error`}>
        {t("ragEditor.schedule.loadFailed", "Could not read the schedule: {{error}}", {
          error: schedule.error instanceof Error ? schedule.error.message : String(schedule.error),
        })}
      </StatusRow>
    );
  }
  const data = schedule.data;
  if (!data) return null;

  if (!data.scheduled) {
    return (
      <StatusRow tone="warning" testId={`${testId}-schedule-missing`}>
        {enabled
          ? t(
              "ragEditor.schedule.notScheduled",
              "This cron is saved but nothing is scheduled. Save the knowledge base again; if it stays like this, the server refused the cron — its log says why.",
            )
          : t("ragEditor.schedule.disabledSource", "The source is disabled, so its cron does not run.")}
      </StatusRow>
    );
  }

  return (
    <div className="space-y-1.5 rounded-lg border border-border/60 p-2.5 text-xs" data-testid={`${testId}-schedule`}>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
        <span className="flex items-center gap-1.5 text-foreground">
          <Clock className="h-3.5 w-3.5 text-sky-500" aria-hidden="true" />
          {data.enabled && data.nextFire
            ? t("ragEditor.schedule.nextFire", "Next run {{relative}} ({{time}})", {
                relative: formatRelative(data.nextFire, i18n.language),
                time: formatFireTime(data.nextFire),
              })
            : t("ragEditor.schedule.paused", "Not scheduled to fire")}
        </span>
        <span className="text-muted-foreground" data-testid={`${testId}-schedule-last`}>
          {data.lastFired
            ? t("ragEditor.schedule.lastFired", "Last fired {{relative}}", {
                relative: formatRelative(data.lastFired, i18n.language),
              })
            : t("ragEditor.schedule.neverFired", "Has not fired yet")}
        </span>
      </div>

      {data.enabled === false && (
        <StatusRow tone="error" testId={`${testId}-schedule-disabled`}>
          {data.disabledReason
            ? t("ragEditor.schedule.disabledBecause", "The scheduler disabled this schedule: {{reason}}", {
                reason: data.disabledReason,
              })
            : t("ragEditor.schedule.disabled", "The scheduler disabled this schedule.")}
        </StatusRow>
      )}
      {data.fireStatus === "DEAD_LETTERED" && (
        <StatusRow tone="error" testId={`${testId}-schedule-dead`}>
          {t(
            "ragEditor.schedule.deadLettered",
            "The scheduler gave up after repeated failures. Fix the cause, then save the knowledge base to re-arm it.",
          )}
        </StatusRow>
      )}
      {(data.failCount ?? 0) > 0 && data.fireStatus !== "DEAD_LETTERED" && (
        <StatusRow tone="warning" testId={`${testId}-schedule-failing`}>
          {t("ragEditor.schedule.failing", "The last {{fails}} fire(s) failed; the scheduler is retrying.", {
            fails: data.failCount,
          })}
        </StatusRow>
      )}

      {data.recentFires.length > 0 && (
        <ul className="space-y-0.5" data-testid={`${testId}-schedule-fires`}>
          {data.recentFires.map((fire, i) => (
            <FireRow key={`${fire.fireTime ?? i}-${fire.attemptNumber ?? 0}`} fire={fire} />
          ))}
        </ul>
      )}
    </div>
  );
}

function FireRow({ fire }: { fire: ScheduleFire }) {
  const { t } = useTranslation();
  const failed = fire.status === "FAILED" || fire.status === "DEAD_LETTERED";
  return (
    <li className="flex items-start gap-1.5 text-[11px] text-muted-foreground">
      {failed ? (
        <AlertCircle className="mt-0.5 h-3 w-3 shrink-0 text-destructive" aria-hidden="true" />
      ) : (
        <CheckCircle2 className="mt-0.5 h-3 w-3 shrink-0 text-emerald-600 dark:text-emerald-400" aria-hidden="true" />
      )}
      <span>{fire.fireTime ? formatFireTime(fire.fireTime) : "—"}</span>
      <span className={cn(failed && "text-destructive")}>
        {failed
          ? (fire.errorMessage ?? t("ragEditor.schedule.fireFailed", "failed"))
          : t("ragEditor.schedule.fireStarted", "started a run")}
      </span>
    </li>
  );
}

function StatusRow({
  tone,
  testId,
  children,
}: {
  tone: "error" | "warning";
  testId: string;
  children: React.ReactNode;
}) {
  return (
    <p
      className={cn(
        "flex items-start gap-1.5 text-[11px]",
        tone === "error" ? "text-destructive" : "text-amber-700 dark:text-amber-400",
      )}
      role={tone === "error" ? "alert" : undefined}
      data-testid={testId}
    >
      <AlertCircle className="mt-0.5 h-3 w-3 shrink-0" />
      {children}
    </p>
  );
}

/** A fire time in the viewer's own time zone; ingestion crons are written in UTC. */
function formatFireTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString(undefined, {
    weekday: "short",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatRelative(iso: string, locale: string): string {
  const target = Date.parse(iso);
  if (!Number.isFinite(target)) return iso;
  const seconds = Math.round((target - Date.now()) / 1000);
  const format = new Intl.RelativeTimeFormat(locale, { numeric: "auto" });
  const abs = Math.abs(seconds);
  if (abs < 60) return format.format(seconds, "second");
  if (abs < 3600) return format.format(Math.round(seconds / 60), "minute");
  if (abs < 86_400) return format.format(Math.round(seconds / 3600), "hour");
  return format.format(Math.round(seconds / 86_400), "day");
}

function useDebounced<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = window.setTimeout(() => setDebounced(value), delayMs);
    return () => window.clearTimeout(id);
  }, [value, delayMs]);
  return debounced;
}
