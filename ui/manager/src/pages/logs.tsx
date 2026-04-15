import { useState, useCallback, useRef, useEffect, useMemo } from "react";
import { useOnboarding } from "@/hooks/use-onboarding";
import { useTranslation } from "react-i18next";
import {
  ScrollText,
  Pause,
  Play,
  Trash2,
  ChevronDown,
  ChevronRight,
  History,
  Radio,
  Loader2,
  Search,
  Copy,
  Download,
  MessageSquare,
  Clock,
  X,
  Check,
} from "lucide-react";
import { StreamBadge } from "@/components/ui/stream-badge";
import { useLogStream, useHistoryLogs, useInstanceId } from "@/hooks/use-logs";
import type { LogEntry } from "@/lib/api/logs";
import type { HistoryFilters } from "@/lib/api/logs";
import { useDeployedAgents } from "@/hooks/use-chat";
import { getConversationDescriptors, parseConversationUri } from "@/lib/api/conversations";
import { useQuery } from "@tanstack/react-query";

// ==================== Level badge config ====================

const LEVEL_CONFIG: Record<string, { label: string; className: string }> = {
  SEVERE: {
    label: "ERROR",
    className: "bg-red-500/15 text-red-400 border-red-500/30",
  },
  ERROR: {
    label: "ERROR",
    className: "bg-red-500/15 text-red-400 border-red-500/30",
  },
  FATAL: {
    label: "FATAL",
    className: "bg-red-600/20 text-red-300 border-red-600/40",
  },
  WARNING: {
    label: "WARN",
    className: "bg-amber-500/15 text-amber-400 border-amber-500/30",
  },
  WARN: {
    label: "WARN",
    className: "bg-amber-500/15 text-amber-400 border-amber-500/30",
  },
  INFO: {
    label: "INFO",
    className: "bg-blue-500/15 text-blue-400 border-blue-500/30",
  },
  CONFIG: {
    label: "CFG",
    className: "bg-sky-500/15 text-sky-400 border-sky-500/30",
  },
  FINE: {
    label: "DEBUG",
    className: "bg-gray-500/15 text-gray-400 border-gray-500/30",
  },
  DEBUG: {
    label: "DEBUG",
    className: "bg-gray-500/15 text-gray-400 border-gray-500/30",
  },
  FINER: {
    label: "TRACE",
    className: "bg-gray-500/10 text-gray-500 border-gray-500/20",
  },
  FINEST: {
    label: "TRACE",
    className: "bg-gray-500/10 text-gray-500 border-gray-500/20",
  },
  TRACE: {
    label: "TRACE",
    className: "bg-gray-500/10 text-gray-500 border-gray-500/20",
  },
};

function LevelBadge({ level }: { level: string }) {
  const config = LEVEL_CONFIG[level?.toUpperCase()] ?? {
    label: level ?? "?",
    className: "bg-gray-500/15 text-gray-400 border-gray-500/30",
  };
  return (
    <span
      className={`inline-flex min-w-14 items-center justify-center rounded border px-1.5 py-0.5 text-[10px] font-bold tracking-wider ${config.className}`}
    >
      {config.label}
    </span>
  );
}

function formatTimestamp(ts: number | string | undefined): string {
  if (!ts) return "—";
  const d = typeof ts === "string" ? new Date(ts) : new Date(ts);
  // Use Intl.DateTimeFormat directly to access fractionalSecondDigits
  try {
    return new Intl.DateTimeFormat(undefined, {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      fractionalSecondDigits: 3,
    } as Intl.DateTimeFormatOptions).format(d);
  } catch {
    return d.toLocaleTimeString();
  }
}

// ==================== Tab type ====================

type Tab = "live" | "history";

// ==================== Component ====================

export function LogsPage() {
  const { t } = useTranslation();
  
  const maybeAutoStart = useOnboarding((s) => s.maybeAutoStart);
  useEffect(() => { const t = setTimeout(() => maybeAutoStart("logs"), 500); return () => clearTimeout(t); }, [maybeAutoStart]);

  const [activeTab, setActiveTab] = useState<Tab>("live");

  return (
    <div className="flex h-full flex-col p-6" data-testid="logs-page">
      {/* Header */}
      <div className="mb-4 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold text-foreground">
            <ScrollText className="me-2 inline h-6 w-6" />
            {t("logs.title", "Logs")}
          </h1>
          <p className="mt-1 text-sm text-muted-foreground">
            {t(
              "logs.description",
              "Monitor real-time log stream or search historical logs."
            )}
          </p>
        </div>
      </div>

      {/* Tab bar */}
      <div className="mb-4 flex gap-1 rounded-lg border border-border bg-muted/30 p-1" data-tour="logs-tabs">
        <button
          className={`inline-flex flex-1 items-center justify-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition-all ${
            activeTab === "live"
              ? "bg-card text-foreground shadow-sm"
              : "text-muted-foreground hover:text-foreground"
          }`}
          onClick={() => setActiveTab("live")}
          data-testid="tab-live"
        >
          <Radio className="h-4 w-4" />
          {t("logs.tabLive", "Live")}
        </button>
        <button
          className={`inline-flex flex-1 items-center justify-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition-all ${
            activeTab === "history"
              ? "bg-card text-foreground shadow-sm"
              : "text-muted-foreground hover:text-foreground"
          }`}
          onClick={() => setActiveTab("history")}
          data-testid="tab-history"
        >
          <History className="h-4 w-4" />
          {t("logs.tabHistory", "History")}
        </button>
      </div>

      {/* Tab content */}
      {activeTab === "live" ? <LiveTab /> : <HistoryTab />}
    </div>
  );
}

// ==================== Live Tab ====================

function LiveTab() {
  const { t } = useTranslation();
  const [agentFilter, setAgentFilter] = useState("");
  const [levelFilter, setLevelFilter] = useState("");
  const scrollRef = useRef<HTMLDivElement>(null);
  const [autoScroll, setAutoScroll] = useState(true);

  const filters = useMemo(
    () => ({
      agentId: agentFilter || undefined,
      level: levelFilter || undefined,
    }),
    [agentFilter, levelFilter]
  );

  const { entries, sseConnected, paused, setPaused, clearEntries } =
    useLogStream(filters);
  const { data: instanceInfo } = useInstanceId();
  const [textSearch, setTextSearch] = useState("");

  // Filter entries by text search
  const filteredEntries = useMemo(() => {
    if (!textSearch.trim()) return entries;
    const q = textSearch.toLowerCase();
    return entries.filter(
      (e) =>
        (e.message ?? "").toLowerCase().includes(q) ||
        (e.loggerName ?? "").toLowerCase().includes(q)
    );
  }, [entries, textSearch]);

  // Level stats
  const levelStats = useMemo(() => {
    const stats = { error: 0, warn: 0, info: 0, debug: 0 };
    for (const e of entries) {
      const lvl = (e.level ?? "").toUpperCase();
      if (lvl === "ERROR" || lvl === "SEVERE" || lvl === "FATAL") stats.error++;
      else if (lvl === "WARNING" || lvl === "WARN") stats.warn++;
      else if (lvl === "INFO") stats.info++;
      else stats.debug++;
    }
    return stats;
  }, [entries]);

  // Export logs
  const handleExportLogs = useCallback(() => {
    if (filteredEntries.length === 0) return;
    const text = filteredEntries
      .map((e) => `[${formatTimestamp(e.timestamp)}] [${e.level}] ${e.message}`)
      .join("\n");
    const blob = new Blob([text], { type: "text/plain" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `logs-${new Date().toISOString().slice(0, 19).replace(/:/g, "-")}.txt`;
    a.click();
    URL.revokeObjectURL(url);
  }, [filteredEntries]);

  // Auto-scroll to top (newest first)
  useEffect(() => {
    if (autoScroll && scrollRef.current && !paused) {
      scrollRef.current.scrollTop = 0;
    }
  }, [entries.length, autoScroll, paused]);

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* Toolbar */}
      <div className="mb-3 flex flex-wrap items-center gap-2" data-tour="logs-filters">
        {/* SSE stream status */}
        <StreamBadge connected={sseConnected} />

        {/* Instance badge */}
        {instanceInfo && (
          <span className="rounded-full border border-border bg-muted/50 px-2.5 py-1 text-xs text-muted-foreground">
            {instanceInfo.instanceId}
          </span>
        )}

        <div className="flex-1" />

        {/* Agent filter */}
        <AgentFilterSelect
          value={agentFilter}
          onChange={setAgentFilter}
          testId="filter-agent"
        />

        {/* Level filter */}
        <div className="relative">
          <select
            value={levelFilter}
            onChange={(e) => setLevelFilter(e.target.value)}
            className="appearance-none rounded-lg border border-input bg-background pe-7 ps-3 py-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-primary"
            data-testid="filter-level"
          >
            <option value="">{t("logs.allLevels", "All Levels")}</option>
            <option value="ERROR">ERROR</option>
            <option value="WARNING">WARN</option>
            <option value="INFO">INFO</option>
            <option value="FINE">DEBUG</option>
          </select>
          <ChevronDown className="pointer-events-none absolute inset-e-2 top-1/2 h-3 w-3 -translate-y-1/2 text-muted-foreground" />
        </div>

        {/* Pause / Resume */}
        <button
          onClick={() => setPaused(!paused)}
          className={`inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors ${
            paused
              ? "border-amber-500/30 bg-amber-500/10 text-amber-400 hover:bg-amber-500/20"
              : "border-input bg-background text-foreground hover:bg-muted"
          }`}
          data-testid="pause-button"
        >
          {paused ? (
            <Play className="h-3.5 w-3.5" />
          ) : (
            <Pause className="h-3.5 w-3.5" />
          )}
          {paused ? t("logs.resume", "Resume") : t("logs.pause", "Pause")}
        </button>

        {/* Clear */}
        <button
          onClick={clearEntries}
          className="inline-flex items-center gap-1.5 rounded-lg border border-input bg-background px-3 py-1.5 text-xs font-medium text-foreground transition-colors hover:bg-muted"
          data-testid="clear-button"
        >
          <Trash2 className="h-3.5 w-3.5" />
          {t("logs.clear", "Clear")}
        </button>

        {/* Export */}
        <button
          onClick={handleExportLogs}
          disabled={filteredEntries.length === 0}
          className="inline-flex items-center gap-1.5 rounded-lg border border-input bg-background px-3 py-1.5 text-xs font-medium text-foreground transition-colors hover:bg-muted disabled:opacity-50"
          data-testid="export-logs-btn"
        >
          <Download className="h-3.5 w-3.5" />
          {t("logs.export", "Export")}
        </button>
      </div>

      {/* Level stats bar */}
      {entries.length > 0 && (
        <div className="mb-3 flex items-center gap-3 rounded-lg border border-border bg-card px-3 py-2" data-testid="level-stats">
          <span className="inline-flex items-center gap-1 text-xs font-semibold text-red-400">
            <span className="h-2 w-2 rounded-full bg-red-500" />
            {levelStats.error} {t("logs.errors", "errors")}
          </span>
          <span className="inline-flex items-center gap-1 text-xs font-semibold text-amber-400">
            <span className="h-2 w-2 rounded-full bg-amber-500" />
            {levelStats.warn} {t("logs.warnings", "warns")}
          </span>
          <span className="inline-flex items-center gap-1 text-xs font-semibold text-blue-400">
            <span className="h-2 w-2 rounded-full bg-blue-500" />
            {levelStats.info} {t("logs.infos", "info")}
          </span>
          <span className="inline-flex items-center gap-1 text-xs font-semibold text-gray-400">
            <span className="h-2 w-2 rounded-full bg-gray-500" />
            {levelStats.debug} {t("logs.debugs", "debug")}
          </span>
          <div className="flex-1" />
          {/* Text search */}
          <div className="relative">
            <Search className="pointer-events-none absolute inset-s-2 top-1/2 h-3 w-3 -translate-y-1/2 text-muted-foreground" />
            <input
              type="text"
              value={textSearch}
              onChange={(e) => setTextSearch(e.target.value)}
              placeholder={t("logs.searchLogs", "Search logs…")}
              className="h-7 w-48 rounded-md border border-input bg-background ps-7 pe-2 text-xs placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-primary"
              data-testid="text-search"
            />
          </div>
        </div>
      )}

      {/* Log entries */}
      <div
        ref={scrollRef}
        className="flex-1 overflow-auto rounded-xl border border-border bg-card"
        onScroll={(e) => {
          // Disable auto-scroll if user scrolls up
          const el = e.currentTarget;
          setAutoScroll(el.scrollTop < 10);
        }}
      >
        {entries.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center gap-3 p-8">
            {sseConnected ? (
              <>
                <Radio className="h-10 w-10 text-muted-foreground/40 animate-pulse" />
                <p className="text-sm font-medium text-muted-foreground">
                  {t("logs.waitingForLogs", "Waiting for logs...")}
                </p>
                <p className="text-xs text-muted-foreground/60">
                  {t("logs.waitingHint", "Logs will appear here as they stream in from the backend.")}
                </p>
              </>
            ) : (
              <>
                <Loader2 className="h-10 w-10 text-muted-foreground/40 animate-spin" />
                <p className="text-sm font-medium text-muted-foreground">
                  {t("logs.connectingStream", "Connecting to stream...")}
                </p>
              </>
            )}
          </div>
        ) : (
          <div className="divide-y divide-border/50 font-mono text-xs">
            {filteredEntries.map((entry, idx) => (
              <LogRow key={`${entry.timestamp}-${idx}`} entry={entry} />
            ))}
          </div>
        )}
      </div>

      {/* Entry count */}
      <div className="mt-2 text-end text-xs text-muted-foreground">
        {textSearch && filteredEntries.length !== entries.length
          ? t("logs.filteredCount", { shown: filteredEntries.length, total: entries.length, defaultValue: `${filteredEntries.length} of ${entries.length} entries` })
          : t("logs.entryCount", { count: entries.length, defaultValue: `${entries.length} entries` })}
      </div>
    </div>
  );
}

// ==================== History Tab ====================

function HistoryTab() {
  const { t } = useTranslation();
  const [filters, setFilters] = useState<HistoryFilters>({ limit: 100 });

  const { data: logs, isLoading, refetch } = useHistoryLogs(filters);

  const updateFilter = useCallback(
    (key: keyof HistoryFilters, value: string) => {
      setFilters((prev) => ({
        ...prev,
        [key]: value || undefined,
        skip: 0,
      }));
    },
    []
  );

  // Reset conversation when agent changes
  const handleAgentChange = useCallback(
    (v: string) => {
      setFilters((prev) => ({
        ...prev,
        agentId: v || undefined,
        conversationId: undefined,
        skip: 0,
      }));
    },
    []
  );

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* Filters */}
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <AgentFilterSelect
          value={filters.agentId ?? ""}
          onChange={handleAgentChange}
          testId="history-filter-agent"
        />
        <ConversationPicker
          value={filters.conversationId ?? ""}
          onChange={(v) => updateFilter("conversationId", v)}
          agentId={filters.agentId}
          testId="history-filter-conversation"
        />
        <input
          type="text"
          placeholder={t("logs.filterInstance", "Instance ID...")}
          value={filters.instanceId ?? ""}
          onChange={(e) => updateFilter("instanceId", e.target.value)}
          className="w-36 rounded-lg border border-input bg-background px-3 py-1.5 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-primary"
          data-testid="history-filter-instance"
        />

        <div className="flex-1" />

        <button
          onClick={() => refetch()}
          disabled={isLoading}
          className="inline-flex items-center gap-1.5 rounded-lg bg-primary px-4 py-1.5 text-xs font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
          data-testid="history-search"
        >
          {isLoading ? (
            <Loader2 className="h-3.5 w-3.5 animate-spin" />
          ) : (
            <History className="h-3.5 w-3.5" />
          )}
          {t("logs.search", "Search")}
        </button>
      </div>

      {/* Results */}
      <div className="flex-1 overflow-auto rounded-xl border border-border bg-card">
        {isLoading ? (
          <div className="flex h-full items-center justify-center p-8">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        ) : !logs || logs.length === 0 ? (
          <div
            className="flex h-full flex-col items-center justify-center gap-3 p-8"
            data-testid="history-empty"
          >
            <History className="h-10 w-10 text-muted-foreground/40" />
            <p className="text-sm font-medium text-muted-foreground">
              {t("logs.noHistoryLogs", "No historical logs found.")}
            </p>
            <p className="text-xs text-muted-foreground/60">
              {t("logs.noHistoryHint", "Try adjusting the filters or search for a different agent or conversation.")}
            </p>
          </div>
        ) : (
          <div className="divide-y divide-border/50 font-mono text-xs">
            {logs.map((entry, idx) => (
              <div
                key={`${entry.timestamp}-${idx}`}
                className="flex items-start gap-3 px-3 py-2 hover:bg-muted/30 transition-colors"
              >
                <span className="shrink-0 text-muted-foreground">
                  {formatTimestamp(entry.timestamp)}
                </span>
                <LevelBadge level={entry.level ?? "INFO"} />
                {entry.agentId && (
                  <span className="shrink-0 rounded bg-primary/10 px-1.5 py-0.5 text-[10px] text-primary">
                    {entry.agentId}
                  </span>
                )}
                {entry.instanceId && (
                  <span className="shrink-0 rounded bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">
                    {entry.instanceId}
                  </span>
                )}
                <span className="min-w-0 flex-1 break-all text-foreground">
                  {entry.message}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Count */}
      <div className="mt-2 text-end text-xs text-muted-foreground">
        {logs &&
          t("logs.entryCount", {
            count: logs.length,
            defaultValue: `${logs.length} entries`,
          })}
      </div>
    </div>
  );
}

// ==================== Stacktrace Helpers ====================

/** Detect if a message contains a Java stacktrace */
function hasStacktrace(message: string): boolean {
  return /\n\s+at /.test(message) || /\nCaused by:/.test(message);
}

/** Split a message into the main line and stacktrace frames */
function splitStacktrace(message: string): { main: string; frames: string } {
  // Find the first "\n  at " or "\nCaused by:" to split
  const match = message.match(/(\n\s+at |\nCaused by:)/);
  if (!match || match.index === undefined) return { main: message, frames: "" };
  return {
    main: message.substring(0, match.index),
    frames: message.substring(match.index),
  };
}

/** Count stacktrace frames */
function countFrames(frames: string): number {
  return (frames.match(/(\n\s+at |\nCaused by:)/g) || []).length;
}

// ==================== Log Row Component ====================

function LogRow({ entry }: { entry: LogEntry }) {
  const [expanded, setExpanded] = useState(false);
  const { t } = useTranslation();
  const isStacktrace = entry.message ? hasStacktrace(entry.message) : false;
  const { main, frames } = isStacktrace
    ? splitStacktrace(entry.message)
    : { main: entry.message, frames: "" };
  const frameCount = isStacktrace ? countFrames(frames) : 0;

  const handleCopy = useCallback(() => {
    const text = `[${formatTimestamp(entry.timestamp)}] [${entry.level}] ${entry.message}`;
    navigator.clipboard.writeText(text);
  }, [entry]);

  return (
    <div className="group px-3 py-2 hover:bg-muted/30 transition-colors">
      <div className="flex items-start gap-3">
        <span className="shrink-0 text-muted-foreground">
          {formatTimestamp(entry.timestamp)}
        </span>
        <LevelBadge level={entry.level} />
        {entry.agentId && (
          <span className="shrink-0 rounded bg-primary/10 px-1.5 py-0.5 text-[10px] text-primary">
            {entry.agentId}
          </span>
        )}
        {entry.conversationId && (
          <span className="shrink-0 rounded bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">
            {entry.conversationId}
          </span>
        )}
        <span className="min-w-0 flex-1 break-all text-foreground">
          {main}
        </span>
        {/* Copy button — visible on hover */}
        <button
          onClick={handleCopy}
          className="shrink-0 opacity-0 group-hover:opacity-100 rounded p-1 text-muted-foreground hover:text-foreground hover:bg-muted transition-all"
          title={t("logs.copyEntry", "Copy log entry")}
          data-testid="copy-log-btn"
        >
          <Copy className="h-3 w-3" />
        </button>
      </div>
      {/* Collapsible stacktrace */}
      {isStacktrace && (
        <div className="ms-18 mt-1">
          <button
            onClick={() => setExpanded(!expanded)}
            className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-medium text-red-400/80 transition-colors hover:bg-red-500/10 hover:text-red-400"
            data-testid="stacktrace-toggle"
          >
            {expanded ? (
              <ChevronDown className="h-3 w-3" />
            ) : (
              <ChevronRight className="h-3 w-3" />
            )}
            {expanded
              ? t("logs.hideStacktrace", "Hide stacktrace")
              : t("logs.showStacktrace", {
                  count: frameCount,
                  defaultValue: `Show stacktrace (${frameCount} frames)`,
                })}
          </button>
          {expanded && (
            <pre className="mt-1 overflow-x-auto whitespace-pre rounded-lg border border-red-500/20 bg-red-500/5 p-2 text-[10px] leading-relaxed text-red-300/80">
              {frames.trim()}
            </pre>
          )}
        </div>
      )}
    </div>
  );
}

// ==================== Filter Select Components ====================

/** Dropdown for selecting an agent — populated from deployed agents. */
function AgentFilterSelect({
  value,
  onChange,
  testId,
}: {
  value: string;
  onChange: (v: string) => void;
  testId: string;
}) {
  const { t } = useTranslation();
  const { data: agents } = useDeployedAgents();

  return (
    <div className="relative">
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="appearance-none rounded-lg border border-input bg-background pe-7 ps-3 py-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-primary"
        data-testid={testId}
      >
        <option value="">{t("logs.allAgents", "All Agents")}</option>
        {agents?.map((agent) => (
          <option key={agent.id} value={agent.id}>
            {agent.name || agent.id}
          </option>
        ))}
      </select>
      <ChevronDown className="pointer-events-none absolute inset-e-2 top-1/2 h-3 w-3 -translate-y-1/2 text-muted-foreground" />
    </div>
  );
}

// ==================== Conversation State Badge ====================

const CONV_STATE_STYLE: Record<string, { i18nKey: string; fallback: string; className: string }> = {
  READY: { i18nKey: "logs.stateReady", fallback: "Ready", className: "bg-emerald-500/15 text-emerald-400 border-emerald-500/30" },
  IN_PROGRESS: { i18nKey: "logs.stateActive", fallback: "Active", className: "bg-blue-500/15 text-blue-400 border-blue-500/30" },
  ERROR: { i18nKey: "logs.stateError", fallback: "Error", className: "bg-red-500/15 text-red-400 border-red-500/30" },
  ENDED: { i18nKey: "logs.stateEnded", fallback: "Ended", className: "bg-gray-500/15 text-gray-400 border-gray-500/30" },
};

function ConvStateBadge({ state }: { state: string }) {
  const { t } = useTranslation();
  const config = CONV_STATE_STYLE[state] ?? {
    i18nKey: "",
    fallback: state,
    className: "bg-gray-500/15 text-gray-400 border-gray-500/30",
  };
  return (
    <span className={`inline-flex items-center rounded border px-1.5 py-0.5 text-[10px] font-medium leading-none ${config.className}`}>
      {config.i18nKey ? t(config.i18nKey, config.fallback) : config.fallback}
    </span>
  );
}

// ==================== Relative Time ====================

function formatRelativeTime(ts: number | string | undefined): string {
  if (!ts) return "—";
  const d = typeof ts === "string" ? new Date(ts) : new Date(ts);
  const now = Date.now();
  const diffMs = now - d.getTime();
  const diffMin = Math.floor(diffMs / 60_000);
  if (diffMin < 1) return "just now";
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return `${diffHr}h ago`;
  const diffDay = Math.floor(diffHr / 24);
  if (diffDay < 30) return `${diffDay}d ago`;
  return d.toLocaleDateString();
}

function formatAbsoluteTime(ts: number | string | undefined): string {
  if (!ts) return "";
  const d = typeof ts === "string" ? new Date(ts) : new Date(ts);
  return d.toLocaleString();
}

// ==================== Rich Conversation Picker ====================

/** Popover combobox for selecting a conversation with rich info display. */
function ConversationPicker({
  value,
  onChange,
  agentId,
  testId,
}: {
  value: string;
  onChange: (v: string) => void;
  agentId?: string;
  testId: string;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const containerRef = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  // Fetch conversations, filtered by agentId
  const { data: conversations, isLoading: convLoading } = useQuery({
    queryKey: ["logs", "conversations-filter", agentId ?? "all"],
    queryFn: () => getConversationDescriptors(100, 0, "", agentId ?? ""),
    staleTime: 60_000,
  });

  // Reset search when agentId changes
  useEffect(() => {
    setSearch("");
  }, [agentId]);

  // Filter conversations by search term (ID, agent name, userId)
  const filtered = useMemo(() => {
    if (!conversations) return [];
    if (!search.trim()) return conversations;
    const q = search.toLowerCase();
    return conversations.filter((conv) => {
      const convId = parseConversationUri(conv.resource);
      return (
        convId.toLowerCase().includes(q) ||
        (conv.name || "").toLowerCase().includes(q) ||
        (conv.agentId || "").toLowerCase().includes(q) ||
        (conv.userId || "").toLowerCase().includes(q) ||
        (conv.conversationState || "").toLowerCase().includes(q)
      );
    });
  }, [conversations, search]);

  // Detect if search looks like a direct conversation ID (24-char hex = MongoDB ObjectId)
  const isDirectId = /^[a-f0-9]{24}$/i.test(search.trim());
  const directIdNotInList =
    isDirectId &&
    !conversations?.some(
      (c) => parseConversationUri(c.resource) === search.trim()
    );

  // Find selected conversation for trigger display
  const selectedConv = conversations?.find(
    (c) => parseConversationUri(c.resource) === value
  );

  // Click outside to close
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (
        containerRef.current &&
        !containerRef.current.contains(e.target as Node)
      ) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [open]);

  // Focus search when opening
  useEffect(() => {
    if (open) {
      // Small delay so the popover renders before focus
      requestAnimationFrame(() => searchRef.current?.focus());
    }
  }, [open]);

  // Keyboard: Escape to close
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === "Escape") {
        setOpen(false);
      }
    },
    []
  );

  const select = useCallback(
    (id: string) => {
      onChange(id);
      setOpen(false);
      setSearch("");
    },
    [onChange]
  );

  return (
    <div ref={containerRef} className="relative" onKeyDown={handleKeyDown} data-testid={testId}>
      {/* Trigger button */}
      <button
        onClick={() => setOpen(!open)}
        className="inline-flex items-center gap-2 rounded-lg border border-input bg-background pe-2 ps-3 py-1.5 text-xs text-foreground transition-colors hover:bg-muted focus:outline-none focus:ring-1 focus:ring-primary"
        data-testid={`${testId}-trigger`}
      >
        <MessageSquare className="h-3 w-3 shrink-0 text-muted-foreground" />
        {selectedConv ? (
          <span className="flex items-center gap-1.5 truncate max-w-[200px]">
            <ConvStateBadge state={selectedConv.conversationState} />
            <span className="truncate">
              {selectedConv.name || selectedConv.agentId || parseConversationUri(selectedConv.resource).substring(0, 10)}
            </span>
            <span className="text-muted-foreground">
              {formatRelativeTime(selectedConv.lastModifiedOn)}
            </span>
          </span>
        ) : (
          <span className="text-muted-foreground">
            {t("logs.allConversations", "All Conversations")}
          </span>
        )}
        {value ? (
          <X
            className="h-3 w-3 shrink-0 text-muted-foreground hover:text-foreground"
            onClick={(e) => {
              e.stopPropagation();
              select("");
            }}
          />
        ) : (
          <ChevronDown className="h-3 w-3 shrink-0 text-muted-foreground" />
        )}
      </button>

      {/* Popover dropdown */}
      {open && (
        <div className="absolute inset-s-0 top-full z-50 mt-1 w-96 max-w-[calc(100vw-2rem)] rounded-xl border border-border bg-card shadow-xl shadow-black/20">
          {/* Search */}
          <div className="flex items-center gap-2 border-b border-border px-3 py-2">
            <Search className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
            <input
              ref={searchRef}
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={t("logs.searchConversations", "Search by ID, agent, user…")}
              className="w-full bg-transparent text-xs text-foreground placeholder:text-muted-foreground focus:outline-none"
              data-testid={`${testId}-search`}
            />
            {search && (
              <button
                onClick={() => setSearch("")}
                className="text-muted-foreground hover:text-foreground"
              >
                <X className="h-3 w-3" />
              </button>
            )}
          </div>

          {/* Options */}
          <div className="max-h-72 overflow-y-auto overscroll-contain" data-testid={`${testId}-list`}>
            {/* "All conversations" option */}
            <button
              onClick={() => select("")}
              className={`flex w-full items-center gap-2 px-3 py-2 text-start text-xs transition-colors hover:bg-muted/50 ${
                !value ? "bg-primary/5 text-primary" : "text-foreground"
              }`}
            >
              {!value && <Check className="h-3 w-3 shrink-0 text-primary" />}
              <MessageSquare className={`h-3.5 w-3.5 shrink-0 ${!value ? "text-primary" : "text-muted-foreground"}`} />
              {t("logs.allConversations", "All Conversations")}
            </button>

            {/* Loading */}
            {convLoading && (
              <div className="flex items-center justify-center py-6">
                <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
              </div>
            )}

            {/* Conversation items */}
            {!convLoading &&
              filtered.map((conv) => {
                const convId = parseConversationUri(conv.resource);
                const isSelected = convId === value;
                return (
                  <button
                    key={convId}
                    onClick={() => select(convId)}
                    className={`flex w-full flex-col gap-1 px-3 py-2.5 text-start transition-colors hover:bg-muted/50 ${
                      isSelected ? "bg-primary/5" : ""
                    }`}
                    title={formatAbsoluteTime(conv.createdOn)}
                  >
                    {/* Row 1: State + Agent + Time */}
                    <div className="flex items-center gap-2">
                      {isSelected && <Check className="h-3 w-3 shrink-0 text-primary" />}
                      <ConvStateBadge state={conv.conversationState} />
                      <span className="truncate text-xs font-medium text-foreground">
                        {conv.name || conv.agentId || "Unnamed"}
                      </span>
                      <span className="ms-auto shrink-0 text-[10px] text-muted-foreground">
                        <Clock className="me-0.5 inline h-2.5 w-2.5" />
                        {formatRelativeTime(conv.lastModifiedOn)}
                      </span>
                    </div>
                    {/* Row 2: ID + Steps + User */}
                    <div className="flex items-center gap-2 text-[10px] text-muted-foreground">
                      <span className="font-mono">{convId.substring(0, 12)}…</span>
                      {conv.conversationStepSize != null && (
                        <span>
                          · {t("logs.stepsCount", { count: conv.conversationStepSize, defaultValue: `${conv.conversationStepSize} steps` })}
                        </span>
                      )}
                      {conv.userId && (
                        <span className="truncate">· {conv.userId}</span>
                      )}
                    </div>
                  </button>
                );
              })}

            {/* Direct ID paste option */}
            {directIdNotInList && (
              <button
                onClick={() => select(search.trim())}
                className="flex w-full items-center gap-2 border-t border-border px-3 py-2.5 text-start text-xs text-primary transition-colors hover:bg-primary/5"
              >
                <Search className="h-3.5 w-3.5 shrink-0" />
                {t("logs.useConversationId", { id: search.trim(), defaultValue: `Use ID: ${search.trim()}` })}
              </button>
            )}

            {/* No results */}
            {!convLoading && filtered.length === 0 && !directIdNotInList && (
              <div className="px-3 py-6 text-center text-xs text-muted-foreground">
                <MessageSquare className="mx-auto mb-2 h-6 w-6 text-muted-foreground/30" />
                {t("logs.noConversationsFound", "No conversations found")}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
