import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { getAuditTrail, type AuditEntry } from "@/lib/api/audit";
import { cn, formatDuration } from "@/lib/utils";
import {
  MessageSquareCode,
  Copy,
  Check,
  RotateCcw,
  Wrench,
  ChevronDown,
  AlertTriangle,
} from "lucide-react";
import { api } from "@/lib/api-client";

// ==================== Component ====================

interface PromptViewerProps {
  conversationId: string | null;
}

export function PromptViewer({ conversationId }: PromptViewerProps) {
  const { t } = useTranslation();

  const { data: auditEntries, isError } = useQuery({
    queryKey: ["audit", "promptViewer", conversationId],
    queryFn: () => getAuditTrail(conversationId!, 0, 200),
    enabled: !!conversationId,
    staleTime: 10_000,
  });

  // Find LLM entries (langchain/llm tasks with llmDetail)
  const llmEntries = useMemo(() => {
    if (!auditEntries) return [];
    return auditEntries
      .filter((e) => e.llmDetail != null)
      .sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime());
  }, [auditEntries]);

  const [selectedIndex, setSelectedIndex] = useState<number | null>(null);
  const selectedEntry = selectedIndex !== null
    ? llmEntries[selectedIndex]
    : llmEntries[llmEntries.length - 1];

  if (!conversationId) {
    return (
      <EmptyState
        message={t("promptViewer.noConversation", "Start a conversation to inspect prompts")}
      />
    );
  }

  if (isError && !auditEntries) {
    return (
      <div className="flex flex-col items-center gap-2 py-6 text-center" data-testid="prompt-viewer-error">
        <AlertTriangle className="h-8 w-8 text-destructive/50" />
        <p className="text-sm text-muted-foreground">
          {t("promptViewer.error", "Failed to load prompt data")}
        </p>
      </div>
    );
  }

  if (llmEntries.length === 0) {
    return (
      <EmptyState
        message={t("promptViewer.noLlm", "No LLM interactions found yet")}
      />
    );
  }

  return (
    <div className="flex flex-col gap-3 p-3" data-testid="prompt-viewer">
      {/* Turn selector */}
      {llmEntries.length > 1 && (
        <div className="flex items-center gap-2">
          <span className="text-xs font-medium text-muted-foreground">
            {t("promptViewer.turn", "Turn")}
          </span>
          <select
            value={selectedIndex ?? "latest"}
            onChange={(e) => {
              const v = e.target.value;
              setSelectedIndex(v === "latest" ? null : Number(v));
            }}
            className="rounded-md border border-input bg-card px-2 py-1 text-xs"
            data-testid="prompt-turn-selector"
          >
            <option value="latest">
              {t("promptViewer.latest", "Latest")} ({t("promptViewer.step", "Step")} {llmEntries.length})
            </option>
            {llmEntries.map((entry, idx) => (
              <option key={idx} value={idx}>
                {t("promptViewer.step", "Step")} {entry.stepIndex + 1} — {entry.taskType}
              </option>
            ))}
          </select>
        </div>
      )}

      {/* Prompt Detail */}
      {selectedEntry && <PromptDetail entry={selectedEntry} conversationId={conversationId} />}
    </div>
  );
}

// ==================== Prompt Detail ====================

function PromptDetail({
  entry,
  conversationId,
}: {
  entry: AuditEntry;
  conversationId: string;
}) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);
  const [replaying, setReplaying] = useState(false);

  const llm = entry.llmDetail as Record<string, unknown> | null;
  const compiledPrompt = llm?.compiledPrompt as string | undefined;
  const modelResponse = llm?.modelResponse as string | undefined;
  const modelName = llm?.modelName as string | undefined;
  const tokenUsage = llm?.tokenUsage as Record<string, number> | undefined;
  const toolCalls = entry.toolCalls;

  // Parse compiled prompt into message segments
  const messages = useMemo(() => {
    if (!compiledPrompt) return [];
    return parsePromptMessages(compiledPrompt);
  }, [compiledPrompt]);

  const handleCopy = async () => {
    const text = compiledPrompt ?? JSON.stringify(llm, null, 2);
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard API not available (e.g. HTTP context)
    }
  };

  const handleReplay = async () => {
    try {
      setReplaying(true);
      await api.post(`/agents/${conversationId}/rerun`);
    } catch {
      // Replay may not be supported
    } finally {
      setReplaying(false);
    }
  };

  return (
    <div className="space-y-3">
      {/* Header badge */}
      <div className="flex items-center gap-2 text-xs text-muted-foreground">
        <span className="font-medium text-foreground">
          {t("promptViewer.step", "Step")} {entry.stepIndex + 1}
        </span>
        <span className="rounded bg-emerald-500/10 px-1.5 py-0.5 text-[10px] font-medium text-emerald-500">
          {entry.taskType}
        </span>
        {entry.durationMs > 0 && (
          <span className="font-mono">
            {formatDuration(entry.durationMs)}
          </span>
        )}
      </div>

      {/* Message cards */}
      {messages.length > 0 ? (
        <div className="space-y-2">
          {messages.map((msg, i) => (
            <MessageCard key={i} role={msg.role} content={msg.content} />
          ))}
        </div>
      ) : compiledPrompt ? (
        <div className="rounded-lg border border-border bg-card p-3">
          <pre className="text-[11px] font-mono text-foreground/80 whitespace-pre-wrap break-all max-h-64 overflow-y-auto">
            {compiledPrompt}
          </pre>
        </div>
      ) : null}

      {/* Model response */}
      {modelResponse && (
        <MessageCard role="assistant" content={modelResponse} />
      )}

      {/* Tool calls */}
      {toolCalls && toolCalls.length > 0 && (
        <ToolCallsSection toolCalls={toolCalls} />
      )}

      {/* Metrics strip */}
      <div className="flex flex-wrap gap-x-4 gap-y-1 rounded-lg border border-border bg-card/50 px-3 py-2 text-[10px] text-muted-foreground">
        {tokenUsage && (
          <span>
            <strong className="text-foreground">{t("promptViewer.tokens", "Tokens")}:</strong>{" "}
            {(tokenUsage.inputTokens ?? 0).toLocaleString()} {t("costDashboard.in", "in")} / {(tokenUsage.outputTokens ?? 0).toLocaleString()} {t("costDashboard.out", "out")}
          </span>
        )}
        {modelName && (
          <span>
            <strong className="text-foreground">{t("promptViewer.model", "Model")}:</strong>{" "}
            {modelName}
          </span>
        )}
        {typeof llm?.temperature === 'number' && (
          <span>
            <strong className="text-foreground">{t("promptViewer.temperature", "Temp")}:</strong>{" "}
            {llm.temperature as number}
          </span>
        )}
        {entry.cost > 0 && (
          <span>
            <strong className="text-foreground">{t("promptViewer.cost", "Cost")}:</strong>{" "}
            ${entry.cost < 0.01 ? entry.cost.toFixed(4) : entry.cost.toFixed(2)}
          </span>
        )}
      </div>

      {/* Action buttons */}
      <div className="flex gap-2">
        <button
          onClick={handleCopy}
          className="inline-flex items-center gap-1 rounded-md border border-input px-2.5 py-1.5 text-xs font-medium text-foreground transition-colors hover:bg-secondary"
          data-testid="copy-prompt"
        >
          {copied ? (
            <Check className="h-3 w-3 text-emerald-500" />
          ) : (
            <Copy className="h-3 w-3" />
          )}
          {copied
            ? t("promptViewer.copied", "Copied!")
            : t("promptViewer.copy", "Copy Prompt")}
        </button>
        <button
          onClick={handleReplay}
          disabled={replaying}
          className="inline-flex items-center gap-1 rounded-md border border-input px-2.5 py-1.5 text-xs font-medium text-foreground transition-colors hover:bg-secondary disabled:opacity-50"
          data-testid="replay-turn"
        >
          <RotateCcw className={cn("h-3 w-3", replaying && "animate-spin")} />
          {t("promptViewer.replay", "Replay This Turn")}
        </button>
      </div>
    </div>
  );
}

// ==================== Message Card ====================

const ROLE_CONFIG = {
  system: { labelKey: "promptViewer.roleSystem", fallback: "SYSTEM", borderClass: "border-primary", textClass: "text-primary" },
  user: { labelKey: "promptViewer.roleUser", fallback: "USER", borderClass: "border-blue-500", textClass: "text-blue-500" },
  assistant: { labelKey: "promptViewer.roleAssistant", fallback: "ASSISTANT", borderClass: "border-emerald-500", textClass: "text-emerald-500" },
  tool: { labelKey: "promptViewer.roleTool", fallback: "TOOL", borderClass: "border-amber-500", textClass: "text-amber-500" },
} as const;

function MessageCard({ role, content }: { role: string; content: string }) {
  const { t } = useTranslation();
  // System collapsed by default, others expanded
  const [expanded, setExpanded] = useState(role !== "system");
  const [copied, setCopied] = useState(false);
  const config = ROLE_CONFIG[role as keyof typeof ROLE_CONFIG] ?? ROLE_CONFIG.assistant;
  const label = t(config.labelKey, config.fallback).toUpperCase();
  
  const handleCopy = async (e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Ignored
    }
  };

  return (
    <div className={cn("flex flex-col border-s-2 bg-card/30 ps-3 py-2 mb-2", config.borderClass)}>
      <div className="flex items-center justify-between">
        <span className={cn("text-[10px] font-bold tracking-wider", config.textClass)}>
          {label}
        </span>
        <button
          onClick={handleCopy}
          className="flex items-center gap-1 text-[10px] text-muted-foreground hover:text-foreground transition-colors"
        >
          {copied ? <Check className="h-3 w-3 text-emerald-500" /> : <Copy className="h-3 w-3" />}
          {copied ? t("common.copied", "Copied") : t("common.copy", "Copy")}
        </button>
      </div>

      <div className="mt-1">
        {expanded ? (
          <pre className="text-xs font-mono text-foreground/80 whitespace-pre-wrap break-all max-h-[200px] overflow-y-auto mt-1">
            {content}
          </pre>
        ) : (
          <div className="relative">
            <pre className="text-xs font-mono text-foreground/80 whitespace-pre-wrap break-all line-clamp-2 mt-1">
              {content}
            </pre>
            {content.split('\n').length > 2 || content.length > 100 ? (
              <button
                onClick={() => setExpanded(true)}
                className="text-[10px] text-muted-foreground hover:text-foreground mt-1 underline"
              >
                {t("promptViewer.showMore", "Show more")}
              </button>
            ) : null}
          </div>
        )}
        {expanded && (
          <button
            onClick={() => setExpanded(false)}
            className="text-[10px] text-muted-foreground hover:text-foreground mt-2 flex items-center gap-1"
          >
            <ChevronDown className="h-3 w-3 rotate-180" />
            {t("promptViewer.collapse", "Collapse")}
          </button>
        )}
      </div>
    </div>
  );
}

// ==================== Tool Calls ====================

function ToolCallsSection({
  toolCalls,
}: {
  toolCalls: Array<Record<string, unknown>>;
}) {
  const { t } = useTranslation();
  return (
    <div className="rounded-lg border border-amber-500/30 bg-amber-500/5 p-2.5 space-y-1">
      <div className="flex items-center gap-1.5 text-[11px] font-semibold text-foreground/80">
        <Wrench className="h-3.5 w-3.5" />
        {t("promptViewer.toolCalls", "Tool Calls")} ({toolCalls.length})
      </div>
      {toolCalls.map((call, idx) => (
        <pre
          key={idx}
          className="text-[10px] font-mono text-foreground/70 whitespace-pre-wrap break-all"
        >
          {JSON.stringify(call, null, 2)}
        </pre>
      ))}
    </div>
  );
}

// ==================== Helpers ====================

interface PromptMessage {
  role: string;
  content: string;
}

function parsePromptMessages(compiledPrompt: string): PromptMessage[] {
  // Try to parse as JSON array of messages first
  try {
    const parsed = JSON.parse(compiledPrompt);
    if (Array.isArray(parsed)) {
      return parsed.map((m: { role?: string; content?: string }) => ({
        role: m.role ?? "user",
        content: m.content ?? JSON.stringify(m),
      }));
    }
  } catch {
    // Not JSON — treat as plain text
  }

  // Heuristic: split by common role markers
  const segments: PromptMessage[] = [];
  const lines = compiledPrompt.split("\n");
  let currentRole = "system";
  let currentContent: string[] = [];

  for (const line of lines) {
    const lower = line.toLowerCase().trim();
    let newRole: string | null = null;

    if (lower.startsWith("system:") || lower === "[system]") {
      newRole = "system";
    } else if (lower.startsWith("user:") || lower === "[user]") {
      newRole = "user";
    } else if (lower.startsWith("assistant:") || lower === "[assistant]") {
      newRole = "assistant";
    } else if (lower.startsWith("tool:") || lower === "[tool]") {
      newRole = "tool";
    }

    if (newRole) {
      if (currentContent.length > 0) {
        segments.push({ role: currentRole, content: currentContent.join("\n").trim() });
        currentContent = [];
      }
      currentRole = newRole;
      // Include remaining content after the marker on the same line
      const colonIdx = line.indexOf(":");
      if (colonIdx >= 0 && colonIdx < line.length - 1) {
        currentContent.push(line.slice(colonIdx + 1).trim());
      }
    } else {
      currentContent.push(line);
    }
  }

  if (currentContent.length > 0) {
    segments.push({ role: currentRole, content: currentContent.join("\n").trim() });
  }

  // If no role markers found, return the whole thing as system
  if (segments.length === 1 && segments[0]!.role === "system") {
    return [{ role: "system", content: compiledPrompt }];
  }

  return segments;
}

function EmptyState({ message }: { message: string }) {
  return (
    <div className="flex flex-col items-center gap-2 py-6 text-center">
      <MessageSquareCode className="h-8 w-8 text-muted-foreground/30" />
      <p className="text-sm text-muted-foreground">{message}</p>
    </div>
  );
}
