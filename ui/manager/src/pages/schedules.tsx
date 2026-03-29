import { useState, useEffect, useCallback, Fragment } from "react";
import { useTranslation } from "react-i18next";
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
} from "lucide-react";
import { toast } from "sonner";
import {
  useSchedules,
  useCreateSchedule,
  useDeleteSchedule,
  useToggleSchedule,
  useFireNow,
  useRetryDeadLetter,
  useFireLogs,
} from "@/hooks/use-schedules";
import type {
  ScheduleConfiguration,
  ScheduleFireLog,
  TriggerType,
} from "@/lib/api/schedules";

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

function TypeBadge({ type }: { type: TriggerType }) {
  const { t } = useTranslation();
  return type === "HEARTBEAT" ? (
    <span className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2.5 py-0.5 text-xs font-semibold text-primary">
      <Timer className="h-3 w-3" />
      {t("schedules.typeHeartbeat", "Heartbeat")}
    </span>
  ) : (
    <span className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2.5 py-0.5 text-xs font-semibold text-primary">
      <CalendarClock className="h-3 w-3" />
      {t("schedules.typeCron", "Cron")}
    </span>
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
          <div className="mb-3 rounded-lg border border-border/50 bg-muted/30">
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
                    <th className="px-3 py-2 text-start font-medium">{t("schedules.logFired", "Fired")}</th>
                    <th className="px-3 py-2 text-start font-medium">
                      {t("schedules.logDuration", "Duration")}
                    </th>
                    <th className="px-3 py-2 text-start font-medium">
                      {t("schedules.logResult", "Result")}
                    </th>
                    <th className="px-3 py-2 text-start font-medium">{t("status.error", "Error")}</th>
                  </tr>
                </thead>
                <tbody>
                  {logs.map((log: ScheduleFireLog, i: number) => (
                    <tr
                      key={log.id ?? i}
                      className="border-b border-border/30"
                    >
                      <td className="px-3 py-1.5 tabular-nums text-foreground">
                        {new Date(log.firedAt).toLocaleString()}
                      </td>
                      <td className="px-3 py-1.5 tabular-nums text-muted-foreground">
                        {log.durationMs != null ? `${log.durationMs}ms` : "—"}
                      </td>
                      <td className="px-3 py-1.5">
                        {log.success ? (
                          <span className="text-emerald-500">✓</span>
                        ) : (
                          <span className="text-red-400">✗</span>
                        )}
                      </td>
                      <td
                        className="max-w-[300px] truncate px-3 py-1.5 text-red-400"
                        title={log.error}
                      >
                        {log.error ?? "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        )}
      </td>
    </tr>
  );
}

// ==================== Create Dialog ====================

function CreateScheduleDialog({
  open,
  onClose,
}: {
  open: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const createMutation = useCreateSchedule();
  const [triggerType, setTriggerType] = useState<TriggerType>("CRON");
  const [name, setName] = useState("");
  const [agentId, setAgentId] = useState("");
  const [cronExpression, setCronExpression] = useState("0 9 * * MON-FRI");
  const [heartbeatInterval, setHeartbeatInterval] = useState(300);
  const [message, setMessage] = useState("Hello");
  const [environment, setEnvironment] = useState("production");
  const [strategy, setStrategy] = useState<"new" | "persistent">("new");

  // Reset form when dialog opens
  const resetForm = useCallback(() => {
    setName("");
    setAgentId("");
    setTriggerType("CRON");
    setCronExpression("0 9 * * MON-FRI");
    setHeartbeatInterval(300);
    setMessage("Hello");
    setEnvironment("production");
    setStrategy("new");
  }, []);

  useEffect(() => {
    if (open) resetForm();
  }, [open, resetForm]);

  // ESC to close
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!open) return null;

  // Form validation
  const isValid =
    name.trim().length > 0 &&
    agentId.trim().length > 0 &&
    (triggerType === "CRON"
      ? cronExpression.trim().length > 0
      : heartbeatInterval >= 60);

  const handleCreate = () => {
    if (!isValid) return;
    const config: Partial<ScheduleConfiguration> = {
      name: name.trim(),
      triggerType,
      agentId: agentId.trim(),
      agentVersion: 0,
      environment,
      message,
      conversationStrategy:
        triggerType === "HEARTBEAT" ? "persistent" : strategy,
      enabled: true,
      ...(triggerType === "CRON"
        ? { cronExpression: cronExpression.trim() }
        : { heartbeatIntervalSeconds: heartbeatInterval }),
    };

    createMutation.mutate(config, {
      onSuccess: () => {
        toast.success(
          t("schedules.createSuccess", "Schedule created successfully")
        );
        onClose();
      },
      onError: () =>
        toast.error(
          t("schedules.createError", "Failed to create schedule")
        ),
    });
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      role="dialog"
      aria-modal="true"
      aria-labelledby="create-schedule-title"
    >
      <div
        className="absolute inset-0 bg-black/50 backdrop-blur-sm"
        onClick={onClose}
      />
      <div className="relative w-full max-w-lg rounded-2xl border border-border bg-card p-6 shadow-2xl">
        <h2 id="create-schedule-title" className="mb-4 text-lg font-bold text-foreground">
          {t("schedules.createTitle", "Create Schedule")}
        </h2>

        <div className="space-y-4">
          {/* Name */}
          <div>
            <label className="mb-1 block text-sm font-medium text-muted-foreground">
              {t("schedules.name", "Name")}
            </label>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t(
                "schedules.namePlaceholder",
                "e.g. Daily health check"
              )}
              className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground/50 focus:border-primary focus:outline-none"
            />
          </div>

          {/* Trigger Type Tabs */}
          <div>
            <label className="mb-1 block text-sm font-medium text-muted-foreground">
              {t("schedules.triggerType", "Trigger Type")}
            </label>
            <div className="flex gap-1 rounded-lg border border-border bg-muted/30 p-1">
              {(["CRON", "HEARTBEAT"] as TriggerType[]).map((tt) => (
                <button
                  key={tt}
                  onClick={() => {
                    setTriggerType(tt);
                    if (tt === "HEARTBEAT") setStrategy("persistent");
                  }}
                  className={`flex flex-1 items-center justify-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium transition-all ${
                    triggerType === tt
                      ? "bg-primary text-primary-foreground shadow-sm"
                      : "text-muted-foreground hover:text-foreground"
                  }`}
                >
                  {tt === "CRON" ? (
                    <CalendarClock className="h-3.5 w-3.5" />
                  ) : (
                    <Timer className="h-3.5 w-3.5" />
                  )}
                  {tt === "CRON" ? t("schedules.typeCron", "Cron") : t("schedules.typeHeartbeat", "Heartbeat")}
                </button>
              ))}
            </div>
          </div>

          {/* Cron / Interval */}
          {triggerType === "CRON" ? (
            <div>
              <label className="mb-1 block text-sm font-medium text-muted-foreground">
                {t("schedules.cronExpression", "Cron Expression")}
              </label>
              <input
                value={cronExpression}
                onChange={(e) => setCronExpression(e.target.value)}
                placeholder="0 9 * * MON-FRI"
                className="w-full rounded-lg border border-border bg-background px-3 py-2 font-mono text-sm text-foreground placeholder:text-muted-foreground/50 focus:border-primary focus:outline-none"
              />
              <p className="mt-1 text-xs text-muted-foreground">
                {t("schedules.cronHelp", "5-field format: minute hour day-of-month month day-of-week")}
              </p>
            </div>
          ) : (
            <div>
              <label className="mb-1 block text-sm font-medium text-muted-foreground">
                {t("schedules.interval", "Interval (seconds)")}
              </label>
              <input
                type="number"
                min={60}
                value={heartbeatInterval}
                onChange={(e) => setHeartbeatInterval(Number(e.target.value))}
                className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground focus:border-primary focus:outline-none"
              />
            </div>
          )}

          {/* Agent ID */}
          <div>
            <label className="mb-1 block text-sm font-medium text-muted-foreground">
              {t("schedules.agentId", "Agent ID")}
            </label>
            <input
              value={agentId}
              onChange={(e) => setAgentId(e.target.value)}
              placeholder={t(
                "schedules.agentIdPlaceholder",
                "Enter agent ID..."
              )}
              className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground/50 focus:border-primary focus:outline-none"
            />
          </div>

          {/* Environment */}
          <div>
            <label className="mb-1 block text-sm font-medium text-muted-foreground">
              {t("schedules.environment", "Environment")}
            </label>
            <select
              value={environment}
              onChange={(e) => setEnvironment(e.target.value)}
              className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground focus:border-primary focus:outline-none"
            >
              <option value="production">{t("schedules.envProduction", "Production")}</option>
              
              <option value="test">{t("schedules.envTest", "Test")}</option>
            </select>
          </div>

          {/* Message */}
          <div>
            <label className="mb-1 block text-sm font-medium text-muted-foreground">
              {t("schedules.message", "Message")}
            </label>
            <input
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              placeholder={t(
                "schedules.messagePlaceholder",
                "Message to send to agent"
              )}
              className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground/50 focus:border-primary focus:outline-none"
            />
          </div>

          {/* Conversation Strategy (only for CRON) */}
          {triggerType === "CRON" && (
            <div>
              <label className="mb-1 block text-sm font-medium text-muted-foreground">
                {t("schedules.conversationStrategy", "Conversation Strategy")}
              </label>
              <select
                value={strategy}
                onChange={(e) =>
                  setStrategy(e.target.value as "new" | "persistent")
                }
                className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground focus:border-primary focus:outline-none"
              >
                <option value="new">{t("schedules.strategyNew", "New (fresh conversation each fire)")}</option>
                <option value="persistent">
                  {t("schedules.strategyPersistent", "Persistent (reuse same conversation)")}
                </option>
              </select>
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
            onClick={handleCreate}
            disabled={!isValid || createMutation.isPending}
            className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
          >
            {createMutation.isPending
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
  const { data: schedules, isLoading } = useSchedules();
  const deleteMutation = useDeleteSchedule();
  const toggleMutation = useToggleSchedule();
  const fireMutation = useFireNow();
  const retryMutation = useRetryDeadLetter();
  const [showCreate, setShowCreate] = useState(false);
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);

  const total = schedules?.length ?? 0;
  const active = schedules?.filter((s) => s.enabled).length ?? 0;
  const failed =
    schedules?.filter(
      (s) => s.fireStatus === "FAILED" || s.fireStatus === "DEAD_LETTERED"
    ).length ?? 0;

  const soonest = schedules
    ?.filter((s) => s.enabled && s.nextFire)
    ?.sort((a, b) => (a.nextFire ?? 0) - (b.nextFire ?? 0))?.[0];

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

  const handleFire = (id: string) => {
    fireMutation.mutate(id, {
      onSuccess: () =>
        toast.success(t("schedules.fired", "Schedule fired successfully")),
      onError: () =>
        toast.error(t("schedules.fireError", "Failed to fire schedule")),
    });
  };

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
          onClick={() => setShowCreate(true)}
          className="flex items-center gap-1.5 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
          data-testid="create-schedule-btn"
        >
          <Plus className="h-4 w-4" />
          {t("schedules.create", "Create Schedule")}
        </button>
      </div>

      {/* Status Cards */}
      {isLoading ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
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
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
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
              {soonest?.nextFire
                ? new Date(soonest.nextFire).toLocaleString()
                : "—"}
            </p>
            {soonest && (
              <p className="mt-0.5 truncate text-xs text-muted-foreground">
                {soonest.name}
              </p>
            )}
          </div>
        </div>
      )}

      {/* Schedule Table */}
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
                {schedules.map((s) => (
                  <Fragment key={s.id}>
                    <tr
                      className="border-b border-border/50 transition-colors hover:bg-muted/30"
                    >
                      <td className="px-5 py-3">
                        <span className="font-medium text-foreground">
                          {s.name}
                        </span>
                      </td>
                      <td className="px-5 py-3">
                        <TypeBadge type={s.triggerType} />
                      </td>
                      <td className="px-5 py-3">
                        <code className="text-xs text-muted-foreground">
                          {s.triggerType === "CRON"
                            ? s.cronExpression
                            : `Every ${s.heartbeatIntervalSeconds}s`}
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
                        {s.nextFire
                          ? new Date(s.nextFire).toLocaleString()
                          : "—"}
                      </td>
                      <td className="px-5 py-3 text-sm tabular-nums text-muted-foreground">
                        {s.lastFired
                          ? new Date(s.lastFired).toLocaleString()
                          : "—"}
                      </td>
                      <td className="px-5 py-3">
                        <div className="flex items-center justify-end gap-1">
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

                          {/* Fire Now */}
                          <button
                            onClick={() => handleFire(s.id!)}
                            disabled={fireMutation.isPending}
                            title={t("schedules.fireNow", "Fire Now")}
                            className="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-primary/10 hover:text-primary disabled:opacity-50"
                            data-testid={`fire-${s.id}`}
                          >
                            <Play className="h-4 w-4" />
                          </button>

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
                    {/* Expandable fire logs */}
                    <FireLogsRow key={`logs-${s.id}`} scheduleId={s.id!} />
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Create Dialog */}
      <CreateScheduleDialog
        open={showCreate}
        onClose={() => setShowCreate(false)}
      />
    </div>
  );
}
