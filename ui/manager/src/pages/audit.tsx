import { useState, useMemo, useCallback } from "react";
import { useTranslation } from "react-i18next";
import {
  ShieldCheck,
  Search,
  Clock,
  DollarSign,
  ChevronDown,
  ChevronRight,
  Brain,
  Zap,
  Eye,
  Cpu,
  FileOutput,
  Settings,
  Hash,
  Loader2,
  Shield,
} from "lucide-react";
import { useAuditTrail, useAuditTrailByBot } from "@/hooks/use-audit";
import type { AuditEntry } from "@/lib/api/audit";

// ─── Constants ───────────────────────────────────────────────────────────────

const TASK_TYPE_STYLES: Record<string, { bg: string; text: string; icon: typeof Brain }> = {
  langchain:    { bg: "bg-purple-500/15", text: "text-purple-400",  icon: Brain },
  behavior:     { bg: "bg-blue-500/15",   text: "text-blue-400",    icon: Zap },
  output:       { bg: "bg-emerald-500/15", text: "text-emerald-400", icon: FileOutput },
  expressions:  { bg: "bg-amber-500/15",  text: "text-amber-400",   icon: Eye },
  httpcalls:    { bg: "bg-orange-500/15",  text: "text-orange-400",  icon: Cpu },
  propertysetter: { bg: "bg-teal-500/15", text: "text-teal-400",    icon: Settings },
};

const DEFAULT_STYLE = { bg: "bg-gray-500/15", text: "text-gray-400", icon: Hash };

const PAGE_SIZE = 100;

type SearchMode = "conversation" | "bot";

// ─── Helpers ─────────────────────────────────────────────────────────────────

function getTaskStyle(taskType: string) {
  return TASK_TYPE_STYLES[taskType] ?? DEFAULT_STYLE;
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function formatCost(cost: number): string {
  if (cost === 0) return "";
  if (cost < 0.01) return `$${cost.toFixed(4)}`;
  return `$${cost.toFixed(2)}`;
}

function groupByStep(entries: AuditEntry[]): Map<number, AuditEntry[]> {
  const groups = new Map<number, AuditEntry[]>();
  for (const entry of entries) {
    const key = entry.stepIndex;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(entry);
  }
  return groups;
}

function formatTimestamp(ts: string): string {
  try {
    return new Intl.DateTimeFormat(undefined, {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    }).format(new Date(ts));
  } catch {
    return ts;
  }
}

// ─── Sub-components ──────────────────────────────────────────────────────────

function JsonBlock({ data, label }: { data: Record<string, unknown> | null; label: string }) {
  const [open, setOpen] = useState(false);
  if (!data || Object.keys(data).length === 0) return null;

  return (
    <div className="mt-2">
      <button
        onClick={() => setOpen(!open)}
        className="flex items-center gap-1.5 text-xs font-medium text-foreground/60 hover:text-foreground/80 transition-colors"
        data-testid={`expand-${label}`}
      >
        {open ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
        {label}
      </button>
      {open && (
        <pre className="mt-1.5 max-h-64 overflow-auto rounded-lg bg-card/50 p-3 text-xs text-foreground/80 border border-border/50">
          {JSON.stringify(data, null, 2)}
        </pre>
      )}
    </div>
  );
}

function TaskCard({ entry, t }: { entry: AuditEntry; t: ReturnType<typeof useTranslation>["t"] }) {
  const style = getTaskStyle(entry.taskType);
  const Icon = style.icon;
  const costStr = formatCost(entry.cost);

  return (
    <div
      className="rounded-xl border border-border/50 bg-card p-4 transition-all hover:border-border hover:shadow-sm"
      data-testid={`audit-entry-${entry.id}`}
    >
      {/* Header row */}
      <div className="flex flex-wrap items-center gap-2">
        {/* Task type badge */}
        <span
          className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${style.bg} ${style.text}`}
          data-testid="task-type-badge"
        >
          <Icon className="h-3.5 w-3.5" />
          {entry.taskType}
        </span>

        {/* Task ID (dimmed) */}
        <span className="text-xs text-foreground/40 font-mono">{entry.taskId}</span>

        {/* Spacer */}
        <div className="flex-1" />

        {/* Duration pill */}
        <span
          className="inline-flex items-center gap-1 rounded-full bg-foreground/5 px-2 py-0.5 text-xs text-foreground/60"
          data-testid="duration-pill"
        >
          <Clock className="h-3 w-3" />
          {formatDuration(entry.durationMs)}
        </span>

        {/* Cost pill */}
        {costStr && (
          <span
            className="inline-flex items-center gap-1 rounded-full bg-amber-500/10 px-2 py-0.5 text-xs font-medium text-amber-500"
            data-testid="cost-pill"
          >
            <DollarSign className="h-3 w-3" />
            {costStr}
          </span>
        )}

        {/* HMAC indicator */}
        {entry.hmac && (
          <span
            className="inline-flex items-center gap-1 rounded-full bg-emerald-500/10 px-1.5 py-0.5 text-xs text-emerald-500"
            title={t("audit.hmacIntegrity", "HMAC integrity verified")}
          >
            <Shield className="h-3 w-3" />
          </span>
        )}
      </div>

      {/* Actions */}
      {entry.actions && entry.actions.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1.5">
          {entry.actions.map((action) => (
            <span
              key={action}
              className="rounded-md bg-foreground/5 px-2 py-0.5 text-[11px] font-medium text-foreground/60"
              data-testid="action-badge"
            >
              {action}
            </span>
          ))}
        </div>
      )}

      {/* Environment + timestamp */}
      <div className="mt-2 flex items-center gap-3 text-[11px] text-foreground/40">
        {entry.environment && (
          <span className="rounded bg-foreground/5 px-1.5 py-0.5" data-testid="env-badge">
            {entry.environment}
          </span>
        )}
        <span>{formatTimestamp(entry.timestamp)}</span>
      </div>

      {/* Expandable detail sections */}
      <JsonBlock data={entry.input} label={t("audit.input", "Input")} />
      <JsonBlock data={entry.output} label={t("audit.output", "Output")} />
      <JsonBlock data={entry.llmDetail} label={t("audit.llmDetail", "LLM Detail")} />
      <JsonBlock data={entry.toolCalls} label={t("audit.toolCalls", "Tool Calls")} />
    </div>
  );
}

// ─── Main Page ───────────────────────────────────────────────────────────────

export function AuditPage() {
  const { t } = useTranslation();
  const [mode, setMode] = useState<SearchMode>("conversation");
  const [conversationId, setConversationId] = useState("");
  const [botId, setBotId] = useState("");
  const [botVersion, setBotVersion] = useState("");
  const [searchValue, setSearchValue] = useState("");
  const [searchBotId, setSearchBotId] = useState("");
  const [searchBotVersion, setSearchBotVersion] = useState<number | undefined>();
  const [skip, setSkip] = useState(0);

  // Queries
  const convQuery = useAuditTrail(
    mode === "conversation" ? searchValue : "",
    skip,
    PAGE_SIZE,
  );
  const botQuery = useAuditTrailByBot(
    mode === "bot" ? searchBotId : "",
    searchBotVersion,
    skip,
    PAGE_SIZE,
  );

  const entries = mode === "conversation" ? convQuery.data : botQuery.data;
  const isLoading = mode === "conversation" ? convQuery.isLoading : botQuery.isLoading;
  const isFetching = mode === "conversation" ? convQuery.isFetching : botQuery.isFetching;
  const hasSearched = mode === "conversation" ? !!searchValue : !!searchBotId;

  // Group entries by step
  const stepGroups = useMemo(() => {
    if (!entries) return new Map<number, AuditEntry[]>();
    return groupByStep(entries);
  }, [entries]);

  // Summary stats
  const stats = useMemo(() => {
    if (!entries || entries.length === 0) return { count: 0, cost: 0, duration: 0 };
    return {
      count: entries.length,
      cost: entries.reduce((sum, e) => sum + e.cost, 0),
      duration: entries.reduce((sum, e) => sum + e.durationMs, 0),
    };
  }, [entries]);

  const handleSearch = useCallback(() => {
    setSkip(0);
    if (mode === "conversation") {
      setSearchValue(conversationId.trim());
    } else {
      setSearchBotId(botId.trim());
      const v = parseInt(botVersion, 10);
      setSearchBotVersion(isNaN(v) ? undefined : v);
    }
  }, [mode, conversationId, botId, botVersion]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === "Enter") handleSearch();
    },
    [handleSearch],
  );

  return (
    <div className="space-y-6" data-testid="audit-page">
      {/* Header */}
      <div>
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary/10">
            <ShieldCheck className="h-5 w-5 text-primary" />
          </div>
          <div>
            <h1 className="text-2xl font-bold text-foreground">
              {t("audit.title", "Audit Trail")}
            </h1>
            <p className="text-sm text-muted-foreground">
              {t("audit.description", "Browse the immutable audit ledger for compliance and debugging.")}
            </p>
          </div>
        </div>
      </div>

      {/* Search bar */}
      <div className="rounded-xl border border-border bg-card p-4">
        {/* Mode toggle */}
        <div className="mb-3 flex items-center gap-2">
          <button
            onClick={() => setMode("conversation")}
            className={`rounded-lg px-3 py-1.5 text-xs font-medium transition-all ${
              mode === "conversation"
                ? "bg-primary text-primary-foreground shadow-sm"
                : "bg-foreground/5 text-foreground/60 hover:text-foreground/80"
            }`}
            data-testid="mode-conversation"
          >
            {t("audit.byConversation", "By Conversation")}
          </button>
          <button
            onClick={() => setMode("bot")}
            className={`rounded-lg px-3 py-1.5 text-xs font-medium transition-all ${
              mode === "bot"
                ? "bg-primary text-primary-foreground shadow-sm"
                : "bg-foreground/5 text-foreground/60 hover:text-foreground/80"
            }`}
            data-testid="mode-bot"
          >
            {t("audit.byBot", "By Bot")}
          </button>
        </div>

        {/* Input fields */}
        <div className="flex items-center gap-3">
          {mode === "conversation" ? (
            <input
              type="text"
              value={conversationId}
              onChange={(e) => setConversationId(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={t("audit.conversationIdPlaceholder", "Enter conversation ID...")}
              className="flex-1 rounded-lg border border-border bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
              data-testid="conversation-input"
            />
          ) : (
            <>
              <input
                type="text"
                value={botId}
                onChange={(e) => setBotId(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder={t("audit.botIdPlaceholder", "Enter bot ID...")}
                className="flex-1 rounded-lg border border-border bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                data-testid="bot-input"
              />
              <input
                type="text"
                value={botVersion}
                onChange={(e) => setBotVersion(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder={t("audit.versionPlaceholder", "Version (optional)")}
                className="w-36 rounded-lg border border-border bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                data-testid="version-input"
              />
            </>
          )}
          <button
            onClick={handleSearch}
            className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 active:scale-[0.98]"
            data-testid="search-button"
          >
            <Search className="h-4 w-4" />
            {t("audit.search", "Search")}
          </button>
        </div>
      </div>

      {/* Summary strip */}
      {hasSearched && entries && entries.length > 0 && (
        <div className="flex items-center gap-4 rounded-xl border border-border bg-card px-4 py-3" data-testid="summary-strip">
          <div className="flex items-center gap-2 text-sm">
            <ShieldCheck className="h-4 w-4 text-primary" />
            <span className="font-semibold text-foreground">{stats.count}</span>
            <span className="text-muted-foreground">{t("audit.entries", "entries")}</span>
          </div>
          <div className="h-4 w-px bg-border" />
          <div className="flex items-center gap-2 text-sm">
            <Clock className="h-4 w-4 text-muted-foreground" />
            <span className="font-semibold text-foreground">{formatDuration(stats.duration)}</span>
            <span className="text-muted-foreground">{t("audit.totalDuration", "total")}</span>
          </div>
          {stats.cost > 0 && (
            <>
              <div className="h-4 w-px bg-border" />
              <div className="flex items-center gap-2 text-sm">
                <DollarSign className="h-4 w-4 text-amber-500" />
                <span className="font-semibold text-amber-500">{formatCost(stats.cost)}</span>
                <span className="text-muted-foreground">{t("audit.totalCost", "total cost")}</span>
              </div>
            </>
          )}
          {isFetching && <Loader2 className="ms-auto h-4 w-4 animate-spin text-muted-foreground" />}
        </div>
      )}

      {/* Loading state */}
      {isLoading && hasSearched && (
        <div className="flex items-center justify-center py-16">
          <Loader2 className="h-8 w-8 animate-spin text-primary" />
        </div>
      )}

      {/* Empty state - no search yet */}
      {!hasSearched && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border bg-card/50 py-16" data-testid="empty-state">
          <ShieldCheck className="h-12 w-12 text-muted-foreground/30" />
          <p className="mt-4 text-sm font-medium text-foreground/60">
            {t("audit.empty", "Search for audit entries")}
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {t("audit.emptyHint", "Enter a conversation or bot ID above to browse the audit trail.")}
          </p>
        </div>
      )}

      {/* Empty state - no results */}
      {hasSearched && !isLoading && entries && entries.length === 0 && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border bg-card/50 py-16">
          <ShieldCheck className="h-12 w-12 text-muted-foreground/30" />
          <p className="mt-4 text-sm font-medium text-foreground/60">
            {t("audit.noResults", "No audit entries found")}
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {t("audit.noResultsHint", "Try a different ID or check that the conversation has been processed.")}
          </p>
        </div>
      )}

      {/* Timeline view */}
      {hasSearched && !isLoading && entries && entries.length > 0 && (
        <div className="space-y-6" data-testid="audit-timeline">
          {[...stepGroups.entries()]
            .sort(([a], [b]) => a - b)
            .map(([stepIdx, stepEntries]) => (
              <div key={stepIdx} className="relative">
                {/* Step header */}
                <div className="mb-3 flex items-center gap-3">
                  <div className="flex h-8 w-8 items-center justify-center rounded-full bg-primary/10 text-sm font-bold text-primary">
                    {stepIdx}
                  </div>
                  <div>
                    <span className="text-sm font-semibold text-foreground">
                      {t("audit.step", "Step")} {stepIdx}
                    </span>
                    {/* Show user input if available */}
                    {stepEntries[0]?.input?.["input:initial"] != null && (
                      <p className="text-xs text-muted-foreground mt-0.5">
                        &ldquo;{String(stepEntries[0].input["input:initial"] as string)}&rdquo;
                      </p>
                    )}
                  </div>
                  <div className="flex-1" />
                  <span className="text-xs text-muted-foreground">
                    {stepEntries.length} {t("audit.tasks", "tasks")}
                  </span>
                </div>

                {/* Task cards with timeline connector */}
                <div className="relative ms-4 border-s-2 border-border/50 ps-6 space-y-3">
                  {stepEntries
                    .sort((a, b) => a.taskIndex - b.taskIndex)
                    .map((entry) => (
                      <div key={entry.id} className="relative">
                        {/* Timeline dot */}
                        <div className="absolute -inset-s-[31px] top-4 h-2.5 w-2.5 rounded-full border-2 border-border bg-card" />
                        <TaskCard entry={entry} t={t} />
                      </div>
                    ))}
                </div>
              </div>
            ))}

          {/* Load more */}
          {entries.length >= PAGE_SIZE && (
            <div className="flex justify-center">
              <button
                onClick={() => setSkip((s) => s + PAGE_SIZE)}
                className="rounded-lg bg-foreground/5 px-6 py-2 text-sm font-medium text-foreground/60 transition-all hover:bg-foreground/10 hover:text-foreground/80"
                data-testid="load-more"
              >
                {t("audit.loadMore", "Load more")}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
