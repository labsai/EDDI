import { useState, useEffect, useRef, useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { createLogEventSource, getRecentLogs, type LogEntry } from "@/lib/api/logs";
import type { BearerEventSource } from "@/lib/bearer-event-source";
import { cn } from "@/lib/utils";
import { logEntryKey, mergeNewestFirst } from "@/lib/log-entries";
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
const SEED_LIMIT = 50;

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
  // Newest first, de-duplicated (see mergeNewestFirst). Rendered oldest-first.
  const [logs, setLogs] = useState<LogEntry[]>([]);
  // Pausing freezes what is shown; lines keep being collected underneath so
  // resuming does not silently skip whatever was logged during the pause.
  const [pausedSnapshot, setPausedSnapshot] = useState<LogEntry[] | null>(null);
  const paused = pausedSnapshot !== null;
  const [filterLevel, setFilterLevel] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [connected, setConnected] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const eventSourceRef = useRef<BearerEventSource | null>(null);

  // A different agent or conversation is a different log; do not carry the
  // previous one's lines (or a paused snapshot of them) over.
  const [scopeKey, setScopeKey] = useState(`${agentId}|${conversationId}`);
  if (scopeKey !== `${agentId}|${conversationId}`) {
    setScopeKey(`${agentId}|${conversationId}`);
    setLogs([]);
    setPausedSnapshot(null);
  }

  // Connect to the SSE stream and (re)seed from the ring buffer on every open.
  //
  // The seed used to be a separate effect that REPLACED the list with the REST
  // result whenever it resolved — discarding any live lines that had already
  // arrived, and (the REST list being newest-first while this view appends
  // oldest-first) showing the history upside down. It also ran only once, so a
  // reconnect left a silent gap. Merging on every open fixes all three; the
  // merge de-duplicates the up-to-50 lines the backend replays on connect.
  useEffect(() => {
    if (!agentId) return;
    const scope = {
      agentId,
      conversationId: conversationId ?? undefined,
    };

    const es = createLogEventSource(scope);

    const handleEvent = (event: MessageEvent) => {
      try {
        const entry: LogEntry = JSON.parse(event.data);
        setLogs((prev) => mergeNewestFirst(prev, [entry], MAX_LOG_ENTRIES));
      } catch {
        // Ignore malformed log events
      }
    };

    es.addEventListener("log", handleEvent);
    // Fallback for backends that send unnamed SSE events
    es.onmessage = handleEvent;

    const seed = () =>
      getRecentLogs({ ...scope, limit: SEED_LIMIT })
        .then((recent) =>
          setLogs((prev) => mergeNewestFirst(prev, recent, MAX_LOG_ENTRIES))
        )
        .catch(() => {
          /* the live stream still works */
        });

    // Seed straight away (the history is useful even if the stream is refused),
    // then again on every RE-open to fill whatever a dropped connection missed.
    let opened = false;
    es.onopen = () => {
      setConnected(true);
      if (opened) void seed();
      opened = true;
    };
    void seed();
    es.onerror = () => setConnected(false);

    eventSourceRef.current = es;

    return () => {
      es.close();
      eventSourceRef.current = null;
      setConnected(false);
    };
  }, [agentId, conversationId]);

  const shown = pausedSnapshot ?? logs;

  // Auto-scroll
  useEffect(() => {
    if (paused) return;
    scrollRef.current?.scrollTo({
      top: scrollRef.current.scrollHeight,
      behavior: "smooth",
    });
  }, [logs, paused]);

  const handleClear = useCallback(() => {
    setLogs([]);
    setPausedSnapshot((s) => (s === null ? null : []));
  }, []);

  const togglePause = useCallback(() => {
    setPausedSnapshot((s) => (s === null ? logs : null));
  }, [logs]);

  // Filtered logs, oldest first (tail at the bottom)
  const filteredLogs = useMemo(() => {
    let result = shown;
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
    return [...result].reverse();
  }, [shown, filterLevel, searchQuery]);

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
        <span className="text-[10px] font-mono text-muted-foreground tabular-nums">
          {filteredLogs.length}
        </span>

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
          <Search className="absolute start-1.5 top-1/2 h-3 w-3 -translate-y-1/2 text-muted-foreground" />
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
          onClick={togglePause}
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
          <div className="flex flex-col items-center gap-2 py-8 text-center">
            <ScrollText className="h-6 w-6 text-muted-foreground/30" />
            <p className="text-xs text-muted-foreground">
              {shown.length === 0
                ? t("logViewer.waiting", "Waiting for logs...")
                : t("logViewer.noMatch", "No logs match your filter")}
            </p>
          </div>
        ) : (
          filteredLogs.map((entry) => (
            <LogLine key={logEntryKey(entry)} entry={entry} />
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
