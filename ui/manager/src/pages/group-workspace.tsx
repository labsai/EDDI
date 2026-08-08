import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useParams, useSearchParams } from "react-router-dom";
import {
  Boxes, ClipboardList, Plus, Trash2, RefreshCw, Users2, DollarSign, CheckCircle2, Clock,
} from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ErrorState } from "@/components/shared/error-state";
import { BackLink } from "@/components/shared/back-link";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { getErrorMessage } from "@/lib/api-client";
import { cn, formatUsd } from "@/lib/utils";
import { useGroup } from "@/hooks/use-groups";
import {
  useGroupWorkspace,
  useAddWorkspaceBacklogTask,
  useAddWorkspaceCadence,
  useDeleteWorkspaceCadence,
} from "@/hooks/use-group-workspace";
import {
  WORKSPACE_MAX_TASK_SUBJECT_LENGTH,
  WORKSPACE_MAX_TASK_DESCRIPTION_LENGTH,
  NO_RUNNING_DISCUSSION,
} from "@/lib/api/group-workspace";

const STATUS_BADGE: Record<string, string> = {
  PENDING: "bg-muted text-muted-foreground",
  ASSIGNED: "bg-blue-500/10 text-blue-600",
  IN_PROGRESS: "bg-amber-500/10 text-amber-600",
  COMPLETED: "bg-sky-500/10 text-sky-600",
  VERIFIED: "bg-emerald-500/10 text-emerald-600",
  FAILED: "bg-destructive/10 text-destructive",
  BLOCKED: "bg-muted text-muted-foreground",
  AWAITING_APPROVAL: "bg-orange-500/10 text-orange-600",
};

export function GroupWorkspacePage() {
  const { id: groupId } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const { t } = useTranslation();
  // Same convention as the group detail page: the version rides on the URL.
  // Hardcoding 1 here read the group's FIRST version, so a renamed group showed
  // its original name in this page's header and back-link.
  const version = Number(searchParams.get("version")) || 1;
  const { data: groupConfig } = useGroup(groupId || "", version);
  const { data: workspace, isLoading, isError, refetch } = useGroupWorkspace(groupId);
  const addTask = useAddWorkspaceBacklogTask(groupId);
  const addCadence = useAddWorkspaceCadence(groupId);
  const deleteCadence = useDeleteWorkspaceCadence(groupId);

  const [subject, setSubject] = useState("");
  const [description, setDescription] = useState("");
  const [priority, setPriority] = useState(0);
  const [taskError, setTaskError] = useState<string | null>(null);

  const [cron, setCron] = useState("");
  const [timeZone, setTimeZone] = useState("UTC");
  const [inputTemplate, setInputTemplate] = useState("");
  const [maxPerRun, setMaxPerRun] = useState(5);
  const [maxCostPerRun, setMaxCostPerRun] = useState("");
  const [cadenceError, setCadenceError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);

  function handleAddTask() {
    setTaskError(null);
    if (!subject.trim()) return;
    addTask.mutate(
      { subject: subject.trim(), description: description.trim() || undefined, priority },
      {
        onSuccess: () => {
          toast.success(t("groupWorkspace.taskAdded", "Task added to the backlog"));
          setSubject("");
          setDescription("");
          setPriority(0);
        },
        onError: (err) => setTaskError(getErrorMessage(err)),
      },
    );
  }

  function handleAddCadence() {
    setCadenceError(null);
    if (!cron.trim()) return;
    // The backend does not validate maxCostPerRun at all (nullable Double, no
    // bounds), so a negative or non-numeric value would be persisted verbatim
    // and then min()'d against the group ceiling — silently capping every run
    // at a nonsense budget. Refuse it here instead.
    const parsedCost = maxCostPerRun.trim() ? Number(maxCostPerRun) : undefined;
    if (parsedCost !== undefined && (!Number.isFinite(parsedCost) || parsedCost < 0)) {
      setCadenceError(
        t("groupWorkspace.maxCostInvalid", "Max cost per run must be a number of 0 or more."),
      );
      return;
    }
    addCadence.mutate(
      {
        cronExpression: cron.trim(),
        timeZone: timeZone.trim() || "UTC",
        inputTemplate: inputTemplate.trim() || undefined,
        maxBacklogTasksPerRun: maxPerRun,
        maxCostPerRun: parsedCost,
      },
      {
        onSuccess: () => {
          toast.success(t("groupWorkspace.cadenceAdded", "Cadence added"));
          setCron("");
          setInputTemplate("");
        },
        onError: (err) => setCadenceError(getErrorMessage(err)),
      },
    );
  }

  if (isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-1/3" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  if (isError || !workspace) {
    return (
      <div className="space-y-4">
        <BackLink to={`/manage/groups/${groupId}?version=${version}`} label={t("groupWorkspace.backToGroup", "Back to group")} />
        <ErrorState message={t("common.error")} onRetry={() => refetch()} retryLabel={t("common.retry")} />
      </div>
    );
  }

  const isRunning = workspace.runningDiscussionId !== NO_RUNNING_DISCUSSION;
  const inputCls =
    "w-full rounded-lg border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring";

  return (
    <div className="space-y-6">
      <div className="space-y-2">
        <BackLink to={`/manage/groups/${groupId}?version=${version}`} label={groupConfig?.name || t("groupWorkspace.backToGroup", "Back to group")} />
        <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
          <Boxes className="h-8 w-8 text-primary" />
          {t("groupWorkspace.title", "Standing Team Workspace")}
        </h1>
        <p className="text-muted-foreground">
          {t(
            "groupWorkspace.subtitle",
            "A persistent backlog and recurring cadences this group runs unattended, on a schedule.",
          )}
        </p>
      </div>

      {isRunning && (
        <div
          className="flex items-center gap-2 rounded-lg border border-amber-500/30 bg-amber-500/5 p-3 text-sm text-amber-700 dark:text-amber-400"
          data-testid="workspace-running-banner"
        >
          <RefreshCw className="h-4 w-4 animate-spin" aria-hidden="true" />
          {t("groupWorkspace.cadenceRunning", "A cadence-run discussion is currently in progress.")}
        </div>
      )}

      {/* Metrics */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4" data-testid="workspace-metrics">
        <MetricCard icon={<ClipboardList className="h-4 w-4" />} label={t("groupWorkspace.discussions", "Discussions")} value={String(workspace.metrics.discussions)} />
        <MetricCard icon={<CheckCircle2 className="h-4 w-4" />} label={t("groupWorkspace.tasksVerified", "Tasks Verified")} value={String(workspace.metrics.tasksVerified)} />
        <MetricCard icon={<DollarSign className="h-4 w-4" />} label={t("groupWorkspace.totalCost", "Total Cost")} value={formatUsd(workspace.metrics.totalCost)} />
        <MetricCard
          icon={<Clock className="h-4 w-4" />}
          label={t("groupWorkspace.lastRun", "Last Run")}
          value={workspace.metrics.lastRunAt ? new Date(workspace.metrics.lastRunAt).toLocaleString() : t("groupWorkspace.never", "Never")}
        />
      </div>

      {Object.keys(workspace.metrics.perMemberStats).length > 0 && (
        <div className="rounded-xl border border-border bg-card p-4">
          <h3 className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-foreground">
            <Users2 className="h-4 w-4" />
            {t("groupWorkspace.perMemberStats", "Per-member reliability")}
          </h3>
          <table className="w-full text-sm">
            <thead>
              <tr className="text-start text-xs text-muted-foreground">
                <th scope="col" className="py-1 text-start font-medium">{t("groupWorkspace.member", "Member")}</th>
                <th scope="col" className="py-1 text-end font-medium">{t("groupWorkspace.verified", "Verified")}</th>
                <th scope="col" className="py-1 text-end font-medium">{t("groupWorkspace.failed", "Failed")}</th>
              </tr>
            </thead>
            <tbody>
              {Object.entries(workspace.metrics.perMemberStats).map(([agentId, stats]) => (
                <tr key={agentId} className="border-t border-border">
                  <td className="py-1.5 font-mono text-xs text-foreground">{agentId}</td>
                  <td className="py-1.5 text-end text-emerald-600">{stats.tasksVerified}</td>
                  <td className="py-1.5 text-end text-destructive">{stats.tasksFailed}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Backlog */}
      <div className="rounded-xl border border-border bg-card p-4" data-testid="workspace-backlog">
        <h3 className="mb-3 flex items-center justify-between text-sm font-semibold text-foreground">
          <span>{t("groupWorkspace.backlogTitle", "Backlog ({{count}})", { count: workspace.backlog.tasks.length })}</span>
        </h3>

        <div className="mb-3 space-y-2">
          {workspace.backlog.tasks.length === 0 ? (
            <p className="text-xs text-muted-foreground">{t("groupWorkspace.backlogEmpty", "No backlog tasks yet.")}</p>
          ) : (
            workspace.backlog.tasks.map((task) => (
              <div key={task.id} className="rounded-lg border border-border bg-secondary/20 p-2.5" data-testid={`backlog-task-${task.id}`}>
                <div className="flex items-center gap-2">
                  <span className="flex-1 text-xs font-medium text-foreground">{task.subject}</span>
                  <span className={cn("rounded-full px-1.5 py-0 text-[9px] font-medium", STATUS_BADGE[task.status] ?? "bg-muted")}>
                    {task.status}
                  </span>
                  <span className="text-[10px] text-muted-foreground">P{task.priority}</span>
                </div>
                {task.description && <p className="mt-1 text-[10px] text-muted-foreground line-clamp-2">{task.description}</p>}
              </div>
            ))
          )}
        </div>

        <div className="space-y-2 border-t border-border pt-3">
          <input
            value={subject}
            onChange={(e) => setSubject(e.target.value)}
            placeholder={t("groupWorkspace.taskSubjectPlaceholder", "Task subject")}
            maxLength={WORKSPACE_MAX_TASK_SUBJECT_LENGTH}
            className={inputCls}
            data-testid="workspace-task-subject"
          />
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder={t("groupWorkspace.taskDescriptionPlaceholder", "Description (optional)")}
            maxLength={WORKSPACE_MAX_TASK_DESCRIPTION_LENGTH}
            rows={2}
            className={cn(inputCls, "resize-none")}
            data-testid="workspace-task-description"
          />
          <div className="flex items-center gap-2">
            <label htmlFor="workspace-task-priority" className="text-xs text-muted-foreground">
              {t("groupWorkspace.priority", "Priority")}
            </label>
            <input
              id="workspace-task-priority"
              type="number"
              value={priority}
              onChange={(e) => setPriority(Number(e.target.value) || 0)}
              className="w-20 rounded-lg border border-input bg-background px-2 py-1 text-sm"
              data-testid="workspace-task-priority"
            />
            <Button
              size="sm"
              className="ms-auto"
              onClick={handleAddTask}
              disabled={!subject.trim() || addTask.isPending}
              data-testid="workspace-add-task"
            >
              <Plus className="h-3.5 w-3.5" />
              {t("groupWorkspace.addTask", "Add Task")}
            </Button>
          </div>
          {taskError && <p className="text-xs text-destructive" data-testid="workspace-task-error">{taskError}</p>}
        </div>
      </div>

      {/* Cadences */}
      <div className="rounded-xl border border-border bg-card p-4" data-testid="workspace-cadences">
        <h3 className="mb-3 text-sm font-semibold text-foreground">
          {t("groupWorkspace.cadencesTitle", "Cadences ({{count}})", { count: workspace.cadences.length })}
        </h3>

        <div className="mb-3 space-y-2">
          {workspace.cadences.length === 0 ? (
            <p className="text-xs text-muted-foreground">{t("groupWorkspace.cadencesEmpty", "No recurring cadences configured yet.")}</p>
          ) : (
            workspace.cadences.map((cadence) => (
              <div
                key={cadence.cadenceId}
                className="flex items-center gap-2 rounded-lg border border-border bg-secondary/20 p-2.5"
                data-testid={`cadence-${cadence.cadenceId}`}
              >
                <div className="min-w-0 flex-1 text-xs">
                  <p className="font-mono text-foreground">{cadence.cadenceId}</p>
                  <p className="text-muted-foreground">
                    {t("groupWorkspace.cadenceSummary", "up to {{max}} task(s)/run", { max: cadence.maxBacklogTasksPerRun })}
                    {cadence.maxCostPerRun != null && ` · ${formatUsd(cadence.maxCostPerRun)}/run`}
                    {` · ${t("groupWorkspace.createdBy", "by {{who}}", { who: cadence.createdBy })}`}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => setDeleteTarget(cadence.cadenceId)}
                  className="rounded-md p-1.5 text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors"
                  title={t("common.delete")}
                  aria-label={t("common.delete")}
                  data-testid={`delete-cadence-${cadence.cadenceId}`}
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              </div>
            ))
          )}
        </div>

        <div className="space-y-2 border-t border-border pt-3">
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label htmlFor="workspace-cron" className="mb-0.5 block text-[10px] text-muted-foreground">
                {t("groupWorkspace.cronLabel", "Cron expression")}
              </label>
              <input
                id="workspace-cron"
                value={cron}
                onChange={(e) => setCron(e.target.value)}
                placeholder={t("groupWorkspace.cronPlaceholder", "e.g. 0 9 * * MON")}
                className={inputCls}
                data-testid="workspace-cron-input"
              />
            </div>
            <div>
              <label htmlFor="workspace-timezone" className="mb-0.5 block text-[10px] text-muted-foreground">
                {t("groupWorkspace.timeZoneLabel", "Time zone")}
              </label>
              <input
                id="workspace-timezone"
                value={timeZone}
                onChange={(e) => setTimeZone(e.target.value)}
                placeholder="UTC"
                className={inputCls}
                data-testid="workspace-timezone-input"
              />
            </div>
          </div>
          <textarea
            value={inputTemplate}
            onChange={(e) => setInputTemplate(e.target.value)}
            placeholder={t("groupWorkspace.inputTemplatePlaceholder", "Prompt template for each run (optional — a plain backlog listing is used otherwise)")}
            rows={2}
            className={cn(inputCls, "resize-none")}
            data-testid="workspace-input-template"
          />
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label htmlFor="workspace-max-per-run" className="mb-0.5 block text-[10px] text-muted-foreground">
                {t("groupWorkspace.maxPerRunLabel", "Max tasks / run")}
              </label>
              <input
                id="workspace-max-per-run"
                type="number"
                min={1}
                value={maxPerRun}
                onChange={(e) => setMaxPerRun(Number(e.target.value) || 1)}
                className={inputCls}
                data-testid="workspace-max-per-run"
              />
            </div>
            <div>
              <label htmlFor="workspace-max-cost" className="mb-0.5 block text-[10px] text-muted-foreground">
                {t("groupWorkspace.maxCostLabel", "Max cost / run ($, optional)")}
              </label>
              <input
                id="workspace-max-cost"
                type="number"
                min={0}
                step="0.01"
                value={maxCostPerRun}
                onChange={(e) => setMaxCostPerRun(e.target.value)}
                placeholder={t("groupWorkspace.maxCostPlaceholder", "group default")}
                className={inputCls}
                data-testid="workspace-max-cost"
              />
            </div>
          </div>
          <Button
            size="sm"
            onClick={handleAddCadence}
            disabled={!cron.trim() || addCadence.isPending}
            data-testid="workspace-add-cadence"
          >
            <Plus className="h-3.5 w-3.5" />
            {t("groupWorkspace.addCadence", "Add Cadence")}
          </Button>
          {cadenceError && <p className="text-xs text-destructive" data-testid="workspace-cadence-error">{cadenceError}</p>}
        </div>
      </div>

      <AlertDialog
        open={deleteTarget !== null}
        onOpenChange={(open) => !open && setDeleteTarget(null)}
        title={t("groupWorkspace.confirmDeleteCadenceTitle", "Delete this cadence?")}
        description={t("groupWorkspace.confirmDeleteCadenceDesc", "This stops its scheduled runs. Already-run discussions are not affected.")}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        variant="destructive"
        isPending={deleteCadence.isPending}
        onConfirm={() => {
          if (deleteTarget) {
            deleteCadence.mutate(deleteTarget, {
              onSuccess: () => toast.success(t("groupWorkspace.cadenceDeleted", "Cadence deleted")),
              onError: (err) => toast.error(getErrorMessage(err)),
            });
          }
          setDeleteTarget(null);
        }}
      />
    </div>
  );
}

function MetricCard({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="rounded-xl border border-border bg-card p-3">
      <div className="flex items-center gap-1.5 text-[10px] font-medium uppercase tracking-wider text-muted-foreground">
        {icon}
        {label}
      </div>
      <p className="mt-1 text-lg font-bold text-foreground">{value}</p>
    </div>
  );
}
