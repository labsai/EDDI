import { useState, useEffect, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import {
  Activity,
  Bot,
  ArrowLeft,
  RefreshCw,
  Circle,
  Clock,
  CheckCircle2,
  AlertTriangle,
  HandMetal,
  Trash2,
  OctagonX,
} from "lucide-react";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { cn } from "@/lib/utils";
import {
  useActiveConversations,
  useEndActiveConversations,
  usePurgeEndedConversations,
} from "@/hooks/use-conversations";
import { useAgentVersions } from "@/hooks/use-agents";
import type { ConversationState, ConversationStatus } from "@/lib/api/conversations";
import { AgentPicker } from "@/components/shared/agent-picker";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";

const stateStyles: Record<ConversationState, { icon: typeof Circle; color: string; bg: string }> = {
  READY: { icon: Circle, color: "text-emerald-500", bg: "bg-emerald-500/10" },
  IN_PROGRESS: { icon: Clock, color: "text-amber-500", bg: "bg-amber-500/10" },
  ERROR: { icon: AlertTriangle, color: "text-destructive", bg: "bg-destructive/10" },
  ENDED: { icon: CheckCircle2, color: "text-muted-foreground", bg: "bg-muted" },
  EXECUTION_INTERRUPTED: { icon: AlertTriangle, color: "text-amber-500", bg: "bg-amber-500/10" },
  AWAITING_HUMAN: { icon: HandMetal, color: "text-orange-500", bg: "bg-orange-500/10" },
};

export function ConversationMonitoringPage() {
  const { t } = useTranslation();

  const [agentId, setAgentId] = useState("");
  const [version, setVersion] = useState<number | undefined>(undefined);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [confirmEnd, setConfirmEnd] = useState(false);

  // Purge (admin) state
  const [purgeDays, setPurgeDays] = useState(30);
  const [confirmPurge, setConfirmPurge] = useState(false);

  const { data: versions } = useAgentVersions(agentId);
  // Dedupe by version number — one <option> per distinct version.
  const versionOptions = useMemo(
    () => (versions ? [...new Map(versions.map((v) => [v.version, v])).values()] : []),
    [versions]
  );

  // Default to the latest version once versions load for the chosen agent.
  useEffect(() => {
    if (version == null && versionOptions.length > 0) {
      setVersion(versionOptions[0]!.version);
    }
  }, [versionOptions, version]);

  // Reset version + selection whenever the agent changes.
  useEffect(() => {
    setVersion(undefined);
    setSelected(new Set());
  }, [agentId]);

  const {
    data: active,
    isLoading,
    isError,
    isFetching,
    refetch,
  } = useActiveConversations(agentId, version);

  const endMutation = useEndActiveConversations();
  const purgeMutation = usePurgeEndedConversations();

  // Prune selection to ids that still exist in the latest poll.
  useEffect(() => {
    if (!active) return;
    setSelected((prev) => {
      const live = new Set(active.map((c) => c.conversationId));
      const next = new Set([...prev].filter((id) => live.has(id)));
      return next.size === prev.size ? prev : next;
    });
  }, [active]);

  const rows = useMemo(() => active ?? [], [active]);
  const allSelected = rows.length > 0 && rows.every((r) => selected.has(r.conversationId));
  const selectedStatuses = useMemo(
    () => rows.filter((r) => selected.has(r.conversationId)),
    [rows, selected]
  );
  const pausedSelectedCount = selectedStatuses.filter(
    (s) => s.conversationState === "AWAITING_HUMAN"
  ).length;

  function toggleAll() {
    setSelected((prev) =>
      prev.size === rows.length && rows.length > 0
        ? new Set()
        : new Set(rows.map((r) => r.conversationId))
    );
  }

  function toggleOne(id: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function confirmEndSelected() {
    const statuses: ConversationStatus[] = selectedStatuses;
    endMutation.mutate(statuses, {
      onSuccess: () => {
        toast.success(
          t("conversations.endSuccess", "Ended {{count}} conversation(s)", {
            count: statuses.length,
          })
        );
        setSelected(new Set());
        setConfirmEnd(false);
      },
      onError: (err) => toast.error(getErrorMessage(err)),
    });
  }

  function confirmPurgeEnded() {
    purgeMutation.mutate(purgeDays, {
      onSuccess: (count) => {
        toast.success(
          t("conversations.purgeSuccess", "Purged {{count}} ended conversation(s)", {
            count: count ?? 0,
          })
        );
        setConfirmPurge(false);
      },
      onError: (err) => toast.error(getErrorMessage(err)),
    });
  }

  const ready = !!agentId && version != null;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <Activity className="h-8 w-8 text-primary" />
            {t("conversations.monitorTitle", "Active Conversations")}
          </h1>
          <p className="mt-1 text-muted-foreground">
            {t(
              "conversations.monitorSubtitle",
              "Monitor and bulk-manage in-flight conversations per agent."
            )}
          </p>
        </div>
        <Link
          to="/manage/conversations"
          className="inline-flex items-center gap-2 rounded-lg border border-input bg-background px-3 py-2 text-sm font-medium text-foreground transition-colors hover:bg-secondary/50"
        >
          <ArrowLeft className="h-4 w-4" />
          {t("conversations.backToList", "Back to conversations")}
        </Link>
      </div>

      {/* Agent + version selector */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="flex items-center gap-1.5 sm:w-80">
          <Bot className="h-4 w-4 shrink-0 text-muted-foreground" />
          <AgentPicker
            value={agentId}
            onChange={setAgentId}
            placeholder={t("conversations.selectAgentToMonitor", "Select an agent")}
          />
        </div>
        {agentId && versionOptions.length > 0 && (
          <select
            value={version ?? ""}
            onChange={(e) => setVersion(e.target.value ? Number(e.target.value) : undefined)}
            aria-label={t("conversations.filterByVersion", "Filter by agent version")}
            data-testid="monitor-version-select"
            className="h-9 rounded-md border border-input bg-background px-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          >
            {versionOptions.map((v) => (
              <option key={v.version} value={v.version}>
                {t("conversations.version", "v{{version}}", { version: v.version })}
              </option>
            ))}
          </select>
        )}
        {ready && (
          <Button
            variant="outline"
            size="sm"
            onClick={() => refetch()}
            disabled={isFetching}
            data-testid="monitor-refresh"
            aria-label={t("common.refresh", "Refresh")}
          >
            <RefreshCw className={cn("h-4 w-4", isFetching && "animate-spin")} />
            {t("common.refresh", "Refresh")}
          </Button>
        )}
      </div>

      {/* Bulk action bar */}
      {ready && (
        <div className="flex flex-wrap items-center justify-between gap-3">
          <span className="text-sm text-muted-foreground" data-testid="selection-count">
            {t("conversations.selectedCount", "{{count}} selected", {
              count: selected.size,
            })}
          </span>
          <Button
            variant="destructive"
            size="sm"
            onClick={() => setConfirmEnd(true)}
            disabled={selected.size === 0 || endMutation.isPending}
            data-testid="end-selected"
          >
            <OctagonX className="h-4 w-4" />
            {t("conversations.endSelected", "End selected")}
          </Button>
        </div>
      )}

      {/* Content */}
      {!ready && (
        <EmptyState
          icon={Activity}
          title={t("conversations.selectAgentPrompt", "Select an agent to monitor")}
          description={t(
            "conversations.selectAgentPromptDesc",
            "Active conversations are listed per agent and version."
          )}
        />
      )}

      {ready && isLoading && (
        <div className="overflow-hidden rounded-xl border bg-card shadow-sm">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="flex items-center gap-4 border-b border-border px-5 py-4">
              <Skeleton className="h-4 w-4" />
              <Skeleton className="h-4 w-40" />
              <Skeleton className="h-5 w-20 rounded-full" />
              <Skeleton className="ms-auto h-4 w-32" />
            </div>
          ))}
        </div>
      )}

      {ready && isError && (
        <ErrorState
          message={t("common.error")}
          onRetry={() => refetch()}
          retryLabel={t("common.retry")}
        />
      )}

      {ready && !isLoading && !isError && rows.length === 0 && (
        <EmptyState
          icon={CheckCircle2}
          title={t("conversations.noActive", "No active conversations")}
          description={t(
            "conversations.noActiveDesc",
            "This agent version has no in-flight conversations right now."
          )}
        />
      )}

      {ready && !isLoading && !isError && rows.length > 0 && (
        <div
          className="overflow-hidden rounded-xl border bg-card shadow-sm"
          data-testid="active-conversation-list"
        >
          <table className="w-full">
            <thead>
              <tr className="border-b border-border bg-secondary/50">
                <th className="w-10 px-5 py-3 text-start">
                  <input
                    type="checkbox"
                    className="h-4 w-4 accent-primary"
                    checked={allSelected}
                    onChange={toggleAll}
                    aria-label={t("conversations.selectAll", "Select all")}
                    data-testid="select-all"
                  />
                </th>
                <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                  {t("conversations.id")}
                </th>
                <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                  {t("conversations.state")}
                </th>
                <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                  {t("conversations.lastActivity")}
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {rows.map((row) => {
                const config = stateStyles[row.conversationState] ?? stateStyles.READY;
                const StateIcon = config.icon;
                const isChecked = selected.has(row.conversationId);
                return (
                  <tr
                    key={row.conversationId}
                    className={cn(
                      "transition-colors hover:bg-secondary/30",
                      isChecked && "bg-primary/5"
                    )}
                  >
                    <td className="px-5 py-3">
                      <input
                        type="checkbox"
                        className="h-4 w-4 accent-primary"
                        checked={isChecked}
                        onChange={() => toggleOne(row.conversationId)}
                        aria-label={t("conversations.selectOne", "Select {{id}}", {
                          id: row.conversationId,
                        })}
                        data-testid={`select-${row.conversationId}`}
                      />
                    </td>
                    <td className="px-5 py-3">
                      <Link
                        to={`/manage/conversationview/${row.conversationId}`}
                        className="font-mono text-sm font-medium text-foreground hover:text-primary transition-colors"
                        title={row.conversationId}
                      >
                        {row.conversationId.length > 24
                          ? `${row.conversationId.slice(0, 24)}…`
                          : row.conversationId}
                      </Link>
                    </td>
                    <td className="px-5 py-3">
                      <span
                        className={cn(
                          "inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium",
                          config.bg,
                          config.color
                        )}
                      >
                        <StateIcon className="h-3 w-3" />
                        {row.conversationState}
                      </span>
                    </td>
                    <td className="px-5 py-3">
                      <span className="text-sm text-muted-foreground">
                        {row.lastInteraction
                          ? new Date(row.lastInteraction).toLocaleString()
                          : "—"}
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Admin — bulk purge of ENDED conversations */}
      <div className="rounded-xl border border-border bg-card p-5 shadow-sm">
        <h2 className="flex items-center gap-2 text-lg font-semibold text-foreground">
          <Trash2 className="h-5 w-5 text-destructive" />
          {t("conversations.purgeTitle", "Purge ended conversations")}
        </h2>
        <p className="mt-1 text-sm text-muted-foreground">
          {t(
            "conversations.purgeDesc",
            "Permanently delete ENDED conversations whose last activity is older than the given number of days."
          )}
        </p>
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <label htmlFor="purge-days" className="text-sm text-foreground">
            {t("conversations.olderThanDays", "Older than (days)")}
          </label>
          <input
            id="purge-days"
            type="number"
            min={0}
            value={purgeDays}
            onChange={(e) => setPurgeDays(Math.max(0, Number(e.target.value)))}
            data-testid="purge-days"
            className="h-9 w-24 rounded-md border border-input bg-background px-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
          <Button
            variant="destructive"
            size="sm"
            onClick={() => setConfirmPurge(true)}
            disabled={purgeMutation.isPending}
            data-testid="purge-ended"
          >
            <Trash2 className="h-4 w-4" />
            {t("conversations.purgeAction", "Purge")}
          </Button>
        </div>
      </div>

      {/* End-selected confirmation */}
      <AlertDialog
        open={confirmEnd}
        onOpenChange={setConfirmEnd}
        variant="warning"
        title={t("conversations.confirmEndTitle", "End selected conversations?")}
        description={t(
          "conversations.confirmEndDesc",
          "{{count}} conversation(s) will be set to ENDED. Paused (Awaiting Human) conversations are ended safely through the approval-aware path.",
          { count: selected.size }
        )}
        confirmLabel={t("conversations.endSelected", "End selected")}
        cancelLabel={t("common.cancel")}
        onConfirm={confirmEndSelected}
        isPending={endMutation.isPending}
      >
        {pausedSelectedCount > 0 && (
          <p className="rounded-lg border border-orange-500/30 bg-orange-500/10 p-3 text-sm text-foreground">
            {t(
              "conversations.pausedNote",
              "{{count}} of the selected conversation(s) are Awaiting Human and will have their pending approval cancelled.",
              { count: pausedSelectedCount }
            )}
          </p>
        )}
      </AlertDialog>

      {/* Purge confirmation */}
      <AlertDialog
        open={confirmPurge}
        onOpenChange={setConfirmPurge}
        title={t("conversations.confirmPurgeTitle", "Purge ended conversations?")}
        description={t(
          "conversations.confirmPurgeDesc",
          "This permanently deletes ENDED conversations older than {{days}} day(s), including their stored memory and attachments. This cannot be undone.",
          { days: purgeDays }
        )}
        confirmLabel={t("conversations.purgeAction", "Purge")}
        cancelLabel={t("common.cancel")}
        onConfirm={confirmPurgeEnded}
        isPending={purgeMutation.isPending}
      />
    </div>
  );
}
