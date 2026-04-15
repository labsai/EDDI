import { useState, useMemo, useCallback, useEffect, useRef } from "react";
import { useOnboarding } from "@/hooks/use-onboarding";
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
  RefreshCw,
  Download,
  Bot,
  MessageSquare,
  BarChart3,
  ChevronsUpDown,
  Fingerprint,
  ShieldAlert,
} from "lucide-react";
import { useAuditTrail, useAuditTrailByAgent } from "@/hooks/use-audit";
import type { AuditEntry } from "@/lib/api/audit";
import { useDeployedAgents } from "@/hooks/use-chat";

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

type SearchMode = "agent" | "conversation";

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

/** Group entries by conversationId (for agent-mode results) */
function groupByConversation(entries: AuditEntry[]): Map<string, AuditEntry[]> {
  const groups = new Map<string, AuditEntry[]>();
  for (const entry of entries) {
    const key = entry.conversationId;
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

function formatFullTimestamp(ts: string): string {
  try {
    return new Intl.DateTimeFormat(undefined, {
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
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

/** Collapsible step group with animation */
function StepGroup({
  stepIdx,
  stepEntries,
  t,
  defaultOpen = true,
}: {
  stepIdx: number;
  stepEntries: AuditEntry[];
  t: ReturnType<typeof useTranslation>["t"];
  defaultOpen?: boolean;
}) {
  const [open, setOpen] = useState(defaultOpen);
  const stepDuration = stepEntries.reduce((sum, e) => sum + e.durationMs, 0);
  const stepCost = stepEntries.reduce((sum, e) => sum + e.cost, 0);

  return (
    <div className="relative">
      {/* Step header — clickable to collapse */}
      <button
        onClick={() => setOpen(!open)}
        className="mb-3 flex w-full items-center gap-3 text-start group"
        data-testid={`step-header-${stepIdx}`}
      >
        <div className="flex h-8 w-8 items-center justify-center rounded-full bg-primary/10 text-sm font-bold text-primary shrink-0">
          {stepIdx}
        </div>
        <div className="flex-1 min-w-0">
          <span className="text-sm font-semibold text-foreground">
            {t("audit.step", "Step")} {stepIdx}
          </span>
          {/* Show user input if available */}
          {stepEntries[0]?.input?.["input:initial"] != null && (
            <p className="text-xs text-muted-foreground mt-0.5 truncate">
              &ldquo;{String(stepEntries[0].input["input:initial"] as string)}&rdquo;
            </p>
          )}
        </div>
        <div className="flex items-center gap-3 shrink-0">
          {/* Duration for this step */}
          <span className="text-xs text-muted-foreground hidden sm:inline-flex items-center gap-1">
            <Clock className="h-3 w-3" />
            {formatDuration(stepDuration)}
          </span>
          {stepCost > 0 && (
            <span className="text-xs text-amber-500 hidden sm:inline-flex items-center gap-1">
              <DollarSign className="h-3 w-3" />
              {formatCost(stepCost)}
            </span>
          )}
          <span className="text-xs text-muted-foreground">
            {stepEntries.length} {t("audit.tasks", "tasks")}
          </span>
          <ChevronsUpDown className="h-3.5 w-3.5 text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity" />
        </div>
      </button>

      {/* Task cards with timeline connector */}
      {open && (
        <div className="relative ms-4 border-s-2 border-border/50 ps-6 space-y-3 animate-in fade-in slide-in-from-top-1 duration-200">
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
      )}
    </div>
  );
}

/** Conversation group wrapper for agent-mode results */
function ConversationGroup({
  conversationId,
  entries,
  t,
}: {
  conversationId: string;
  entries: AuditEntry[];
  t: ReturnType<typeof useTranslation>["t"];
}) {
  const [open, setOpen] = useState(true);
  const stepGroups = useMemo(() => groupByStep(entries), [entries]);
  const totalDuration = entries.reduce((sum, e) => sum + e.durationMs, 0);
  const totalCost = entries.reduce((sum, e) => sum + e.cost, 0);
  const timestamps = entries.map((e) => e.timestamp).sort();
  const firstTs = timestamps[0];

  return (
    <div className="rounded-xl border border-border bg-card/50 overflow-hidden" data-testid={`conversation-group-${conversationId}`}>
      {/* Conversation header */}
      <button
        onClick={() => setOpen(!open)}
        className="flex w-full items-center gap-3 p-4 text-start hover:bg-muted/30 transition-colors group"
      >
        <MessageSquare className="h-4 w-4 text-primary shrink-0" />
        <div className="flex-1 min-w-0">
          <span className="text-sm font-semibold text-foreground font-mono">
            {conversationId.length > 24 ? `${conversationId.slice(0, 12)}…${conversationId.slice(-8)}` : conversationId}
          </span>
          {firstTs && (
            <span className="ms-2 text-xs text-muted-foreground">
              {formatFullTimestamp(firstTs)}
            </span>
          )}
        </div>
        <div className="flex items-center gap-3 shrink-0">
          <span className="text-xs text-muted-foreground">
            {entries.length} {t("audit.entries", "entries")}
          </span>
          <span className="text-xs text-muted-foreground hidden sm:inline-flex items-center gap-1">
            <Clock className="h-3 w-3" />
            {formatDuration(totalDuration)}
          </span>
          {totalCost > 0 && (
            <span className="text-xs text-amber-500 hidden sm:inline-flex items-center gap-1">
              <DollarSign className="h-3 w-3" />
              {formatCost(totalCost)}
            </span>
          )}
          <ChevronDown className={`h-4 w-4 text-muted-foreground transition-transform ${open ? "" : "-rotate-90"}`} />
        </div>
      </button>

      {/* Step groups inside */}
      {open && (
        <div className="border-t border-border/50 p-4 space-y-6">
          {[...stepGroups.entries()]
            .sort(([a], [b]) => a - b)
            .map(([stepIdx, stepEntries]) => (
              <StepGroup key={stepIdx} stepIdx={stepIdx} stepEntries={stepEntries} t={t} />
            ))}
        </div>
      )}
    </div>
  );
}

/** Loading skeleton */
function LoadingSkeleton() {
  return (
    <div className="space-y-4 animate-pulse" data-testid="loading-skeleton">
      {Array.from({ length: 3 }).map((_, i) => (
        <div key={i} className="rounded-xl border border-border bg-card p-4 space-y-3">
          <div className="flex items-center gap-3">
            <div className="h-8 w-8 rounded-full bg-foreground/5" />
            <div className="h-4 w-32 rounded bg-foreground/5" />
            <div className="flex-1" />
            <div className="h-4 w-16 rounded bg-foreground/5" />
          </div>
          <div className="ms-11 space-y-2">
            <div className="h-16 rounded-lg bg-foreground/5" />
            <div className="h-16 rounded-lg bg-foreground/5" />
          </div>
        </div>
      ))}
    </div>
  );
}

// ─── Main Page ───────────────────────────────────────────────────────────────

export function AuditPage() {
  const { t } = useTranslation();

  const maybeAutoStart = useOnboarding((s) => s.maybeAutoStart);
  useEffect(() => { const t = setTimeout(() => maybeAutoStart("audit"), 500); return () => clearTimeout(t); }, [maybeAutoStart]);

  // Default to agent mode (primary), conversation is secondary
  const [mode, setMode] = useState<SearchMode>("agent");
  const [conversationId, setConversationId] = useState("");
  const [searchValue, setSearchValue] = useState("");

  // Agent mode state — search triggers immediately on selection
  const [selectedAgentId, setSelectedAgentId] = useState("");
  const [agentVersion, setAgentVersion] = useState("");
  const [activeAgentId, setActiveAgentId] = useState("");
  const [activeAgentVersion, setActiveAgentVersion] = useState<number | undefined>();

  const [skip, setSkip] = useState(0);

  // Queries
  const convQuery = useAuditTrail(
    mode === "conversation" ? searchValue : "",
    skip,
    PAGE_SIZE,
  );
  const agentQuery = useAuditTrailByAgent(
    mode === "agent" ? activeAgentId : "",
    activeAgentVersion,
    skip,
    PAGE_SIZE,
  );

  const entries = mode === "conversation" ? convQuery.data : agentQuery.data;
  const isLoading = mode === "conversation" ? convQuery.isLoading : agentQuery.isLoading;
  const isFetching = mode === "conversation" ? convQuery.isFetching : agentQuery.isFetching;
  const hasSearched = mode === "conversation" ? !!searchValue : !!activeAgentId;

  // Group entries by step (conversation mode) or by conversation then step (agent mode)
  const stepGroups = useMemo(() => {
    if (!entries) return new Map<number, AuditEntry[]>();
    return groupByStep(entries);
  }, [entries]);

  const conversationGroups = useMemo(() => {
    if (!entries || mode !== "agent") return new Map<string, AuditEntry[]>();
    return groupByConversation(entries);
  }, [entries, mode]);

  // Summary stats
  const stats = useMemo(() => {
    if (!entries || entries.length === 0) return { count: 0, cost: 0, duration: 0, signed: 0 };
    return {
      count: entries.length,
      cost: entries.reduce((sum, e) => sum + e.cost, 0),
      duration: entries.reduce((sum, e) => sum + e.durationMs, 0),
      signed: entries.filter((e) => !!e.hmac).length,
    };
  }, [entries]);

  // ─── Agent mode: auto-search on selection ────────────────────
  const handleAgentChange = useCallback((newAgentId: string) => {
    setSelectedAgentId(newAgentId);
    setSkip(0);
    if (newAgentId) {
      setActiveAgentId(newAgentId);
      const v = parseInt(agentVersion, 10);
      setActiveAgentVersion(isNaN(v) ? undefined : v);
    } else {
      setActiveAgentId("");
      setActiveAgentVersion(undefined);
    }
  }, [agentVersion]);

  // When version changes and agent is selected, re-search
  const handleVersionChange = useCallback((newVersion: string) => {
    setAgentVersion(newVersion);
    if (selectedAgentId) {
      setSkip(0);
      setActiveAgentId(selectedAgentId);
      const v = parseInt(newVersion, 10);
      setActiveAgentVersion(isNaN(v) ? undefined : v);
    }
  }, [selectedAgentId]);

  // ─── Conversation mode: search on Enter or button ────────────
  const handleConversationSearch = useCallback(() => {
    const trimmed = conversationId.trim();
    if (!trimmed) return;
    setSkip(0);
    setSearchValue(trimmed);
  }, [conversationId]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === "Enter") handleConversationSearch();
    },
    [handleConversationSearch],
  );

  // Auto-refresh
  const [autoRefresh, setAutoRefresh] = useState(false);
  const activeQueryFn = mode === "conversation" ? convQuery.refetch : agentQuery.refetch;
  const autoRefreshRef = useRef(autoRefresh);
  autoRefreshRef.current = autoRefresh;

  useEffect(() => {
    if (!autoRefresh || !hasSearched) return;
    const id = setInterval(() => {
      if (autoRefreshRef.current) activeQueryFn();
    }, 10_000);
    return () => clearInterval(id);
  }, [autoRefresh, hasSearched, activeQueryFn]);

  // Export to JSON
  const handleExport = useCallback(() => {
    if (!entries || entries.length === 0) return;
    const blob = new Blob([JSON.stringify(entries, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `audit-${searchValue || activeAgentId || "export"}-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(url);
  }, [entries, searchValue, activeAgentId]);

  // Data for dropdowns
  const { data: deployedAgents } = useDeployedAgents();

  // Find agent name for display
  const activeAgentName = useMemo(() => {
    if (!activeAgentId || !deployedAgents) return null;
    return deployedAgents.find((a) => a.id === activeAgentId)?.name ?? null;
  }, [activeAgentId, deployedAgents]);

  return (
    <div className="space-y-6" data-testid="audit-page">
      {/* Header */}
      <div>
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary/10">
            <ShieldCheck className="h-5 w-5 text-primary" />
          </div>
          <div className="flex-1">
            <h1 className="text-2xl font-bold text-foreground">
              {t("audit.title", "Audit Trail")}
            </h1>
            <p className="text-sm text-muted-foreground">
              {t("audit.description", "Browse the immutable audit ledger for compliance and debugging.")}
            </p>
          </div>
          <div className="flex items-center gap-2">
            {/* Export */}
            <button
              onClick={handleExport}
              disabled={!entries || entries.length === 0}
              className="inline-flex items-center gap-1.5 rounded-lg border border-input bg-background px-3 py-2 text-sm font-medium text-foreground transition-colors hover:bg-muted disabled:opacity-50"
              data-testid="export-btn"
            >
              <Download className="h-4 w-4" />
              {t("audit.export", "Export")}
            </button>
          </div>
        </div>
      </div>

      {/* Search bar */}
      <div className="rounded-xl border border-border bg-card p-4">
        {/* Mode toggle */}
        <div className="mb-3 flex items-center gap-2" data-tour="audit-mode-toggle">
          <button
            onClick={() => setMode("agent")}
            className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition-all ${
              mode === "agent"
                ? "bg-primary text-primary-foreground shadow-sm"
                : "bg-foreground/5 text-foreground/60 hover:text-foreground/80"
            }`}
            data-testid="mode-agent"
          >
            <Bot className="h-3.5 w-3.5" />
            {t("audit.byAgent", "By Agent")}
          </button>
          <button
            onClick={() => setMode("conversation")}
            className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition-all ${
              mode === "conversation"
                ? "bg-primary text-primary-foreground shadow-sm"
                : "bg-foreground/5 text-foreground/60 hover:text-foreground/80"
            }`}
            data-testid="mode-conversation"
          >
            <MessageSquare className="h-3.5 w-3.5" />
            {t("audit.byConversation", "By Conversation")}
          </button>
        </div>

        {/* Input fields */}
        <div className="flex items-center gap-3">
          {mode === "agent" ? (
            <>
              <div className="relative flex-1">
                <select
                  value={selectedAgentId}
                  onChange={(e) => handleAgentChange(e.target.value)}
                  className="w-full appearance-none rounded-lg border border-border bg-background pe-8 ps-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                  data-testid="agent-input"
                >
                  <option value="">{t("audit.selectAgent", "Select an agent…")}</option>
                  {deployedAgents?.map((agent) => (
                    <option key={agent.id} value={agent.id}>
                      {agent.name || agent.id}
                    </option>
                  ))}
                </select>
                <ChevronDown className="pointer-events-none absolute inset-e-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              </div>
              <input
                type="text"
                value={agentVersion}
                onChange={(e) => handleVersionChange(e.target.value)}
                placeholder={t("audit.versionPlaceholder", "Version (optional)")}
                className="w-36 rounded-lg border border-border bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                data-testid="version-input"
              />
            </>
          ) : (
            <>
              <input
                type="text"
                value={conversationId}
                onChange={(e) => setConversationId(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder={t("audit.conversationIdPlaceholder", "Enter conversation ID...")}
                className="flex-1 rounded-lg border border-border bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                data-testid="conversation-input"
              />
              <button
                onClick={handleConversationSearch}
                disabled={!conversationId.trim()}
                className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed"
                data-testid="search-button"
              >
                <Search className="h-4 w-4" />
                {t("audit.search", "Search")}
              </button>
            </>
          )}
          {/* Auto-refresh toggle */}
          {hasSearched && (
            <button
              onClick={() => setAutoRefresh((p) => !p)}
              className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
                autoRefresh
                  ? "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400"
                  : "bg-foreground/5 text-muted-foreground hover:text-foreground"
              }`}
              title={t("audit.autoRefreshToggle", "Toggle auto-refresh (every 10s)")}
              data-testid="auto-refresh-toggle"
            >
              <RefreshCw className={`h-4 w-4 ${autoRefresh ? "animate-spin" : ""}`} />
              {autoRefresh ? t("audit.autoRefreshOn", "Auto") : t("audit.autoRefreshOff", "Auto")}
            </button>
          )}
        </div>
      </div>

      {/* Summary strip */}
      {hasSearched && entries && entries.length > 0 && (
        <div className="flex items-center gap-4 rounded-xl border border-border bg-card px-4 py-3" data-testid="summary-strip">
          {/* Context label */}
          {activeAgentName && mode === "agent" && (
            <>
              <div className="flex items-center gap-2 text-sm">
                <Bot className="h-4 w-4 text-primary" />
                <span className="font-semibold text-foreground">{activeAgentName}</span>
              </div>
              <div className="h-4 w-px bg-border" />
            </>
          )}
          <div className="flex items-center gap-2 text-sm">
            <BarChart3 className="h-4 w-4 text-primary" />
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
          {mode === "agent" && conversationGroups.size > 0 && (
            <>
              <div className="h-4 w-px bg-border" />
              <div className="flex items-center gap-2 text-sm">
                <MessageSquare className="h-4 w-4 text-muted-foreground" />
                <span className="font-semibold text-foreground">{conversationGroups.size}</span>
                <span className="text-muted-foreground">{t("audit.conversations", "conversations")}</span>
              </div>
            </>
          )}
          {isFetching && <Loader2 className="ms-auto h-4 w-4 animate-spin text-muted-foreground" />}
        </div>
      )}

      {/* Integrity trust banner */}
      {hasSearched && entries && entries.length > 0 && (
        <div
          className={`flex items-center gap-3 rounded-xl border px-4 py-3 ${
            stats.signed === stats.count
              ? "border-emerald-500/30 bg-emerald-500/5"
              : stats.signed > 0
                ? "border-amber-500/30 bg-amber-500/5"
                : "border-border bg-card"
          }`}
          data-testid="integrity-banner"
        >
          {stats.signed === stats.count ? (
            <>
              <div className="flex h-8 w-8 items-center justify-center rounded-full bg-emerald-500/15 shrink-0">
                <Fingerprint className="h-4 w-4 text-emerald-500" />
              </div>
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-emerald-700 dark:text-emerald-400">
                  {t("audit.integrityVerified", "All entries cryptographically signed")}
                </p>
                <p className="text-xs text-emerald-600/70 dark:text-emerald-400/60">
                  {t("audit.integrityVerifiedDesc", "{{count}} of {{total}} entries have valid HMAC signatures — tamper-proof audit trail", { count: stats.signed, total: stats.count })}
                </p>
              </div>
              <div className="shrink-0 flex items-center gap-1.5 rounded-full bg-emerald-500/10 px-2.5 py-1">
                <ShieldCheck className="h-3.5 w-3.5 text-emerald-500" />
                <span className="text-xs font-semibold text-emerald-600 dark:text-emerald-400">
                  {t("audit.integrityPass", "VERIFIED")}
                </span>
              </div>
            </>
          ) : stats.signed > 0 ? (
            <>
              <div className="flex h-8 w-8 items-center justify-center rounded-full bg-amber-500/15 shrink-0">
                <ShieldAlert className="h-4 w-4 text-amber-500" />
              </div>
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-amber-700 dark:text-amber-400">
                  {t("audit.integrityPartial", "Partially signed audit trail")}
                </p>
                <p className="text-xs text-amber-600/70 dark:text-amber-400/60">
                  {t("audit.integrityPartialDesc", "{{count}} of {{total}} entries have HMAC signatures", { count: stats.signed, total: stats.count })}
                </p>
              </div>
              <div className="shrink-0 flex items-center gap-1.5 rounded-full bg-amber-500/10 px-2.5 py-1">
                <Fingerprint className="h-3.5 w-3.5 text-amber-500" />
                <span className="text-xs font-semibold text-amber-600 dark:text-amber-400">
                  {stats.signed}/{stats.count}
                </span>
              </div>
            </>
          ) : (
            <>
              <div className="flex h-8 w-8 items-center justify-center rounded-full bg-foreground/5 shrink-0">
                <Shield className="h-4 w-4 text-muted-foreground" />
              </div>
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-foreground/70">
                  {t("audit.integrityNone", "Unsigned audit entries")}
                </p>
                <p className="text-xs text-muted-foreground">
                  {t("audit.integrityNoneDesc", "Configure a vault master key to enable automatic HMAC-SHA256 signing on all future audit entries.")}
                </p>
              </div>
            </>
          )}
        </div>
      )}

      {/* Loading state */}
      {isLoading && hasSearched && <LoadingSkeleton />}

      {/* Empty state - no search yet */}
      {!hasSearched && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border bg-card/50 py-16" data-testid="empty-state">
          <ShieldCheck className="h-12 w-12 text-muted-foreground/30" />
          <p className="mt-4 text-sm font-medium text-foreground/60">
            {mode === "agent"
              ? t("audit.emptyAgent", "Select an agent to view its audit trail")
              : t("audit.empty", "Search for audit entries")}
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {mode === "agent"
              ? t("audit.emptyAgentHint", "Choose a deployed agent from the dropdown above to see all its audit records.")
              : t("audit.emptyHint", "Enter a conversation ID above to browse the audit trail.")}
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
        <div className="space-y-4" data-testid="audit-timeline">
          {mode === "agent" && conversationGroups.size > 1 ? (
            /* Agent mode with multiple conversations — group by conversation */
            [...conversationGroups.entries()].map(([convId, convEntries]) => (
              <ConversationGroup
                key={convId}
                conversationId={convId}
                entries={convEntries}
                t={t}
              />
            ))
          ) : (
            /* Conversation mode or single conversation in agent mode — flat step view */
            [...stepGroups.entries()]
              .sort(([a], [b]) => a - b)
              .map(([stepIdx, stepEntries]) => (
                <StepGroup
                  key={stepIdx}
                  stepIdx={stepIdx}
                  stepEntries={stepEntries}
                  t={t}
                />
              ))
          )}

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
