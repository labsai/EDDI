import { useState, useEffect, useRef, useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { createLogEventSource, getRecentLogs, type LogEntry } from "@/lib/api/logs";
import type { AuthEventSourceHandle } from "@/lib/api/sse-utils";
import { SSE_RECONNECT_BASE_MS, SSE_RECONNECT_MAX_ATTEMPTS, SSE_RECONNECT_MAX_DELAY_MS } from "@/lib/constants";
import { cn } from "@/lib/utils";
import {
  ScrollText,
  Pause,
  Play,
  Trash2,
  Search,
  Circle,
} from "lucide-react";

// ==================== Constants ====================

const MAX_LOG_ENTRIES = 500;

const LEVEL_COLORS: Record<string, string> = {
  ERROR: "text-destructive",
  WARN: "text-amber-500",
  INFO: "text-emerald-500",
  DEBUG: "text-muted-foreground",
  TRACE: "text-muted-foreground/50",
};

const LEVEL_BG: Record<string, string> = {
  ERROR: "bg-destructive/10",
  WARN: "bg-amber-500/10",
  INFO: "bg-emerald-500/10",
  DEBUG: "bg-muted/30",
  TRACE: "bg-muted/20",
};

// ==================== Component ====================

interface LiveLogViewerProps {
  agentId: string | null;
  conversationId: string | null;
}

export function LiveLogViewer({ agentId, conversationId }: LiveLogViewerProps) {
  const { t } = useTranslation();
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [paused, setPaused] = useState(false);
  const pausedRef = useRef(false);
  const [filterLevel, setFilterLevel] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [connected, setConnected] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const handleRef = useRef<AuthEventSourceHandle | null>(null);
  const reconnectTimer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const reconnectAttempts = useRef(0);

  // Keep ref in sync with state for use in SSE callback
  useEffect(() => {
    pausedRef.current = paused;
  }, [paused]);

  // Connect to SSE stream — pausedRef avoids closing/reopening on pause toggle
  useEffect(() => {
    if (!agentId) return;

    handleRef.current?.close();
    clearTimeout(reconnectTimer.current);
    reconnectAttempts.current = 0;

    function connect() {
      const handle = createLogEventSource(
        {
          agentId: agentId!,
          conversationId: conversationId ?? undefined,
        },
        {
          onMessage: (entry) => {
            if (pausedRef.current) return;
            setLogs((prev) => {
              const next = [...prev, entry];
              return next.length > MAX_LOG_ENTRIES ? next.slice(-MAX_LOG_ENTRIES) : next;
            });
          },
          onOpen: () => {
            reconnectAttempts.current = 0;
            setConnected(true);
          },
          onError: () => {
            setConnected(false);
            handleRef.current?.close();
            handleRef.current = null;
            if (reconnectAttempts.current < SSE_RECONNECT_MAX_ATTEMPTS) {
              const delay = Math.min(SSE_RECONNECT_BASE_MS * Math.pow(2, reconnectAttempts.current), SSE_RECONNECT_MAX_DELAY_MS);
              reconnectAttempts.current++;
              clearTimeout(reconnectTimer.current);
              reconnectTimer.current = setTimeout(connect, delay);
            }
          },
        },
      );
      handleRef.current = handle;
    }

    connect();

    return () => {
      clearTimeout(reconnectTimer.current);
      handleRef.current?.close();
      setConnected(false);
    };
  }, [agentId, conversationId]);

  // Load initial logs
  useEffect(() => {
    if (!agentId) return;
    getRecentLogs({ agentId, conversationId: conversationId ?? undefined, limit: 50 })
      .then((entries) => {
        // Only seed from REST if SSE hasn't already provided entries
        setLogs((prev) => (prev.length === 0 ? entries : prev));
      })
      .catch(() => {
        /* ignore — SSE is the primary source */
      });
  }, [agentId, conversationId]);

  // Auto-scroll
  useEffect(() => {
    if (paused) return;
    scrollRef.current?.scrollTo({
      top: scrollRef.current.scrollHeight,
      behavior: "smooth",
    });
  }, [logs, paused]);

  const handleClear = useCallback(() => setLogs([]), []);

  // Filtered logs
  const filteredLogs = useMemo(() => {
    let result = logs;
    if (filterLevel) {
      result = result.filter((l) => l.level === filterLevel);
    }
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      result = result.filter(
        (l) =>
          l.message.toLowerCase().includes(q) ||
          l.loggerName.toLowerCase().includes(q),
      );
    }
    return result;
  }, [logs, filterLevel, searchQuery]);

  if (!agentId) {
    return (
      <div className="flex flex-col items-center gap-2 py-6 text-center">
        <ScrollText className="h-8 w-8 text-muted-foreground/30" />
        <p className="text-sm text-muted-foreground">
          {t("logViewer.noAgent", "Select an agent to view logs")}
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-col" data-testid="live-log-viewer">
      {/* Toolbar */}
      <div className="flex items-center gap-1.5 border-b border-border px-3 py-1.5">
        {/* Connection indicator */}
        <Circle
          className={cn(
            "h-2 w-2 shrink-0 fill-current",
            connected ? "text-emerald-500" : "text-destructive",
          )}
          aria-label={connected ? t("logViewer.connected", "Connected") : t("logViewer.disconnected", "Disconnected")}
          role="status"
        />

        {/* Level filters */}
        {["ERROR", "WARN", "INFO", "DEBUG"].map((level) => (
          <button
            key={level}
            onClick={() => setFilterLevel(filterLevel === level ? null : level)}
            aria-pressed={filterLevel === level}
            className={cn(
              "rounded-full px-2 py-0.5 text-[10px] font-medium transition-colors",
              filterLevel === level
                ? `${LEVEL_BG[level]} ${LEVEL_COLORS[level]}`
                : "text-muted-foreground hover:bg-muted",
            )}
            data-testid={`filter-${level}`}
          >
            {level}
          </button>
        ))}

        <div className="flex-1" />

        {/* Search */}
        <div className="relative">
          <Search className="absolute inset-s-1.5 top-1/2 h-3 w-3 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder={t("logViewer.search", "Search...")}
            className="w-32 rounded border border-input bg-card py-0.5 ps-6 pe-2 text-[10px] focus:outline-none focus:ring-1 focus:ring-ring"
            data-testid="log-search"
          />
        </div>

        {/* Pause/Resume */}
        <button
          onClick={() => setPaused(!paused)}
          className="flex h-6 w-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
          title={paused ? t("logViewer.resume", "Resume") : t("logViewer.pause", "Pause")}
          data-testid="log-pause"
        >
          {paused ? <Play className="h-3 w-3" /> : <Pause className="h-3 w-3" />}
        </button>

        {/* Clear */}
        <button
          onClick={handleClear}
          className="flex h-6 w-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
          title={t("logViewer.clear", "Clear")}
          data-testid="log-clear"
        >
          <Trash2 className="h-3 w-3" />
        </button>
      </div>

      {/* Log entries */}
      <div
        ref={scrollRef}
        role="log"
        aria-live="polite"
        aria-label={t("logViewer.logOutput", "Log output")}
        className="overflow-y-auto font-mono text-[10px] leading-relaxed"
        style={{ maxHeight: "35vh" }}
      >
        {filteredLogs.length === 0 ? (
          <div className="flex items-center justify-center py-8 text-muted-foreground text-xs">
            {t("logViewer.waiting", "Waiting for logs...")}
          </div>
        ) : (
          filteredLogs.map((entry, idx) => (
            <LogLine key={`${entry.timestamp}-${entry.loggerName}-${idx}`} entry={entry} />
          ))
        )}
      </div>
    </div>
  );
}

// ==================== Log Line ====================

function LogLine({ entry }: { entry: LogEntry }) {
  const level = entry.level ?? "INFO";
  const time = entry.timestamp
    ? new Date(entry.timestamp).toLocaleTimeString(undefined, {
        hour12: false,
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        fractionalSecondDigits: 3,
      } as Intl.DateTimeFormatOptions)
    : "";

  const loggerShort = entry.loggerName
    ? entry.loggerName.split(".").pop() ?? entry.loggerName
    : "";

  return (
    <div
      className={cn(
        "flex items-start gap-1.5 border-b border-border/30 px-3 py-0.5 leading-tight",
        LEVEL_BG[level] ?? "bg-transparent",
      )}
      data-testid="log-entry"
    >
      <span className="shrink-0 text-muted-foreground/70 min-w-[70px]">{time}</span>
      <span className={cn("shrink-0 w-10 font-bold", LEVEL_COLORS[level] ?? "text-foreground")}>
        {level.padEnd(5)}
      </span>
      <span className="shrink-0 text-primary/60 w-24 truncate">[{loggerShort}]</span>
      <span className="text-foreground/80 break-all">{entry.message}</span>
    </div>
  );
}
