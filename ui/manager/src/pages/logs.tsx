import { useState, useCallback, useRef, useEffect, useMemo } from "react";
import { useTranslation } from "react-i18next";
import {
  ScrollText,
  Pause,
  Play,
  Trash2,
  Wifi,
  WifiOff,
  ChevronDown,
  ChevronRight,
  History,
  Radio,
  Loader2,
  Search,
  Copy,
  Download,
} from "lucide-react";
import { useLogStream, useHistoryLogs, useInstanceId } from "@/hooks/use-logs";
import type { LogEntry } from "@/lib/api/logs";
import type { HistoryFilters } from "@/lib/api/logs";

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
      <div className="mb-4 flex gap-1 rounded-lg border border-border bg-muted/30 p-1">
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
      <div className="mb-3 flex flex-wrap items-center gap-2">
        {/* Connection status */}
        <div
          className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium ${
            sseConnected
              ? "border-green-500/30 bg-green-500/10 text-green-400"
              : "border-red-500/30 bg-red-500/10 text-red-400"
          }`}
          data-testid="sse-status"
        >
          {sseConnected ? (
            <Wifi className="h-3 w-3" />
          ) : (
            <WifiOff className="h-3 w-3" />
          )}
          {sseConnected
            ? t("logs.connected", "Connected")
            : t("logs.disconnected", "Disconnected")}
        </div>

        {/* Instance badge */}
        {instanceInfo && (
          <span className="rounded-full border border-border bg-muted/50 px-2.5 py-1 text-xs text-muted-foreground">
            {instanceInfo.instanceId}
          </span>
        )}

        <div className="flex-1" />

        {/* Agent filter */}
        <input
          type="text"
          placeholder={t("logs.filterAgent", "Agent ID...")}
          value={agentFilter}
          onChange={(e) => setAgentFilter(e.target.value)}
          className="w-36 rounded-lg border border-input bg-background px-3 py-1.5 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-primary"
          data-testid="filter-agent"
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
          <div className="flex h-full items-center justify-center p-8 text-sm text-muted-foreground">
            {sseConnected
              ? t("logs.waitingForLogs", "Waiting for logs...")
              : t("logs.connectingStream", "Connecting to stream...")}
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

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* Filters */}
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <input
          type="text"
          placeholder={t("logs.filterAgent", "Agent ID...")}
          value={filters.agentId ?? ""}
          onChange={(e) => updateFilter("agentId", e.target.value)}
          className="w-36 rounded-lg border border-input bg-background px-3 py-1.5 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-primary"
          data-testid="history-filter-agent"
        />
        <input
          type="text"
          placeholder={t("logs.filterConversation", "Conversation ID...")}
          value={filters.conversationId ?? ""}
          onChange={(e) => updateFilter("conversationId", e.target.value)}
          className="w-44 rounded-lg border border-input bg-background px-3 py-1.5 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-primary"
          data-testid="history-filter-conversation"
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
            className="flex h-full items-center justify-center p-8 text-sm text-muted-foreground"
            data-testid="history-empty"
          >
            {t("logs.noHistoryLogs", "No historical logs found.")}
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
