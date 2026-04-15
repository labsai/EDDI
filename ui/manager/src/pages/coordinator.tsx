import { useState, useEffect, useRef, useMemo, useCallback } from "react";
import { useOnboarding } from "@/hooks/use-onboarding";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import {
  Activity,
  Trash2,
  RotateCcw,
  Server,
  Cloud,
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  RefreshCw,
  Gauge,
  Eye,
  Clock,
  TrendingUp,
  ExternalLink,
} from "lucide-react";
import { StreamBadge } from "@/components/ui/stream-badge";
import { toast } from "sonner";
import {
  useCoordinatorStatus,
  useDeadLetters,
  useReplayDeadLetter,
  useDiscardDeadLetter,
  usePurgeDeadLetters,
  useCoordinatorSSE,
} from "@/hooks/use-coordinator";

// ─── Helpers ─────────────────────────────────────────────────────────────────

function formatTime(iso: string): string {
  try {
    return new Intl.DateTimeFormat(undefined, {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    }).format(new Date(iso));
  } catch {
    return iso;
  }
}

// ─── Main Page ───────────────────────────────────────────────────────────────

export function CoordinatorPage() {
  const { t } = useTranslation();

  const maybeAutoStart = useOnboarding((s) => s.maybeAutoStart);
  useEffect(() => { const t = setTimeout(() => maybeAutoStart("coordinator"), 500); return () => clearTimeout(t); }, [maybeAutoStart]);

  const { data: status, isLoading: statusLoading, refetch: refetchStatus } = useCoordinatorStatus();
  const { data: deadLetters, isLoading: dlLoading, refetch: refetchDL } = useDeadLetters();
  const { liveStatus, sseConnected, eventHistory } = useCoordinatorSSE();
  const replayMutation = useReplayDeadLetter();
  const discardMutation = useDiscardDeadLetter();
  const purgeMutation = usePurgeDeadLetters();
  const [confirmPurge, setConfirmPurge] = useState(false);
  const [refreshInterval, setRefreshInterval] = useState(10);
  const [expandedPayloads, setExpandedPayloads] = useState<Set<string>>(new Set());

  // Use live SSE status if available, otherwise fall back to polling
  const currentStatus = liveStatus ?? status;

  const isNats = currentStatus?.coordinatorType === "nats";
  const isConnected = currentStatus?.connected ?? false;

  // Auto-refresh status polling
  const intervalRef = useRef<number | null>(null);

  useEffect(() => {
    if (intervalRef.current) clearInterval(intervalRef.current);
    intervalRef.current = window.setInterval(() => {
      refetchStatus();
      refetchDL();
    }, refreshInterval * 1000);
    return () => { if (intervalRef.current) clearInterval(intervalRef.current); };
  }, [refreshInterval, refetchStatus, refetchDL]);

  // Throughput rate — approximate tasks/sec from totalProcessed
  const prevProcessed = useRef<{ count: number; time: number } | null>(null);
  const [throughput, setThroughput] = useState<number | null>(null);

  useEffect(() => {
    if (!currentStatus) return;
    const now = Date.now();
    if (prevProcessed.current) {
      const dt = (now - prevProcessed.current.time) / 1000;
      if (dt > 0) {
        const rate = (currentStatus.totalProcessed - prevProcessed.current.count) / dt;
        setThroughput(Math.max(0, rate));
      }
    }
    prevProcessed.current = { count: currentStatus.totalProcessed, time: now };
  }, [currentStatus]);

  // Computed metrics
  const successRate = useMemo(() => {
    if (!currentStatus) return null;
    const total = currentStatus.totalProcessed + currentStatus.totalDeadLettered;
    if (total === 0) return 100;
    return Math.round((currentStatus.totalProcessed / total) * 100);
  }, [currentStatus]);

  const activeQueueCount = useMemo(() => {
    if (!currentStatus) return 0;
    return Object.keys(currentStatus.queueDepths).length;
  }, [currentStatus]);

  const totalPending = useMemo(() => {
    if (!currentStatus) return 0;
    return Object.values(currentStatus.queueDepths).reduce((sum, d) => sum + d, 0);
  }, [currentStatus]);

  const togglePayload = useCallback((id: string) => {
    setExpandedPayloads((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  // Dead-letter error category breakdown
  const errorCategories = useMemo(() => {
    if (!deadLetters || deadLetters.length === 0) return [];
    const cats = new Map<string, number>();
    for (const dl of deadLetters) {
      const cat = dl.error.includes("timeout") ? "Timeout"
        : dl.error.includes("503") || dl.error.includes("502") ? "Backend Unavailable"
        : dl.error.includes("401") || dl.error.includes("403") ? "Auth Error"
        : dl.error.includes("rate") ? "Rate Limited"
        : "Other";
      cats.set(cat, (cats.get(cat) ?? 0) + 1);
    }
    return [...cats.entries()].sort((a, b) => b[1] - a[1]);
  }, [deadLetters]);

  const handleReplay = (id: string) => {
    replayMutation.mutate(id, {
      onSuccess: () => toast.success(t("coordinator.replaySuccess", "Dead-letter replayed")),
      onError: () => toast.error(t("coordinator.replayError", "Failed to replay")),
    });
  };

  const handleDiscard = (id: string) => {
    discardMutation.mutate(id, {
      onSuccess: () => toast.success(t("coordinator.discardSuccess", "Dead-letter discarded")),
      onError: () => toast.error(t("coordinator.discardError", "Failed to discard")),
    });
  };

  const handlePurge = () => {
    purgeMutation.mutate(undefined, {
      onSuccess: (count) => {
        toast.success(t("coordinator.purgeSuccess", `Purged ${count} entries`));
        setConfirmPurge(false);
      },
      onError: () => toast.error(t("coordinator.purgeError", "Failed to purge")),
    });
  };

  return (
    <div className="space-y-6 p-6">
      {/* Page Header */}
      <div className="flex items-center gap-3">
        <Activity className="h-7 w-7 text-accent" />
        <div className="flex-1">
          <h1 className="text-2xl font-bold text-foreground">
            {t("coordinator.title", "Coordinator Dashboard")}
          </h1>
          <p className="text-sm text-muted-foreground">
            {t("coordinator.subtitle", "Monitor conversation processing and manage dead-letter entries")}
          </p>
        </div>
        {/* Auto-refresh selector */}
        <div className="flex items-center gap-2">
          <RefreshCw className="h-4 w-4 text-muted-foreground animate-spin" style={{ animationDuration: `${refreshInterval}s` }} />
          <select
            value={refreshInterval}
            onChange={(e) => setRefreshInterval(Number(e.target.value))}
            className="h-8 appearance-none rounded-lg border border-input bg-background pe-6 ps-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-primary"
            data-testid="refresh-interval"
          >
            <option value={5}>5s</option>
            <option value={10}>10s</option>
            <option value={30}>30s</option>
            <option value={60}>60s</option>
          </select>
          {throughput !== null && (
            <span className="inline-flex items-center gap-1 rounded-full bg-accent/10 px-2.5 py-1 text-xs font-semibold text-accent" data-testid="throughput-badge">
              <Gauge className="h-3.5 w-3.5" />
              {throughput < 0.1 ? "<0.1" : throughput.toFixed(1)} {t("coordinator.tasksPerSec", "tasks/s")}
            </span>
          )}
        </div>
      </div>

      {/* ─── Hero: Connection Status ─── */}
      {statusLoading && !currentStatus ? (
        <div className="cq-stat-grid">
          {[...Array(4)].map((_, i) => (
            <div key={i} className="animate-pulse rounded-xl border border-border bg-card p-5">
              <div className="h-4 w-24 rounded bg-muted" />
              <div className="mt-3 h-8 w-16 rounded bg-muted" />
            </div>
          ))}
        </div>
      ) : currentStatus ? (
        <>
          {/* Hero card — full-width connection status */}
          <div className="rounded-xl border border-border bg-card p-5" data-testid="coordinator-connection-card">
            <div className="flex items-center gap-4">
              <div className={`flex h-12 w-12 items-center justify-center rounded-xl ${
                isConnected ? "bg-emerald-500/10" : "bg-red-500/10"
              }`}>
                {isNats ? <Cloud className={`h-6 w-6 ${isConnected ? "text-emerald-500" : "text-red-500"}`} /> : <Server className={`h-6 w-6 ${isConnected ? "text-emerald-500" : "text-red-500"}`} />}
              </div>
              <div className="flex-1">
                <div className="flex items-center gap-2">
                  <span className={`inline-flex h-2.5 w-2.5 rounded-full ${
                    isConnected ? "bg-emerald-500 animate-pulse" : "bg-red-500"
                  }`} />
                  <span className={`text-lg font-semibold ${
                    isConnected ? "text-emerald-500" : "text-red-500"
                  }`}>
                    {currentStatus.connectionStatus}
                  </span>
                  <span className={`ms-2 rounded-full px-2.5 py-0.5 text-xs font-medium ${
                    isNats
                      ? "bg-blue-500/10 text-blue-500"
                      : "bg-purple-500/10 text-purple-500"
                  }`} data-testid="coordinator-type-card">
                    {isNats ? "NATS JetStream" : "In-Memory"}
                  </span>
                </div>
                <p className="text-xs text-muted-foreground mt-0.5">
                  {t("coordinator.activeConversations", "Active conversations")}: {currentStatus.activeConversations}
                </p>
              </div>
              <StreamBadge connected={sseConnected} />
            </div>
          </div>

          {/* 3 metric cards */}
          <div className="grid gap-4 sm:grid-cols-3">
            {/* Tasks Processed */}
            <div className="rounded-xl border border-border bg-card p-5" data-testid="coordinator-processed-card">
              <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
                <CheckCircle2 className="h-4 w-4 text-emerald-500" />
                {t("coordinator.processed", "Tasks Processed")}
              </div>
              <p className="mt-2 text-2xl font-bold text-foreground tabular-nums">
                {currentStatus.totalProcessed.toLocaleString()}
              </p>
            </div>

            {/* Dead-Lettered */}
            <div className="rounded-xl border border-border bg-card p-5" data-testid="coordinator-dead-letter-card">
              <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
                <AlertTriangle className="h-4 w-4 text-amber-500" />
                {t("coordinator.deadLettered", "Dead-Lettered")}
              </div>
              <p className={`mt-2 text-2xl font-bold tabular-nums ${
                currentStatus.totalDeadLettered > 0 ? "text-amber-500" : "text-foreground"
              }`}>
                {currentStatus.totalDeadLettered.toLocaleString()}
              </p>
            </div>

            {/* Success Rate */}
            <div className="rounded-xl border border-border bg-card p-5" data-testid="coordinator-success-rate-card">
              <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
                <TrendingUp className="h-4 w-4 text-blue-500" />
                {t("coordinator.successRate", "Success Rate")}
              </div>
              <p className={`mt-2 text-2xl font-bold tabular-nums ${
                successRate !== null && successRate < 90 ? "text-amber-500" : "text-emerald-500"
              }`}>
                {successRate !== null ? `${successRate}%` : "—"}
              </p>
              {/* Ratio bar */}
              {successRate !== null && currentStatus.totalProcessed + currentStatus.totalDeadLettered > 0 && (
                <div className="mt-2 flex h-2 overflow-hidden rounded-full bg-muted" data-testid="success-rate-bar">
                  <div
                    className="bg-emerald-500 transition-all"
                    style={{ width: `${successRate}%` }}
                  />
                  <div
                    className="bg-red-400 transition-all"
                    style={{ width: `${100 - successRate}%` }}
                  />
                </div>
              )}
            </div>
          </div>
        </>
      ) : (
        <div className="flex flex-col items-center justify-center rounded-xl border-2 border-dashed border-border py-16">
          <Activity className="h-12 w-12 text-muted-foreground/40" />
          <p className="mt-4 text-lg font-medium text-muted-foreground">
            {t("coordinator.empty", "No coordinator data available")}
          </p>
          <p className="mt-1 text-sm text-muted-foreground/70">
            {t("coordinator.emptyHint", "The coordinator service may still be starting up. Data will appear automatically.")}
          </p>
        </div>
      )}

      {/* Error category breakdown */}
      {errorCategories.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {errorCategories.map(([cat, count]) => (
            <span key={cat} className="inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-3 py-1 text-xs font-medium text-muted-foreground">
              <span className="h-2 w-2 rounded-full bg-red-400" />
              {cat}: {count}
            </span>
          ))}
        </div>
      )}

      {/* ─── Active Queues ─── */}
      <div className="rounded-xl border border-border bg-card p-5" data-testid="coordinator-queues">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-foreground">
            {t("coordinator.activeQueues", "Active Queues")}
          </h2>
          {activeQueueCount > 0 && (
            <span className="rounded-full bg-accent/10 px-2.5 py-0.5 text-xs font-semibold text-accent tabular-nums">
              {totalPending} {t("coordinator.totalPending", "pending")}
            </span>
          )}
        </div>
        {currentStatus && Object.keys(currentStatus.queueDepths).length > 0 ? (
          <div className="space-y-2">
            {Object.entries(currentStatus.queueDepths).map(([convId, depth]) => (
              <div key={convId} className="flex items-center gap-3 rounded-lg bg-muted/50 px-3 py-2">
                <Link
                  to={`/manage/conversations`}
                  className="flex-1 truncate font-mono text-sm text-foreground hover:text-primary transition-colors"
                  title={convId}
                >
                  {convId}
                  <ExternalLink className="ms-1.5 inline h-3 w-3 text-muted-foreground" />
                </Link>
                <span className="rounded-full bg-accent/10 px-2.5 py-0.5 text-sm font-semibold text-accent tabular-nums">
                  {depth} {t("coordinator.queued", "queued")}
                </span>
              </div>
            ))}
          </div>
        ) : (
          <div className="flex items-center gap-2 py-4 text-sm text-muted-foreground">
            <CheckCircle2 className="h-5 w-5 text-emerald-500/50" />
            {t("coordinator.noActiveQueues", "No active conversations being processed")}
          </div>
        )}
      </div>

      {/* ─── SSE Event History ─── */}
      {eventHistory.length > 0 && (
        <div className="rounded-xl border border-border bg-card p-5" data-testid="coordinator-event-history">
          <h2 className="mb-3 flex items-center gap-2 text-lg font-semibold text-foreground">
            <Clock className="h-4 w-4 text-muted-foreground" />
            {t("coordinator.eventHistory", "Event History")}
            <span className="text-xs font-normal text-muted-foreground">
              ({t("coordinator.lastNSnapshots", `Last ${eventHistory.length} snapshots`)})
            </span>
          </h2>
          <div className="max-h-48 overflow-y-auto space-y-0.5 rounded-lg bg-muted/30 p-2 font-mono text-[11px]">
            {[...eventHistory].reverse().map((snap, i) => (
              <div
                key={i}
                className="flex items-center gap-2 rounded px-2 py-1 text-muted-foreground hover:bg-muted/50 transition-colors"
              >
                <span className="text-foreground/40 tabular-nums">
                  [{formatTime(snap.receivedAt)}]
                </span>
                <span className="text-emerald-500 tabular-nums">
                  ✓{snap.totalProcessed.toLocaleString()}
                </span>
                <span className={`tabular-nums ${snap.totalDeadLettered > 0 ? "text-red-400" : "text-muted-foreground/40"}`}>
                  ✗{snap.totalDeadLettered.toLocaleString()}
                </span>
                <span className="text-blue-400 tabular-nums">
                  ⧗{Object.keys(snap.queueDepths).length}q
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ─── Dead-Letter Admin ─── */}
      <div className="rounded-xl border border-border bg-card" data-testid="coordinator-dead-letters">
        <div className="flex items-center justify-between border-b border-border px-5 py-4">
          <h2 className="text-lg font-semibold text-foreground">
            {t("coordinator.deadLetterTitle", "Dead-Letter Queue")}
          </h2>
          <div className="flex items-center gap-2">
            {deadLetters && deadLetters.length > 0 && (
              confirmPurge ? (
                <div className="flex items-center gap-2">
                  <span className="text-sm text-muted-foreground">
                    {t("coordinator.confirmPurge", "Purge all?")}
                  </span>
                  <button
                    onClick={handlePurge}
                    disabled={purgeMutation.isPending}
                    className="rounded-lg bg-red-500 px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-red-600 disabled:opacity-50"
                  >
                    {t("coordinator.yes", "Yes")}
                  </button>
                  <button
                    onClick={() => setConfirmPurge(false)}
                    className="rounded-lg border border-border px-3 py-1.5 text-sm font-medium text-foreground transition-colors hover:bg-muted"
                  >
                    {t("coordinator.cancel", "Cancel")}
                  </button>
                </div>
              ) : (
                <button
                  onClick={() => setConfirmPurge(true)}
                  className="flex items-center gap-1.5 rounded-lg border border-border px-3 py-1.5 text-sm font-medium text-muted-foreground transition-colors hover:border-red-500/50 hover:text-red-500"
                  data-testid="purge-dead-letters-btn"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                  {t("coordinator.purgeAll", "Purge All")}
                </button>
              )
            )}
          </div>
        </div>

        {dlLoading ? (
          <div className="p-8 text-center">
            <div className="mx-auto h-6 w-6 animate-spin rounded-full border-2 border-accent border-t-transparent" />
          </div>
        ) : !deadLetters || deadLetters.length === 0 ? (
          <div className="p-8 text-center text-muted-foreground" data-testid="dead-letters-empty">
            <CheckCircle2 className="mx-auto mb-2 h-8 w-8 text-emerald-500/50" />
            <p>{t("coordinator.noDeadLetters", "No dead-letter entries")}</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full" data-testid="dead-letters-table">
              <thead>
                <tr className="border-b border-border text-start text-sm text-muted-foreground">
                  <th className="px-5 py-3 text-start font-medium">{t("coordinator.colId", "ID")}</th>
                  <th className="px-5 py-3 text-start font-medium">{t("coordinator.colConversation", "Conversation")}</th>
                  <th className="px-5 py-3 text-start font-medium">{t("coordinator.colError", "Error")}</th>
                  <th className="px-5 py-3 text-start font-medium">{t("coordinator.colTime", "Time")}</th>
                  <th className="px-5 py-3 text-end font-medium">{t("coordinator.colActions", "Actions")}</th>
                </tr>
              </thead>
              <tbody>
                {deadLetters.map((entry) => (
                  <tr key={entry.id} className="border-b border-border/50 transition-colors hover:bg-muted/30">
                    <td className="px-5 py-3">
                      <code className="text-xs text-muted-foreground">{entry.id}</code>
                    </td>
                    <td className="px-5 py-3">
                      <code className="text-sm text-foreground">{entry.conversationId}</code>
                    </td>
                    <td className="max-w-[300px] px-5 py-3 text-sm text-red-400">
                      <div className="truncate" title={entry.error}>{entry.error}</div>
                      {/* Expandable payload viewer */}
                      {entry.payload && (
                        <button
                          onClick={() => togglePayload(entry.id)}
                          className="mt-1 inline-flex items-center gap-1 text-[11px] text-muted-foreground hover:text-foreground transition-colors"
                          data-testid={`toggle-payload-${entry.id}`}
                        >
                          {expandedPayloads.has(entry.id) ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
                          <Eye className="h-3 w-3" />
                          {t("coordinator.payload", "Payload")}
                        </button>
                      )}
                      {expandedPayloads.has(entry.id) && entry.payload && (
                        <pre className="mt-1 max-h-40 overflow-auto rounded-lg bg-card/50 p-2 text-xs text-foreground/80 border border-border/50">
                          {(() => { try { return JSON.stringify(JSON.parse(entry.payload), null, 2); } catch { return entry.payload; } })()}
                        </pre>
                      )}
                    </td>
                    <td className="px-5 py-3 text-sm text-muted-foreground tabular-nums">
                      {new Date(entry.timestamp).toLocaleString()}
                    </td>
                    <td className="px-5 py-3">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          onClick={() => handleReplay(entry.id)}
                          disabled={replayMutation.isPending}
                          title={t("coordinator.replay", "Replay")}
                          className="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-accent/10 hover:text-accent disabled:opacity-50"
                          data-testid={`replay-${entry.id}`}
                        >
                          <RotateCcw className="h-4 w-4" />
                        </button>
                        <button
                          onClick={() => handleDiscard(entry.id)}
                          disabled={discardMutation.isPending}
                          title={t("coordinator.discard", "Discard")}
                          className="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-red-500/10 hover:text-red-500 disabled:opacity-50"
                          data-testid={`discard-${entry.id}`}
                        >
                          <Trash2 className="h-4 w-4" />
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
    </div>
  );
}
