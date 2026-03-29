import { useState, useEffect, useRef, useMemo, useCallback } from "react";
import { useTranslation } from "react-i18next";
import {
  Activity,
  Trash2,
  RotateCcw,
  Wifi,
  WifiOff,
  Server,
  Cloud,
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  RefreshCw,
  Gauge,
  Eye,
} from "lucide-react";
import { toast } from "sonner";
import {
  useCoordinatorStatus,
  useDeadLetters,
  useReplayDeadLetter,
  useDiscardDeadLetter,
  usePurgeDeadLetters,
  useCoordinatorSSE,
} from "@/hooks/use-coordinator";

export function CoordinatorPage() {
  const { t } = useTranslation();
  const { data: status, isLoading: statusLoading } = useCoordinatorStatus();
  const { data: deadLetters, isLoading: dlLoading } = useDeadLetters();
  const { liveStatus, sseConnected } = useCoordinatorSSE();
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
  const { refetch: refetchStatus } = useCoordinatorStatus();
  const { refetch: refetchDL } = useDeadLetters();

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

      {/* Status Cards */}
      {statusLoading && !currentStatus ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {[...Array(4)].map((_, i) => (
            <div key={i} className="animate-pulse rounded-xl border border-border bg-card p-5">
              <div className="h-4 w-24 rounded bg-muted" />
              <div className="mt-3 h-8 w-16 rounded bg-muted" />
            </div>
          ))}
        </div>
      ) : currentStatus ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {/* Coordinator Type */}
          <div className="rounded-xl border border-border bg-card p-5" data-testid="coordinator-type-card">
            <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
              {isNats ? <Cloud className="h-4 w-4" /> : <Server className="h-4 w-4" />}
              {t("coordinator.type", "Coordinator Type")}
            </div>
            <div className="mt-2 flex items-center gap-2">
              <span className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-sm font-semibold ${
                isNats
                  ? "bg-blue-500/10 text-blue-500"
                  : "bg-purple-500/10 text-purple-500"
              }`}>
                {isNats ? "NATS JetStream" : "In-Memory"}
              </span>
            </div>
          </div>

          {/* Connection Status */}
          <div className="rounded-xl border border-border bg-card p-5" data-testid="coordinator-connection-card">
            <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
              {isConnected ? <Wifi className="h-4 w-4" /> : <WifiOff className="h-4 w-4" />}
              {t("coordinator.connection", "Connection")}
            </div>
            <div className="mt-2 flex items-center gap-2">
              <span className={`inline-flex h-2.5 w-2.5 rounded-full ${
                isConnected ? "bg-emerald-500 animate-pulse" : "bg-red-500"
              }`} />
              <span className={`text-lg font-semibold ${
                isConnected ? "text-emerald-500" : "text-red-500"
              }`}>
                {currentStatus.connectionStatus}
              </span>
              {sseConnected && (
                <span className="ms-auto text-xs text-muted-foreground" title="SSE Live">
                  ● Live
                </span>
              )}
            </div>
          </div>

          {/* Tasks Processed */}
          <div className="rounded-xl border border-border bg-card p-5" data-testid="coordinator-processed-card">
            <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
              <CheckCircle2 className="h-4 w-4" />
              {t("coordinator.processed", "Tasks Processed")}
            </div>
            <p className="mt-2 text-2xl font-bold text-foreground tabular-nums">
              {currentStatus.totalProcessed.toLocaleString()}
            </p>
          </div>

          {/* Dead-Lettered */}
          <div className="rounded-xl border border-border bg-card p-5" data-testid="coordinator-dead-letter-card">
            <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
              <AlertTriangle className="h-4 w-4" />
              {t("coordinator.deadLettered", "Dead-Lettered")}
            </div>
            <p className={`mt-2 text-2xl font-bold tabular-nums ${
              currentStatus.totalDeadLettered > 0 ? "text-amber-500" : "text-foreground"
            }`}>
              {currentStatus.totalDeadLettered.toLocaleString()}
            </p>
          </div>
        </div>
      ) : null}

      {/* Queue Depths */}
      {currentStatus && Object.keys(currentStatus.queueDepths).length > 0 && (
        <div className="rounded-xl border border-border bg-card p-5" data-testid="coordinator-queues">
          <h2 className="mb-3 text-lg font-semibold text-foreground">
            {t("coordinator.activeQueues", "Active Queues")}
          </h2>
          <div className="space-y-2">
            {Object.entries(currentStatus.queueDepths).map(([convId, depth]) => (
              <div key={convId} className="flex items-center gap-3 rounded-lg bg-muted/50 px-3 py-2">
                <code className="flex-1 truncate text-sm text-foreground">{convId}</code>
                <span className="rounded-full bg-accent/10 px-2.5 py-0.5 text-sm font-semibold text-accent tabular-nums">
                  {depth} {t("coordinator.queued", "queued")}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Dead-Letter Admin */}
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
