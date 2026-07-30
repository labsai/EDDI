import { useState, useEffect, useCallback, useMemo, Fragment } from "react";
import { useOnboarding } from "@/hooks/use-onboarding";
import { useTranslation } from "react-i18next";
import { ErrorState } from "@/components/shared/error-state";
import {
  Clock,
  Plus,
  Trash2,
  Play,
  RotateCcw,
  ToggleLeft,
  ToggleRight,
  Timer,
  CalendarClock,
  AlertTriangle,
  ChevronDown,
  ChevronRight,
  Zap,
  Pause,
  HandMetal,
  Pencil,
  Globe,
  X,
  Inbox,
  Ban,
} from "lucide-react";
import { toast } from "sonner";
import {
  useSchedules,
  useCreateSchedule,
  useUpdateSchedule,
  useDeleteSchedule,
  useToggleSchedule,
  useFireNow,
  useRetryDeadLetter,
  useDismissDeadLetter,
  useFailedFires,
  useFireLogs,
} from "@/hooks/use-schedules";
import {
  fireLogDurationMs,
  parseInstant,
  formatInstantInZone,
  formatDateInZone,
  describeCron,
  isValidCron,
  nextCronFires,
  cronMinIntervalSeconds,
  isoToLocalInput,
  localInputToIso,
  listTimeZones,
  CRON_PRESETS,
  DEFAULT_TIME_ZONE,
  MIN_INTERVAL_SECONDS,
  UNLIMITED_COST,
} from "@/lib/api/schedules";
import type {
  ScheduleConfiguration,
  ScheduleFireLog,
  FireLogStatus,
} from "@/lib/api/schedules";

/** Which timing form the create/edit dialog is currently showing. */
type FormMode = "cron" | "oneTime" | "heartbeat";

/** HITL approval-timeout schedules are system-managed: the backend refuses a
 *  manual fire (409), an edit/delete/enable/disable/retry by non-admins (403),
 *  and any body that tries to forge one (400). Detect them so the UI doesn't
 *  offer actions the backend will reject. */
function isHitlTimeoutSchedule(s: ScheduleConfiguration): boolean {
  return s.metadata?.hitlType === "hitl_timeout";
}

/** Render a cost value, treating -1 / undefined as "unlimited". */
function formatCost(cost?: number): string {
  if (cost == null) return "—";
  if (cost < 0) return "∞";
  return `$${cost.toFixed(4)}`;
}

// ==================== Status Badge ====================

function StatusBadge({ schedule }: { schedule: ScheduleConfiguration }) {
  const { t } = useTranslation();
  if (
    !schedule.enabled &&
    schedule.fireStatus !== "FAILED" &&
    schedule.fireStatus !== "DEAD_LETTERED"
  ) {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-zinc-500/10 px-2.5 py-0.5 text-xs font-semibold text-zinc-400">
        <Pause className="h-3 w-3" />
        {t("schedules.statusDisabled", "Disabled")}
      </span>
    );
  }

  const statusMap: Record<string, { bg: string; text: string; label: string }> =
    {
      PENDING: {
        bg: "bg-emerald-500/10",
        text: "text-emerald-500",
        label: t("schedules.statusActive", "Active"),
      },
      CLAIMED: {
        bg: "bg-blue-500/10",
        text: "text-blue-500",
        label: t("schedules.statusRunning", "Running"),
      },
      EXECUTING: {
        bg: "bg-blue-500/10",
        text: "text-blue-500",
        label: t("schedules.statusExecuting", "Executing"),
      },
      COMPLETED: {
        bg: "bg-emerald-500/10",
        text: "text-emerald-500",
        label: t("schedules.statusActive", "Active"),
      },
      FAILED: {
        bg: "bg-amber-500/10",
        text: "text-amber-500",
        label: t("schedules.statusFailed", "Failed"),
      },
      DEAD_LETTERED: {
        bg: "bg-red-500/10",
        text: "text-red-500",
        label: t("schedules.statusDeadLettered", "Dead-Lettered"),
      },
    };

  const s = statusMap[schedule.fireStatus] ?? statusMap.PENDING!;
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold ${s.bg} ${s.text}`}
    >
      {schedule.fireStatus === "DEAD_LETTERED" && (
        <AlertTriangle className="h-3 w-3" />
      )}
      {s.label}
    </span>
  );
}

// ==================== Type Badge ====================

function TypeBadge({ schedule }: { schedule: ScheduleConfiguration }) {
  const { t } = useTranslation();
  if (schedule.triggerType === "HEARTBEAT") {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2.5 py-0.5 text-xs font-semibold text-primary">
        <Timer className="h-3 w-3" />
        {t("schedules.typeHeartbeat", "Heartbeat")}
      </span>
    );
  }
  if (schedule.oneTimeAt) {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2.5 py-0.5 text-xs font-semibold text-primary">
        <Clock className="h-3 w-3" />
        {t("schedules.typeOneTime", "One-time")}
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2.5 py-0.5 text-xs font-semibold text-primary">
      <CalendarClock className="h-3 w-3" />
      {t("schedules.typeCron", "Cron")}
    </span>
  );
}

// ==================== Fire Status Badge ====================

function FireStatusBadge({ status }: { status: FireLogStatus }) {
  const { t } = useTranslation();
  if (status === "COMPLETED") {
    return (
      <span className="inline-flex items-center gap-1 text-emerald-500">
        ✓ {t("schedules.fireCompleted", "Completed")}
      </span>
    );
  }
  if (status === "DEAD_LETTERED") {
    return (
      <span className="inline-flex items-center gap-1 text-amber-500">
        ⚠ {t("schedules.fireDeadLettered", "Dead-lettered")}
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 text-red-400">
      ✗ {t("schedules.fireFailed", "Failed")}
    </span>
  );
}

/** A conversation id rendered as a short monospace chip (or a dash). */
function ConversationCell({ conversationId }: { conversationId?: string }) {
  if (!conversationId) return <span className="text-muted-foreground">—</span>;
  return (
    <code
      className="text-xs text-muted-foreground"
      title={conversationId}
    >
      {conversationId.length > 12
        ? `${conversationId.slice(0, 12)}…`
        : conversationId}
    </code>
  );
}

// ==================== Fire Logs Expandable ====================

function FireLogsRow({ scheduleId }: { scheduleId: string }) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const { data: logs, isLoading } = useFireLogs(scheduleId, expanded);

  return (
    <tr>
      <td colSpan={8} className="px-5 py-0">
        <button
          onClick={() => setExpanded(!expanded)}
          className="flex items-center gap-1.5 py-2 text-xs text-muted-foreground transition-colors hover:text-primary"
          aria-expanded={expanded}
        >
          {expanded ? (
            <ChevronDown className="h-3 w-3" />
          ) : (
            <ChevronRight className="h-3 w-3" />
          )}
          {t("schedules.fireHistory", "Fire History")}
        </button>

        {expanded && (
          <div className="mb-3 overflow-x-auto rounded-lg border border-border/50 bg-muted/30">
            {isLoading ? (
              <div className="p-4 text-center">
                <div className="mx-auto h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />
              </div>
            ) : !logs || logs.length === 0 ? (
              <p className="p-4 text-center text-xs text-muted-foreground">
                {t("schedules.noFireHistory", "No fire history yet")}
              </p>
            ) : (
              <table className="w-full text-xs">
                <thead>
                  <tr className="border-b border-border/50 text-muted-foreground">
                    <th className="px-3 py-2 text-start font-medium">
                      {t("schedules.logFired", "Fired")}
                    </th>
                    <th className="px-3 py-2 text-start font-medium">
                      {t("schedules.logDuration", "Duration")}
                    </th>
                    <th className="px-3 py-2 text-start font-medium">
                      {t("schedules.logResult", "Result")}
                    </th>
                    <th className="px-3 py-2 text-start font-medium">
                      {t("schedules.logAttempt", "Attempt")}
                    </th>
                    <th className="px-3 py-2 text-start font-medium">
                      {t("schedules.logCost", "Cost")}
                    </th>
                    <th className="px-3 py-2 text-start font-medium">
                      {t("schedules.logConversation", "Conversation")}
                    </th>
                    <th className="px-3 py-2 text-start font-medium">
                      {t("status.error", "Error")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {logs.map((log: ScheduleFireLog, i: number) => {
                    const durationMs = fireLogDurationMs(log);
                    return (
                      <tr
                        key={log.id ?? i}
                        className="border-b border-border/30"
                      >
                        <td className="px-3 py-1.5 tabular-nums text-foreground">
                          {log.fireTime
                            ? (parseInstant(log.fireTime)?.toLocaleString() ?? "—")
                            : "—"}
                        </td>
                        <td className="px-3 py-1.5 tabular-nums text-muted-foreground">
                          {durationMs != null ? `${durationMs}ms` : "—"}
                        </td>
                        <td className="px-3 py-1.5">
                          <FireStatusBadge status={log.status} />
                        </td>
                        <td className="px-3 py-1.5 tabular-nums text-muted-foreground">
                          {log.attemptNumber ?? "—"}
                        </td>
                        <td className="px-3 py-1.5 tabular-nums text-muted-foreground">
                          {formatCost(log.cost)}
                        </td>
                        <td className="px-3 py-1.5">
                          <ConversationCell
                            conversationId={log.conversationId}
                          />
                        </td>
                        <td
                          className="max-w-[300px] truncate px-3 py-1.5 text-red-400"
                          title={log.errorMessage}
                        >
                          {log.errorMessage ?? "—"}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
          </div>
        )}
      </td>
    </tr>
  );
}

// ==================== Failed / Dead-letter Panel ====================

function FailedFiresPanel({
  schedules,
}: {
  schedules?: ScheduleConfiguration[];
}) {
  const { t } = useTranslation();
  const { data: failed, isLoading, isError, refetch } = useFailedFires();
  const retryMutation = useRetryDeadLetter();
  const dismissMutation = useDismissDeadLetter();

  const nameFor = (scheduleId: string) =>
    schedules?.find((s) => s.id === scheduleId)?.name ?? scheduleId;

  const handleRetry = (scheduleId: string) => {
    retryMutation.mutate(scheduleId, {
      onSuccess: () =>
        toast.success(t("schedules.retrySuccess", "Schedule re-queued")),
      onError: () =>
        toast.error(t("schedules.retryError", "Failed to retry schedule")),
    });
  };

  const handleDismiss = (scheduleId: string) => {
    dismissMutation.mutate(scheduleId, {
      onSuccess: () =>
        toast.success(t("schedules.dismissSuccess", "Dead letter dismissed")),
      onError: () =>
        toast.error(t("schedules.dismissError", "Failed to dismiss dead letter")),
    });
  };

  return (
    <div
      className="rounded-xl border border-border bg-card"
      data-testid="failed-fires-panel"
    >
      <div className="border-b border-border px-5 py-4">
        <h2 className="flex items-center gap-2 text-lg font-semibold text-foreground">
          <AlertTriangle className="h-4 w-4 text-amber-500" />
          {t("schedules.failedTitle", "Failed & Dead-Lettered Fires")}
        </h2>
        <p className="mt-0.5 text-sm text-muted-foreground">
          {t(
            "schedules.failedSubtitle",
            "Fires that failed permanently. Retry to re-queue, or dismiss to clear without re-running."
          )}
        </p>
      </div>

      {isLoading ? (
        <div className="p-8 text-center">
          <div className="mx-auto h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      ) : isError ? (
        <div className="p-8">
          {/* The dead-letter list is its own query. Without this a 500 rendered
              "No failed fires" — telling an operator nothing is broken at the
              exact moment the check for broken things failed. */}
          <ErrorState
            message={t("common.error")}
            onRetry={() => refetch()}
            retryLabel={t("common.retry")}
          />
        </div>
      ) : !failed || failed.length === 0 ? (
        <div
          className="p-8 text-center text-muted-foreground"
          data-testid="failed-fires-empty"
        >
          <Inbox className="mx-auto mb-2 h-8 w-8 text-muted-foreground/50" />
          <p>{t("schedules.failedEmpty", "No failed fires")}</p>
          <p className="mt-1 text-xs">
            {t(
              "schedules.failedEmptyHint",
              "Dead-lettered fires will appear here for retry or dismissal."
            )}
          </p>
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full" data-testid="failed-fires-table">
            <thead>
              <tr className="border-b border-border text-start text-sm text-muted-foreground">
                <th className="px-5 py-3 text-start font-medium">
                  {t("schedules.colSchedule2", "Schedule")}
                </th>
                <th className="px-5 py-3 text-start font-medium">
                  {t("schedules.logFired", "Fired")}
                </th>
                <th className="px-5 py-3 text-start font-medium">
                  {t("schedules.logAttempt", "Attempt")}
                </th>
                <th className="px-5 py-3 text-start font-medium">
                  {t("schedules.logCost", "Cost")}
                </th>
                <th className="px-5 py-3 text-start font-medium">
                  {t("schedules.logConversation", "Conversation")}
                </th>
                <th className="px-5 py-3 text-start font-medium">
                  {t("status.error", "Error")}
                </th>
                <th className="px-5 py-3 text-end font-medium">
                  {t("schedules.colActions", "Actions")}
                </th>
              </tr>
            </thead>
            <tbody>
              {failed.map((log, i) => (
                <tr
                  key={log.id ?? i}
                  className="border-b border-border/50 transition-colors hover:bg-muted/30"
                  data-testid={`failed-row-${log.scheduleId}`}
                >
                  <td className="px-5 py-3">
                    <span className="font-medium text-foreground">
                      {nameFor(log.scheduleId)}
                    </span>
                  </td>
                  <td className="px-5 py-3 text-sm tabular-nums text-muted-foreground">
                    {log.fireTime
                      ? (parseInstant(log.fireTime)?.toLocaleString() ?? "—")
                      : "—"}
                  </td>
                  <td className="px-5 py-3 text-sm tabular-nums text-muted-foreground">
                    {log.attemptNumber ?? "—"}
                  </td>
                  <td className="px-5 py-3 text-sm tabular-nums text-muted-foreground">
                    {formatCost(log.cost)}
                  </td>
                  <td className="px-5 py-3">
                    <ConversationCell conversationId={log.conversationId} />
                  </td>
                  <td
                    className="max-w-[280px] truncate px-5 py-3 text-sm text-red-400"
                    title={log.errorMessage}
                  >
                    {log.errorMessage ?? "—"}
                  </td>
                  <td className="px-5 py-3">
                    <div className="flex items-center justify-end gap-1.5">
                      <button
                        onClick={() => handleRetry(log.scheduleId)}
                        disabled={retryMutation.isPending}
                        className="inline-flex items-center gap-1 rounded-md border border-border px-2 py-1 text-xs font-medium text-foreground transition-colors hover:bg-primary/10 hover:text-primary disabled:opacity-50"
                        data-testid={`failed-retry-${log.scheduleId}`}
                      >
                        <RotateCcw className="h-3 w-3" />
                        {t("schedules.retry", "Retry")}
                      </button>
                      <button
                        onClick={() => handleDismiss(log.scheduleId)}
                        disabled={dismissMutation.isPending}
                        className="inline-flex items-center gap-1 rounded-md border border-border px-2 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-muted disabled:opacity-50"
                        data-testid={`failed-dismiss-${log.scheduleId}`}
                      >
                        <Ban className="h-3 w-3" />
                        {t("schedules.dismiss", "Dismiss")}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// ==================== Create / Edit Dialog ====================

function ScheduleFormDialog({
  open,
  editing,
  onClose,
}: {
  open: boolean;
  editing: ScheduleConfiguration | null;
  onClose: () => void;
}) {
  const { t, i18n } = useTranslation();
  const createMutation = useCreateSchedule();
  const updateMutation = useUpdateSchedule();
  const isEdit = editing != null;

  const [formMode, setFormMode] = useState<FormMode>("cron");
  const [name, setName] = useState("");
  const [agentId, setAgentId] = useState("");
  const [agentVersion, setAgentVersion] = useState(0);
  const [cronExpression, setCronExpression] = useState("0 9 * * MON-FRI");
  const [heartbeatInterval, setHeartbeatInterval] = useState(300);
  const [oneTimeAt, setOneTimeAt] = useState("");
  const [timeZone, setTimeZone] = useState(DEFAULT_TIME_ZONE);
  const [message, setMessage] = useState("Hello");
  const [environment, setEnvironment] = useState("production");
  const [strategy, setStrategy] = useState<"new" | "persistent">("new");
  const [persistentConversationId, setPersistentConversationId] = useState("");
  const [userId, setUserId] = useState("");
  const [unlimitedCost, setUnlimitedCost] = useState(true);
  const [maxCost, setMaxCost] = useState(1);

  const timeZones = useMemo(() => listTimeZones(), []);

  const resetToDefaults = useCallback(() => {
    setFormMode("cron");
    setName("");
    setAgentId("");
    setAgentVersion(0);
    setCronExpression("0 9 * * MON-FRI");
    setHeartbeatInterval(300);
    setOneTimeAt("");
    setTimeZone(DEFAULT_TIME_ZONE);
    setMessage("Hello");
    setEnvironment("production");
    setStrategy("new");
    setPersistentConversationId("");
    setUserId("");
    setUnlimitedCost(true);
    setMaxCost(1);
  }, []);

  const prefillFrom = useCallback((s: ScheduleConfiguration) => {
    const mode: FormMode =
      s.triggerType === "HEARTBEAT"
        ? "heartbeat"
        : s.oneTimeAt
          ? "oneTime"
          : "cron";
    setFormMode(mode);
    setName(s.name ?? "");
    setAgentId(s.agentId ?? "");
    setAgentVersion(s.agentVersion ?? 0);
    setCronExpression(s.cronExpression ?? "0 9 * * MON-FRI");
    setHeartbeatInterval(s.heartbeatIntervalSeconds ?? 300);
    setOneTimeAt(isoToLocalInput(s.oneTimeAt));
    setTimeZone(s.timeZone ?? DEFAULT_TIME_ZONE);
    setMessage(s.message ?? "");
    setEnvironment(s.environment ?? "production");
    setStrategy((s.conversationStrategy as "new" | "persistent") ?? "new");
    setPersistentConversationId(s.persistentConversationId ?? "");
    setUserId(s.userId ?? "");
    if (s.maxCostPerFire == null || s.maxCostPerFire < 0) {
      setUnlimitedCost(true);
      setMaxCost(1);
    } else {
      setUnlimitedCost(false);
      setMaxCost(s.maxCostPerFire);
    }
  }, []);

  // (Re)initialise the form each time the dialog opens.
  useEffect(() => {
    if (!open) return;
    if (editing) prefillFrom(editing);
    else resetToDefaults();
  }, [open, editing, prefillFrom, resetToDefaults]);

  // ESC to close
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  // --- Live cron helper state ---
  const cronDesc = useMemo(
    () =>
      formMode === "cron"
        ? describeCron(
            cronExpression,
            (k, d, vars) => t(k, { defaultValue: d, ...(vars ?? {}) }),
            i18n.language
          )
        : null,
    [formMode, cronExpression, t, i18n.language]
  );
  const cronNextFires = useMemo(
    () =>
      formMode === "cron" && isValidCron(cronExpression)
        ? nextCronFires(cronExpression, 3, new Date(), timeZone)
        : [],
    [formMode, cronExpression, timeZone]
  );
  const cronMin = useMemo(
    () =>
      formMode === "cron" && isValidCron(cronExpression)
        ? cronMinIntervalSeconds(cronExpression, timeZone)
        : null,
    [formMode, cronExpression, timeZone]
  );

  let cronError: string | null = null;
  if (formMode === "cron") {
    if (!isValidCron(cronExpression)) {
      cronError = t(
        "schedules.cronInvalid",
        "Invalid cron expression (expected 5 fields)"
      );
    } else if (cronMin != null && cronMin < MIN_INTERVAL_SECONDS) {
      cronError = t(
        "schedules.cronTooFrequent",
        "Fires more often than the 60-second minimum"
      );
    }
  }

  const heartbeatError =
    formMode === "heartbeat" && heartbeatInterval < MIN_INTERVAL_SECONDS
      ? t(
          "schedules.intervalTooShort",
          "Interval must be at least 60 seconds"
        )
      : null;

  if (!open) return null;

  const messageRequired = formMode !== "heartbeat";
  const isValid =
    name.trim().length > 0 &&
    agentId.trim().length > 0 &&
    (!messageRequired || message.trim().length > 0) &&
    (formMode === "cron"
      ? cronError == null
      : formMode === "oneTime"
        ? localInputToIso(oneTimeAt) != null
        : heartbeatError == null);

  const isPending = createMutation.isPending || updateMutation.isPending;

  const buildConfig = (): Partial<ScheduleConfiguration> => {
    const effectiveStrategy = formMode === "heartbeat" ? "persistent" : strategy;
    const config: Partial<ScheduleConfiguration> = {
      name: name.trim(),
      triggerType: formMode === "heartbeat" ? "HEARTBEAT" : "CRON",
      agentId: agentId.trim(),
      agentVersion,
      environment,
      message: message.trim() || undefined,
      timeZone,
      userId: userId.trim() || undefined,
      maxCostPerFire: unlimitedCost ? UNLIMITED_COST : maxCost,
      conversationStrategy: effectiveStrategy,
      enabled: editing ? editing.enabled : true,
    };
    // Exactly one of cron / oneTimeAt / heartbeat (backend rule).
    if (formMode === "cron") {
      config.cronExpression = cronExpression.trim();
    } else if (formMode === "oneTime") {
      config.oneTimeAt = localInputToIso(oneTimeAt) ?? undefined;
    } else {
      config.heartbeatIntervalSeconds = heartbeatInterval;
    }
    if (effectiveStrategy === "persistent" && persistentConversationId.trim()) {
      config.persistentConversationId = persistentConversationId.trim();
    }
    return config;
  };

  const handleSubmit = () => {
    if (!isValid) return;
    const config = buildConfig();
    if (isEdit && editing?.id) {
      updateMutation.mutate(
        { id: editing.id, config },
        {
          onSuccess: () => {
            toast.success(t("schedules.updateSuccess", "Schedule updated"));
            onClose();
          },
          onError: () =>
            toast.error(
              t("schedules.updateError", "Failed to update schedule")
            ),
        }
      );
    } else {
      createMutation.mutate(config, {
        onSuccess: () => {
          toast.success(
            t("schedules.createSuccess", "Schedule created successfully")
          );
          onClose();
        },
        onError: () =>
          toast.error(t("schedules.createError", "Failed to create schedule")),
      });
    }
  };

  const modeTabs: { mode: FormMode; label: string; Icon: typeof Timer }[] = [
    { mode: "cron", label: t("schedules.typeCron", "Cron"), Icon: CalendarClock },
    { mode: "oneTime", label: t("schedules.typeOneTime", "One-time"), Icon: Clock },
    {
      mode: "heartbeat",
      label: t("schedules.typeHeartbeat", "Heartbeat"),
      Icon: Timer,
    },
  ];

  const inputCls =
    "w-full rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground/50 focus:border-primary focus:outline-none";
  const labelCls = "mb-1 block text-sm font-medium text-muted-foreground";

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      role="dialog"
      aria-modal="true"
      aria-labelledby="schedule-form-title"
    >
      <div
        className="absolute inset-0 bg-black/50 backdrop-blur-sm"
        onClick={onClose}
      />
      <div className="relative max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-2xl border border-border bg-card p-6 shadow-2xl">
        <h2
          id="schedule-form-title"
          className="mb-4 text-lg font-bold text-foreground"
        >
          {isEdit
            ? t("schedules.editTitle", "Edit Schedule")
            : t("schedules.createTitle", "Create Schedule")}
        </h2>

        <div className="space-y-4">
          {/* Name */}
          <div>
            <label className={labelCls}>{t("schedules.name", "Name")}</label>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t(
                "schedules.namePlaceholder",
                "e.g. Daily health check"
              )}
              className={inputCls}
              data-testid="schedule-name-input"
            />
          </div>

          {/* Trigger Type Tabs */}
          <div>
            <label className={labelCls}>
              {t("schedules.triggerType", "Trigger Type")}
            </label>
            <div className="flex gap-1 rounded-lg border border-border bg-muted/30 p-1">
              {modeTabs.map(({ mode, label, Icon }) => (
                <button
                  key={mode}
                  onClick={() => {
                    setFormMode(mode);
                    if (mode === "heartbeat") setStrategy("persistent");
                  }}
                  data-testid={`trigger-${mode}`}
                  className={`flex flex-1 items-center justify-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium transition-all ${
                    formMode === mode
                      ? "bg-primary text-primary-foreground shadow-sm"
                      : "text-muted-foreground hover:text-foreground"
                  }`}
                >
                  <Icon className="h-3.5 w-3.5" />
                  {label}
                </button>
              ))}
            </div>
          </div>

          {/* Timing: Cron */}
          {formMode === "cron" && (
            <div>
              <label className={labelCls}>
                {t("schedules.cronExpression", "Cron Expression")}
              </label>
              <div className="mb-2 flex flex-wrap gap-1">
                {CRON_PRESETS.map((p) => (
                  <button
                    key={p.key}
                    type="button"
                    onClick={() => setCronExpression(p.expression)}
                    title={p.expression}
                    data-testid={`cron-preset-${p.key}`}
                    className={`rounded-full border px-2 py-0.5 text-xs transition-colors ${
                      cronExpression.trim() === p.expression
                        ? "border-primary bg-primary/10 text-primary"
                        : "border-border text-muted-foreground hover:bg-muted"
                    }`}
                  >
                    {t(`schedules.cronPreset.${p.key}`, p.label)}
                  </button>
                ))}
              </div>
              <input
                value={cronExpression}
                onChange={(e) => setCronExpression(e.target.value)}
                placeholder="0 9 * * MON-FRI"
                className={`${inputCls} font-mono`}
                data-testid="cron-input"
              />
              {cronError ? (
                <p className="mt-1 text-xs text-red-400" data-testid="cron-error">
                  {cronError}
                </p>
              ) : (
                <>
                  {cronDesc && (
                    <p
                      className="mt-1 text-xs text-primary"
                      data-testid="cron-description"
                    >
                      {cronDesc}
                    </p>
                  )}
                  {cronNextFires.length > 0 && (
                    <p
                      className="mt-0.5 text-xs text-muted-foreground"
                      data-testid="cron-next-preview"
                    >
                      {t("schedules.cronNext", "Next")}:{" "}
                      {/* These instants were computed against the selected
                          zone, so render them in it too — otherwise the preview
                          appears not to match the cron the user just typed. */}
                      {cronNextFires
                        .map((d) => formatDateInZone(d, timeZone, true))
                        .join(" · ")}
                    </p>
                  )}
                </>
              )}
              <p className="mt-1 text-xs text-muted-foreground/70">
                {t(
                  "schedules.cronHelp",
                  "5-field format: minute hour day-of-month month day-of-week"
                )}
              </p>
            </div>
          )}

          {/* Timing: One-time */}
          {formMode === "oneTime" && (
            <div>
              <label className={labelCls}>
                {t("schedules.oneTimeAt", "Date & time")}
              </label>
              <input
                type="datetime-local"
                value={oneTimeAt}
                onChange={(e) => setOneTimeAt(e.target.value)}
                className={inputCls}
                data-testid="onetime-input"
              />
              <p className="mt-1 text-xs text-muted-foreground/70">
                {t(
                  "schedules.oneTimeHelp",
                  "Fires once at this moment, then completes. Interpreted in your local time."
                )}
              </p>
            </div>
          )}

          {/* Timing: Heartbeat */}
          {formMode === "heartbeat" && (
            <div>
              <label className={labelCls}>
                {t("schedules.interval", "Interval (seconds)")}
              </label>
              <input
                type="number"
                min={MIN_INTERVAL_SECONDS}
                value={heartbeatInterval}
                onChange={(e) => setHeartbeatInterval(Number(e.target.value))}
                className={inputCls}
                data-testid="heartbeat-input"
              />
              {heartbeatError && (
                <p
                  className="mt-1 text-xs text-red-400"
                  data-testid="heartbeat-error"
                >
                  {heartbeatError}
                </p>
              )}
            </div>
          )}

          {/* Time Zone */}
          <div>
            <label className={`${labelCls} flex items-center gap-1.5`}>
              <Globe className="h-3.5 w-3.5" />
              {t("schedules.timeZone", "Time Zone")}
            </label>
            <select
              value={timeZone}
              onChange={(e) => setTimeZone(e.target.value)}
              className={inputCls}
              data-testid="timezone-select"
            >
              {timeZones.map((tz) => (
                <option key={tz} value={tz}>
                  {tz}
                </option>
              ))}
            </select>
          </div>

          {/* Agent ID + Version */}
          <div className="flex gap-3">
            <div className="flex-1">
              <label className={labelCls}>
                {t("schedules.agentId", "Agent ID")}
              </label>
              <input
                value={agentId}
                onChange={(e) => setAgentId(e.target.value)}
                placeholder={t(
                  "schedules.agentIdPlaceholder",
                  "Enter agent ID..."
                )}
                className={inputCls}
                data-testid="agent-id-input"
              />
            </div>
            <div className="w-32">
              <label className={labelCls}>
                {t("schedules.agentVersion", "Version")}
              </label>
              <input
                type="number"
                min={0}
                value={agentVersion}
                onChange={(e) => setAgentVersion(Number(e.target.value))}
                className={inputCls}
                data-testid="agent-version-input"
                title={t("schedules.agentVersionHint", "0 = latest deployed")}
              />
            </div>
          </div>

          {/* Environment */}
          <div>
            <label className={labelCls}>
              {t("schedules.environment", "Environment")}
            </label>
            <select
              value={environment}
              onChange={(e) => setEnvironment(e.target.value)}
              className={inputCls}
            >
              <option value="production">
                {t("schedules.envProduction", "Production")}
              </option>
              <option value="test">{t("schedules.envTest", "Test")}</option>
            </select>
          </div>

          {/* Message */}
          <div>
            <label className={labelCls}>
              {t("schedules.message", "Message")}
            </label>
            <input
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              placeholder={t(
                "schedules.messagePlaceholder",
                "Message to send to agent"
              )}
              className={inputCls}
              data-testid="message-input"
            />
          </div>

          {/* User ID */}
          <div>
            <label className={labelCls}>
              {t("schedules.userId", "User ID")}
            </label>
            <input
              value={userId}
              onChange={(e) => setUserId(e.target.value)}
              placeholder={t("schedules.userIdPlaceholder", "system:scheduler")}
              className={inputCls}
              data-testid="userid-input"
            />
          </div>

          {/* Max cost per fire */}
          <div>
            <label className={labelCls}>
              {t("schedules.maxCostPerFire", "Max cost per fire")}
            </label>
            <div className="flex items-center gap-3">
              <label className="flex items-center gap-1.5 text-sm text-muted-foreground">
                <input
                  type="checkbox"
                  checked={unlimitedCost}
                  onChange={(e) => setUnlimitedCost(e.target.checked)}
                  data-testid="maxcost-unlimited"
                  className="h-4 w-4 rounded border-border"
                />
                {t("schedules.unlimited", "Unlimited")}
              </label>
              <input
                type="number"
                min={0}
                step={0.01}
                value={maxCost}
                disabled={unlimitedCost}
                onChange={(e) => setMaxCost(Number(e.target.value))}
                className={`${inputCls} flex-1 disabled:opacity-50`}
                data-testid="maxcost-input"
              />
            </div>
          </div>

          {/* Conversation Strategy (cron / one-time only) */}
          {formMode !== "heartbeat" && (
            <div>
              <label className={labelCls}>
                {t("schedules.conversationStrategy", "Conversation Strategy")}
              </label>
              <select
                value={strategy}
                onChange={(e) =>
                  setStrategy(e.target.value as "new" | "persistent")
                }
                className={inputCls}
                data-testid="strategy-select"
              >
                <option value="new">
                  {t("schedules.strategyNew", "New (fresh conversation each fire)")}
                </option>
                <option value="persistent">
                  {t(
                    "schedules.strategyPersistent",
                    "Persistent (reuse same conversation)"
                  )}
                </option>
              </select>
            </div>
          )}

          {/* Persistent conversation id (only when persistent) */}
          {(formMode === "heartbeat" || strategy === "persistent") && (
            <div>
              <label className={labelCls}>
                {t(
                  "schedules.persistentConversationId",
                  "Persistent conversation ID"
                )}
              </label>
              <input
                value={persistentConversationId}
                onChange={(e) => setPersistentConversationId(e.target.value)}
                placeholder={t(
                  "schedules.persistentConversationIdPlaceholder",
                  "Auto-generated if left blank"
                )}
                className={inputCls}
                data-testid="persistent-conv-input"
              />
            </div>
          )}
        </div>

        {/* Actions */}
        <div className="mt-6 flex justify-end gap-2">
          <button
            onClick={onClose}
            className="rounded-lg border border-border px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-muted"
          >
            {t("common.cancel", "Cancel")}
          </button>
          <button
            onClick={handleSubmit}
            disabled={!isValid || isPending}
            className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
            data-testid="schedule-submit-btn"
          >
            {isEdit
              ? isPending
                ? t("schedules.saving", "Saving...")
                : t("common.save", "Save")
              : isPending
                ? t("schedules.creating", "Creating...")
                : t("common.create", "Create")}
          </button>
        </div>
      </div>
    </div>
  );
}

// ==================== Main Page ====================

export function SchedulesPage() {
  const { t } = useTranslation();

  const maybeAutoStart = useOnboarding((s) => s.maybeAutoStart);
  useEffect(() => {
    const timer = setTimeout(() => maybeAutoStart("schedules"), 500);
    return () => clearTimeout(timer);
  }, [maybeAutoStart]);

  const { data: schedules, isLoading, isError, refetch } = useSchedules();
  const { data: failedFires } = useFailedFires();
  const deleteMutation = useDeleteSchedule();
  const toggleMutation = useToggleSchedule();
  const fireMutation = useFireNow();
  const retryMutation = useRetryDeadLetter();

  const [activeTab, setActiveTab] = useState<"schedules" | "failed">(
    "schedules"
  );
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<ScheduleConfiguration | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [confirmFireId, setConfirmFireId] = useState<string | null>(null);
  const [fireOutcomes, setFireOutcomes] = useState<
    Record<string, ScheduleFireLog | null>
  >({});

  const total = schedules?.length ?? 0;
  const active = schedules?.filter((s) => s.enabled).length ?? 0;
  const failed =
    schedules?.filter(
      (s) => s.fireStatus === "FAILED" || s.fireStatus === "DEAD_LETTERED"
    ).length ?? 0;
  const failedFiresCount = failedFires?.length ?? 0;

  // `nextFire` arrives either as an ISO-8601 string (backend @JsonFormat) or as
  // a numeric epoch, so compare parsed instants. Subtracting the raw values
  // yields NaN on the ISO form, which makes the comparator inconsistent and
  // silently surfaces the wrong "soonest" schedule. Unparseable values sort
  // last, and the explicit compare avoids NaN when both are unparseable.
  const nextFireMs = (s: { nextFire?: string | number }) =>
    parseInstant(s.nextFire)?.getTime() ?? Number.POSITIVE_INFINITY;
  const soonest = schedules
    ?.filter((s) => s.enabled && s.nextFire)
    ?.sort((a, b) => {
      const x = nextFireMs(a);
      const y = nextFireMs(b);
      return x === y ? 0 : x < y ? -1 : 1;
    })?.[0];

  const openCreate = () => {
    setEditing(null);
    setShowForm(true);
  };

  const openEdit = (s: ScheduleConfiguration) => {
    setEditing(s);
    setShowForm(true);
  };

  const handleToggle = (s: ScheduleConfiguration) => {
    toggleMutation.mutate(
      { id: s.id!, enable: !s.enabled },
      {
        onSuccess: () =>
          toast.success(
            s.enabled
              ? t("schedules.disabled", "Schedule disabled")
              : t("schedules.enabled", "Schedule enabled")
          ),
        onError: () =>
          toast.error(t("schedules.toggleError", "Failed to toggle schedule")),
      }
    );
  };

  const doFire = (s: ScheduleConfiguration) => {
    fireMutation.mutate(s.id!, {
      onSuccess: (log) => {
        setConfirmFireId(null);
        setFireOutcomes((prev) => ({ ...prev, [s.id!]: log ?? null }));
        if (log && (log.status === "FAILED" || log.status === "DEAD_LETTERED")) {
          toast.error(
            `${t("schedules.fireOutcomeFailed", "Fire failed")}: ${
              log.errorMessage ?? t("schedules.unknownError", "unknown error")
            }`
          );
        } else {
          toast.success(t("schedules.fired", "Schedule fired successfully"));
        }
      },
      onError: () => {
        setConfirmFireId(null);
        toast.error(t("schedules.fireError", "Failed to fire schedule"));
      },
    });
  };

  const clearOutcome = (id: string) =>
    setFireOutcomes((prev) => {
      const next = { ...prev };
      delete next[id];
      return next;
    });

  const handleDelete = (id: string) => {
    deleteMutation.mutate(id, {
      onSuccess: () => {
        toast.success(t("schedules.deleteSuccess", "Schedule deleted"));
        setConfirmDeleteId(null);
      },
      onError: () =>
        toast.error(t("schedules.deleteError", "Failed to delete schedule")),
    });
  };

  const handleRetry = (id: string) => {
    retryMutation.mutate(id, {
      onSuccess: () =>
        toast.success(t("schedules.retrySuccess", "Schedule re-queued")),
      onError: () =>
        toast.error(t("schedules.retryError", "Failed to retry schedule")),
    });
  };

  return (
    <div className="space-y-6 p-6">
      {/* Page Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Clock className="h-7 w-7 text-primary" />
          <div>
            <h1 className="text-2xl font-bold text-foreground">
              {t("schedules.title", "Schedules")}
            </h1>
            <p className="text-sm text-muted-foreground">
              {t(
                "schedules.subtitle",
                "Manage scheduled agent triggers — cron jobs and heartbeats"
              )}
            </p>
          </div>
        </div>
        <button
          onClick={openCreate}
          className="flex items-center gap-1.5 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
          data-testid="create-schedule-btn"
        >
          <Plus className="h-4 w-4" />
          {t("schedules.create", "Create Schedule")}
        </button>
      </div>

      {/* Status Cards */}
      {isLoading ? (
        <div className="cq-stat-grid" data-testid="schedules-loading">
          {[...Array(4)].map((_, i) => (
            <div
              key={i}
              className="animate-pulse rounded-xl border border-border bg-card p-5"
            >
              <div className="h-4 w-24 rounded bg-muted" />
              <div className="mt-3 h-8 w-16 rounded bg-muted" />
            </div>
          ))}
        </div>
      ) : (
        <div className="cq-stat-grid" data-tour="schedules-stats">
          {/* Total */}
          <div
            className="rounded-xl border border-border bg-card p-5"
            data-testid="schedules-total-card"
          >
            <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
              <Clock className="h-4 w-4" />
              {t("schedules.total", "Total Schedules")}
            </div>
            <p className="mt-2 text-2xl font-bold tabular-nums text-foreground">
              {total}
            </p>
          </div>

          {/* Active */}
          <div
            className="rounded-xl border border-border bg-card p-5"
            data-testid="schedules-active-card"
          >
            <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
              <Zap className="h-4 w-4" />
              {t("schedules.active", "Active")}
            </div>
            <p className="mt-2 text-2xl font-bold tabular-nums text-emerald-500">
              {active}
            </p>
          </div>

          {/* Failed */}
          <div
            className="rounded-xl border border-border bg-card p-5"
            data-testid="schedules-failed-card"
          >
            <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
              <AlertTriangle className="h-4 w-4" />
              {t("schedules.failedCount", "Failed / Dead-Lettered")}
            </div>
            <p
              className={`mt-2 text-2xl font-bold tabular-nums ${
                failed > 0 ? "text-amber-500" : "text-foreground"
              }`}
            >
              {failed}
            </p>
          </div>

          {/* Next Fire */}
          <div
            className="rounded-xl border border-border bg-card p-5"
            data-testid="schedules-next-fire-card"
          >
            <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
              <CalendarClock className="h-4 w-4" />
              {t("schedules.nextFireLabel", "Next Fire")}
            </div>
            <p className="mt-2 text-lg font-semibold tabular-nums text-foreground">
              {/* No zone label on this card, so carry a short zone marker. */}
              {formatInstantInZone(
                soonest?.nextFire,
                soonest?.timeZone ?? DEFAULT_TIME_ZONE,
                true
              )}
            </p>
            {soonest && (
              <p className="mt-0.5 truncate text-xs text-muted-foreground">
                {soonest.name}
              </p>
            )}
          </div>
        </div>
      )}

      {/* Tabs */}
      <div className="flex gap-1 border-b border-border" role="tablist">
        <button
          role="tab"
          aria-selected={activeTab === "schedules"}
          onClick={() => setActiveTab("schedules")}
          data-testid="tab-schedules"
          className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium transition-colors ${
            activeTab === "schedules"
              ? "border-primary text-primary"
              : "border-transparent text-muted-foreground hover:text-foreground"
          }`}
        >
          {t("schedules.tabSchedules", "Schedules")}
        </button>
        <button
          role="tab"
          aria-selected={activeTab === "failed"}
          onClick={() => setActiveTab("failed")}
          data-testid="tab-failed"
          className={`-mb-px flex items-center gap-1.5 border-b-2 px-4 py-2 text-sm font-medium transition-colors ${
            activeTab === "failed"
              ? "border-primary text-primary"
              : "border-transparent text-muted-foreground hover:text-foreground"
          }`}
        >
          {t("schedules.tabFailed", "Failed / Dead-letter")}
          {failedFiresCount > 0 && (
            <span
              className="rounded-full bg-amber-500/15 px-1.5 py-0.5 text-xs font-semibold text-amber-500"
              data-testid="failed-tab-count"
            >
              {failedFiresCount}
            </span>
          )}
        </button>
      </div>

      {activeTab === "failed" ? (
        <FailedFiresPanel schedules={schedules} />
      ) : (
        /* Schedule Table */
        <div
          className="rounded-xl border border-border bg-card"
          data-testid="schedules-table-container"
        >
          <div className="border-b border-border px-5 py-4">
            <h2 className="text-lg font-semibold text-foreground">
              {t("schedules.tableTitle", "All Schedules")}
            </h2>
          </div>

          {isLoading ? (
            <div className="p-8 text-center">
              <div className="mx-auto h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
            </div>
          ) : isError ? (
            <div className="p-8">
              {/* A failed fetch used to fall through to the empty state below, which
                  told the user there is no data when the request never landed. */}
              <ErrorState
                message={t("common.error")}
                onRetry={() => refetch()}
                retryLabel={t("common.retry")}
              />
            </div>
          ) : !schedules || schedules.length === 0 ? (
            <div
              className="p-8 text-center text-muted-foreground"
              data-testid="schedules-empty"
            >
              <Clock className="mx-auto mb-2 h-8 w-8 text-muted-foreground/50" />
              <p>{t("schedules.empty", "No schedules yet")}</p>
              <p className="mt-1 text-xs">
                {t(
                  "schedules.emptyHint",
                  "Create a schedule to automate agent triggers."
                )}
              </p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full" data-testid="schedules-table">
                <thead>
                  <tr className="border-b border-border text-start text-sm text-muted-foreground">
                    <th className="px-5 py-3 text-start font-medium">
                      {t("schedules.colName", "Name")}
                    </th>
                    <th className="px-5 py-3 text-start font-medium">
                      {t("schedules.colType", "Type")}
                    </th>
                    <th className="px-5 py-3 text-start font-medium">
                      {t("schedules.colSchedule", "Schedule")}
                    </th>
                    <th className="px-5 py-3 text-start font-medium">
                      {t("schedules.colAgent", "Agent")}
                    </th>
                    <th className="px-5 py-3 text-start font-medium">
                      {t("schedules.colStatus", "Status")}
                    </th>
                    <th className="px-5 py-3 text-start font-medium">
                      {t("schedules.colNextFire", "Next Fire")}
                    </th>
                    <th className="px-5 py-3 text-start font-medium">
                      {t("schedules.colLastFired", "Last Fired")}
                    </th>
                    <th className="px-5 py-3 text-end font-medium">
                      {t("schedules.colActions", "Actions")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {schedules.map((s) => {
                    const hitl = isHitlTimeoutSchedule(s);
                    const outcome = fireOutcomes[s.id!];
                    return (
                      <Fragment key={s.id}>
                        <tr className="border-b border-border/50 transition-colors hover:bg-muted/30">
                          <td className="px-5 py-3">
                            <span className="font-medium text-foreground">
                              {s.name}
                            </span>
                            {hitl && (
                              <span
                                className="ms-2 inline-flex items-center gap-1 rounded-full bg-amber-500/10 px-2 py-0.5 text-[10px] font-medium text-amber-600"
                                title={t(
                                  "schedules.hitlTimeoutHint",
                                  "System-managed HITL approval timeout — resolve it via the conversation's approval, not here."
                                )}
                                data-testid={`hitl-schedule-badge-${s.id}`}
                              >
                                <HandMetal className="h-3 w-3" />{" "}
                                {t("schedules.hitlTimeout", "HITL timeout")}
                              </span>
                            )}
                          </td>
                          <td className="px-5 py-3">
                            <TypeBadge schedule={s} />
                          </td>
                          <td className="px-5 py-3">
                            <code className="text-xs text-muted-foreground">
                              {s.triggerType === "HEARTBEAT"
                                ? `Every ${s.heartbeatIntervalSeconds}s`
                                : s.oneTimeAt
                                  ? new Date(s.oneTimeAt).toLocaleString()
                                  : s.cronExpression}
                            </code>
                            {s.cronDescription && (
                              <p className="mt-0.5 text-xs text-muted-foreground/70">
                                {s.cronDescription}
                              </p>
                            )}
                          </td>
                          <td className="px-5 py-3">
                            <code className="text-xs text-foreground">
                              {s.agentId}
                            </code>
                          </td>
                          <td className="px-5 py-3">
                            <StatusBadge schedule={s} />
                            {s.failCount > 0 && (
                              <span className="ms-1.5 text-xs text-amber-500">
                                ×{s.failCount}
                              </span>
                            )}
                          </td>
                          <td className="px-5 py-3 text-sm tabular-nums text-muted-foreground">
                            {/* Rendered in the SCHEDULE's zone — the label just
                                below states that zone, so the viewer's local
                                wall-clock here would contradict it. */}
                            {formatInstantInZone(
                              s.nextFire,
                              s.timeZone ?? DEFAULT_TIME_ZONE
                            )}
                            <span
                              className="mt-0.5 flex items-center gap-1 text-xs text-muted-foreground/60"
                              data-testid={`timezone-${s.id}`}
                            >
                              <Globe className="h-3 w-3" />
                              {s.timeZone ?? DEFAULT_TIME_ZONE}
                            </span>
                          </td>
                          <td className="px-5 py-3 text-sm tabular-nums text-muted-foreground">
                            {formatInstantInZone(
                              s.lastFired,
                              s.timeZone ?? DEFAULT_TIME_ZONE
                            )}
                          </td>
                          <td className="px-5 py-3">
                            <div className="flex items-center justify-end gap-1">
                              {/* Edit — hidden for HITL-timeout schedules
                                  (backend restricts their mutation to admins). */}
                              {!hitl && (
                                <button
                                  onClick={() => openEdit(s)}
                                  title={t("common.edit", "Edit")}
                                  className="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-primary/10 hover:text-primary"
                                  data-testid={`edit-${s.id}`}
                                >
                                  <Pencil className="h-4 w-4" />
                                </button>
                              )}

                              {/* Toggle Enable/Disable */}
                              <button
                                onClick={() => handleToggle(s)}
                                disabled={toggleMutation.isPending}
                                title={
                                  s.enabled
                                    ? t("schedules.disable", "Disable")
                                    : t("schedules.enable", "Enable")
                                }
                                className="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-primary/10 hover:text-primary disabled:opacity-50"
                                data-testid={`toggle-${s.id}`}
                              >
                                {s.enabled ? (
                                  <ToggleRight className="h-4 w-4 text-emerald-500" />
                                ) : (
                                  <ToggleLeft className="h-4 w-4" />
                                )}
                              </button>

                              {/* Fire Now — hidden for HITL-timeout schedules,
                                  which the backend refuses to fire (409). A
                                  lightweight inline confirm precedes the fire. */}
                              {!hitl &&
                                (confirmFireId === s.id ? (
                                  <span className="inline-flex items-center gap-1">
                                    <button
                                      onClick={() => doFire(s)}
                                      disabled={fireMutation.isPending}
                                      className="rounded-md bg-primary px-2 py-1 text-xs font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
                                      data-testid={`fire-confirm-${s.id}`}
                                    >
                                      {t("schedules.fireNow", "Fire Now")}
                                    </button>
                                    <button
                                      onClick={() => setConfirmFireId(null)}
                                      className="rounded-md border border-border px-2 py-1 text-xs font-medium text-foreground transition-colors hover:bg-muted"
                                      data-testid={`fire-cancel-${s.id}`}
                                    >
                                      {t("common.cancel", "Cancel")}
                                    </button>
                                  </span>
                                ) : (
                                  <button
                                    onClick={() => setConfirmFireId(s.id!)}
                                    title={t("schedules.fireNow", "Fire Now")}
                                    className="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-primary/10 hover:text-primary disabled:opacity-50"
                                    data-testid={`fire-${s.id}`}
                                  >
                                    <Play className="h-4 w-4" />
                                  </button>
                                ))}

                              {/* Retry (only for dead-lettered) */}
                              {s.fireStatus === "DEAD_LETTERED" && (
                                <button
                                  onClick={() => handleRetry(s.id!)}
                                  disabled={retryMutation.isPending}
                                  title={t("schedules.retry", "Retry")}
                                  className="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-amber-500/10 hover:text-amber-500 disabled:opacity-50"
                                  data-testid={`retry-${s.id}`}
                                >
                                  <RotateCcw className="h-4 w-4" />
                                </button>
                              )}

                              {/* Delete */}
                              {confirmDeleteId === s.id ? (
                                <div className="flex items-center gap-1">
                                  <button
                                    onClick={() => handleDelete(s.id!)}
                                    disabled={deleteMutation.isPending}
                                    className="rounded-md bg-red-500 px-2 py-1 text-xs font-medium text-white transition-colors hover:bg-red-600 disabled:opacity-50"
                                  >
                                    {t("common.delete", "Delete")}
                                  </button>
                                  <button
                                    onClick={() => setConfirmDeleteId(null)}
                                    className="rounded-md border border-border px-2 py-1 text-xs font-medium text-foreground transition-colors hover:bg-muted"
                                  >
                                    {t("common.cancel", "Cancel")}
                                  </button>
                                </div>
                              ) : (
                                <button
                                  onClick={() => setConfirmDeleteId(s.id!)}
                                  title={t("common.delete", "Delete")}
                                  className="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-red-500/10 hover:text-red-500 disabled:opacity-50"
                                  data-testid={`delete-${s.id}`}
                                >
                                  <Trash2 className="h-4 w-4" />
                                </button>
                              )}
                            </div>
                          </td>
                        </tr>

                        {/* Inline fire outcome */}
                        {outcome !== undefined && (
                          <tr data-testid={`fire-outcome-${s.id}`}>
                            <td colSpan={8} className="px-5 py-0">
                              <div className="mb-2 flex items-center justify-between gap-2 rounded-lg border border-border/50 bg-muted/30 px-3 py-2 text-xs">
                                <div className="flex flex-wrap items-center gap-2">
                                  <span className="font-medium text-muted-foreground">
                                    {t(
                                      "schedules.lastManualFire",
                                      "Last manual fire"
                                    )}
                                    :
                                  </span>
                                  {outcome ? (
                                    <>
                                      <FireStatusBadge status={outcome.status} />
                                      {outcome.cost != null && (
                                        <span className="text-muted-foreground">
                                          · {formatCost(outcome.cost)}
                                        </span>
                                      )}
                                      {outcome.conversationId && (
                                        <span className="text-muted-foreground">
                                          ·{" "}
                                          <ConversationCell
                                            conversationId={
                                              outcome.conversationId
                                            }
                                          />
                                        </span>
                                      )}
                                      {outcome.errorMessage && (
                                        <span className="text-red-400">
                                          · {outcome.errorMessage}
                                        </span>
                                      )}
                                    </>
                                  ) : (
                                    <span className="text-muted-foreground">
                                      {t(
                                        "schedules.outcomePending",
                                        "triggered — awaiting result"
                                      )}
                                    </span>
                                  )}
                                </div>
                                <button
                                  onClick={() => clearOutcome(s.id!)}
                                  title={t("common.dismiss", "Dismiss")}
                                  className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
                                  data-testid={`fire-outcome-clear-${s.id}`}
                                >
                                  <X className="h-3 w-3" />
                                </button>
                              </div>
                            </td>
                          </tr>
                        )}

                        {/* Expandable fire logs */}
                        <FireLogsRow scheduleId={s.id!} />
                      </Fragment>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* Create / Edit Dialog */}
      <ScheduleFormDialog
        open={showForm}
        editing={editing}
        onClose={() => setShowForm(false)}
      />
    </div>
  );
}
