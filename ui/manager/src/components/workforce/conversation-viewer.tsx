import { useState, useMemo, useRef, useEffect, useCallback } from "react";
import { useTranslation } from "react-i18next";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { X, AlertCircle, Sparkles, Download, ChevronDown, ChevronUp } from "lucide-react";
import { cn, hashColor, formatRelativeTime } from "@/lib/utils";
import { useGroupConversation } from "@/hooks/use-groups";
import { AdvisorAvatar } from "@/components/workforce/advisor-avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { parseTranscriptContent, truncateContent } from "@/components/groups/group-utils";
import { DiscussionInsights } from "@/components/groups/discussion-insights";
import {
  entryTypeInfo,
  type TranscriptEntry,
  type GroupConversationState,
} from "@/lib/api/groups";

// ─── Types ───────────────────────────────────────────────────────

interface ConversationViewerProps {
  groupId: string;
  conversationId: string;
  onClose?: () => void;
  className?: string;
}

// ─── State Badge Config ──────────────────────────────────────────

const STATE_VARIANT: Record<
  GroupConversationState,
  { label: string; variant: "success" | "warning" | "destructive" | "secondary" }
> = {
  COMPLETED: { label: "Completed", variant: "success" },
  IN_PROGRESS: { label: "In Progress", variant: "warning" },
  SYNTHESIZING: { label: "Synthesizing", variant: "warning" },
  CREATED: { label: "Created", variant: "secondary" },
  FAILED: { label: "Failed", variant: "destructive" },
  CANCELLED: { label: "Cancelled", variant: "secondary" },
  AWAITING_APPROVAL: { label: "Awaiting Approval", variant: "warning" },
  AWAITING_HUMAN_INPUT: { label: "Awaiting Human Input", variant: "warning" },
  CLOSED: { label: "Closed", variant: "secondary" },
};

function stateI18nKey(state: GroupConversationState): string {
  const map: Record<string, string> = {
    COMPLETED: "Workforce.history.completed",
    IN_PROGRESS: "Workforce.history.inProgress",
    SYNTHESIZING: "Workforce.history.synthesizing",
    CREATED: "Workforce.history.created",
    FAILED: "Workforce.history.failed",
    CANCELLED: "Workforce.history.cancelled",
    AWAITING_APPROVAL: "Workforce.history.awaitingApproval",
    AWAITING_HUMAN_INPUT: "Workforce.history.awaitingHumanInput",
    CLOSED: "Workforce.history.closed",
  };
  return map[state] ?? "Workforce.history.created";
}

// ─── Border Color Mapping ────────────────────────────────────────

/** Map hashColor bg-* class → border-s-* class for the start-border accent. */
const BG_TO_BORDER: Record<string, string> = {
  "bg-blue-500": "border-s-blue-500",
  "bg-emerald-500": "border-s-emerald-500",
  "bg-amber-500": "border-s-amber-500",
  "bg-purple-500": "border-s-purple-500",
  "bg-rose-500": "border-s-rose-500",
  "bg-cyan-500": "border-s-cyan-500",
  "bg-indigo-500": "border-s-indigo-500",
  "bg-orange-500": "border-s-orange-500",
  "bg-teal-500": "border-s-teal-500",
  "bg-pink-500": "border-s-pink-500",
  "bg-lime-500": "border-s-lime-500",
  "bg-violet-500": "border-s-violet-500",
};

function agentBorderClass(agentId: string): string {
  const bg = hashColor(agentId);
  return BG_TO_BORDER[bg] ?? "border-s-muted-foreground";
}

// ─── Phase icons ─────────────────────────────────────────────────

const PHASE_ICONS: Record<string, string> = {
  OPINION: "📋",
  CRITIQUE: "🔍",
  REVISION: "🔄",
  EXECUTE: "⚡",
  VERIFY: "✅",
  SYNTHESIS: "💡",
  CHALLENGE: "⚔️",
  DEFENSE: "🛡️",
  ARGUMENT: "📢",
  REBUTTAL: "↩️",
  PLAN: "📝",
  TASK_RESULT: "📦",
  VERIFICATION: "☑️",
  // Wave-3 entry types (I14/I11/I8/I18) — looked up by TranscriptEntryType.
  VOTE: "🗳️",
  PROPOSAL: "🤝",
  BARGAIN: "🔄",
  RETRO: "🪞",
  BID: "💰",
};

// ─── Constants ───────────────────────────────────────────────────

/** Height in px above which we collapse a message (~6 lines) */
const COLLAPSE_THRESHOLD = 144;

// ─── Hooks ──────────────────────────────────────────────────────

/** Shared collapse state for message cards */
function useCollapsibleContent(dep: unknown) {
  const contentRef = useRef<HTMLDivElement>(null);
  const [isCollapsible, setIsCollapsible] = useState(false);
  const [isExpanded, setIsExpanded] = useState(false);

  useEffect(() => {
    if (contentRef.current) {
      setIsCollapsible(contentRef.current.scrollHeight > COLLAPSE_THRESHOLD);
    }
  }, [dep]);

  return { contentRef, isCollapsible, isExpanded, setIsExpanded };
}

// ─── Sub-components ──────────────────────────────────────────────

function PhaseSeparator({
  phaseName,
  phaseType,
  index,
}: {
  phaseName: string | null;
  phaseType: string;
  index: number;
}) {
  const { t } = useTranslation();
  const icon = PHASE_ICONS[phaseType] ?? "📌";
  // Never undefined — see entryTypeInfo. The `as keyof typeof` cast this
  // replaced satisfied the compiler while the runtime value could still be
  // absent, which is how eleven entry types ended up unlabelled here.
  const typeInfo = entryTypeInfo(phaseType);

  return (
    <div
      className="flex items-center gap-3 my-4"
      style={{
        animation: "br-message-in 250ms ease-out both",
        animationDelay: `${Math.min(index * 40, 400)}ms`,
      }}
    >
      <div className="flex-1 h-px bg-muted" />
      <div
        className={cn(
          "flex items-center gap-1.5 ps-3 pe-3 py-1 rounded-full",
          "text-xs uppercase tracking-wider font-medium",
          "bg-muted text-muted-foreground",
        )}
      >
        <span>{icon}</span>
        <span>
          {phaseName ?? t("Workforce.history.phase", "Phase")}
        </span>
        <Badge variant="secondary" className="text-[9px] px-1.5 py-0">
          {typeInfo.label}
        </Badge>
      </div>
      <div className="flex-1 h-px bg-muted" />
    </div>
  );
}

function QuestionBubble({ content, index }: { content: string | null; index: number }) {
  const { t } = useTranslation();

  return (
    <div
      className="flex justify-end"
      style={{
        animation: "br-message-in 250ms ease-out both",
        animationDelay: `${Math.min(index * 40, 400)}ms`,
      }}
    >
      <div
        className={cn(
          "bg-primary text-primary-foreground rounded-2xl rounded-ee-md ps-4 pe-4 py-3 max-w-lg",
          "shadow-sm",
        )}
      >
        <p className="text-sm whitespace-pre-wrap leading-relaxed">
          {content || t("Workforce.history.noContent", "No content")}
        </p>
      </div>
    </div>
  );
}

function AgentEntryCard({
  entry,
  index,
}: {
  entry: TranscriptEntry;
  index: number;
}) {
  const { t } = useTranslation();
  const typeInfo = entryTypeInfo(entry.type);
  const borderClass = agentBorderClass(entry.speakerAgentId);
  const parsedContent = parseTranscriptContent(entry.content ?? "");
  const hasContent = parsedContent.trim().length > 0;
  const { contentRef, isCollapsible, isExpanded, setIsExpanded } = useCollapsibleContent(parsedContent);

  return (
    <div
      className={cn(
        "rounded-xl border border-s-4 p-4",
        "bg-card border-border",
        borderClass,
      )}
      style={{
        animation: "br-message-in 250ms ease-out both",
        animationDelay: `${Math.min(index * 40, 400)}ms`,
      }}
    >
      {/* Header */}
      <div className="flex items-center gap-2 mb-2">
        <AdvisorAvatar
          name={entry.speakerDisplayName}
          agentId={entry.speakerAgentId}
          size="sm"
        />
        <span className="font-medium text-sm text-foreground">
          {entry.speakerDisplayName}
        </span>
        <Badge variant="secondary" className="text-[10px]">
          {typeInfo.label}
        </Badge>
        {entry.targetAgentId && (
          <span className="text-xs text-muted-foreground">
            → {entry.targetAgentId}
          </span>
        )}
      </div>

      {/* Content */}
      <div className="ps-10">
        {hasContent ? (
          <>
            <div
              ref={contentRef}
              className={cn(
                "relative transition-[max-height] duration-300 ease-in-out overflow-hidden",
                isCollapsible && !isExpanded && "max-h-36",
              )}
            >
              <div className="prose prose-sm dark:prose-invert max-w-none text-foreground/80 [&_pre]:rounded-lg [&_pre]:bg-muted [&_pre]:p-3 [&_code]:rounded [&_code]:bg-muted [&_code]:px-1 [&_code]:py-0.5 [&_code]:text-xs [&_p:first-child]:mt-0 [&_p:last-child]:mb-0">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>
                  {truncateContent(
                    parsedContent,
                    t("groups.contentTruncated", "[Content truncated]"),
                  )}
                </ReactMarkdown>
              </div>
              {isCollapsible && !isExpanded && (
                <div className="absolute bottom-0 inset-x-0 h-10 bg-gradient-to-t from-card to-transparent pointer-events-none" />
              )}
            </div>
            {isCollapsible && (
              <button
                onClick={() => setIsExpanded((v) => !v)}
                className="flex items-center gap-1 mt-1 text-xs font-medium text-primary hover:text-primary/80 transition-colors"
              >
                {isExpanded ? (
                  <>
                    <ChevronUp className="h-3 w-3" />
                    {t("common.showLess", "Show less")}
                  </>
                ) : (
                  <>
                    <ChevronDown className="h-3 w-3" />
                    {t("common.showMore", "Show more")}
                  </>
                )}
              </button>
            )}
          </>
        ) : (
          <p className="text-sm text-muted-foreground italic">
            {t("Workforce.history.noContent", "No content")}
          </p>
        )}
      </div>

      {/* Timestamp */}
      {entry.timestamp && (
        <div className="ps-10 mt-2">
          <span className="text-[10px] text-muted-foreground">
            {new Date(entry.timestamp).toLocaleTimeString()}
          </span>
        </div>
      )}
    </div>
  );
}

function SynthesisEntryCard({
  entry,
  index,
}: {
  entry: TranscriptEntry;
  index: number;
}) {
  const { t } = useTranslation();
  const parsedContent = parseTranscriptContent(entry.content ?? "");
  const hasContent = parsedContent.trim().length > 0;
  const { contentRef, isCollapsible, isExpanded, setIsExpanded } = useCollapsibleContent(parsedContent);

  return (
    <div
      className={cn(
        "rounded-xl border border-s-4 p-4",
        "bg-amber-50 border-amber-300 border-s-amber-500",
        "dark:bg-amber-500/10 dark:border-amber-700 dark:border-s-amber-500",
      )}
      style={{
        animation: "br-message-in 250ms ease-out both",
        animationDelay: `${Math.min(index * 40, 400)}ms`,
      }}
    >
      <div className="flex items-center gap-2 mb-2">
        <Sparkles className="h-4 w-4 text-amber-600 dark:text-amber-400" />
        <span className="text-sm font-semibold text-amber-700 dark:text-amber-300">
          {t("Workforce.history.synthesis", "Synthesis")}
        </span>
        {entry.speakerDisplayName && (
          <span className="text-xs text-amber-600/70 dark:text-amber-400/70">
            — {entry.speakerDisplayName}
          </span>
        )}
      </div>
      <div
        ref={contentRef}
        className={cn(
          "relative transition-[max-height] duration-300 ease-in-out overflow-hidden",
          isCollapsible && !isExpanded && "max-h-36",
        )}
      >
        {hasContent ? (
          <div className="prose prose-sm dark:prose-invert max-w-none text-foreground/80 ps-6 [&_pre]:rounded-lg [&_pre]:bg-muted [&_pre]:p-3 [&_code]:rounded [&_code]:bg-muted [&_code]:px-1 [&_code]:py-0.5 [&_code]:text-xs [&_p:first-child]:mt-0 [&_p:last-child]:mb-0">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>
              {truncateContent(
                parsedContent,
                t("groups.contentTruncated", "[Content truncated]"),
              )}
            </ReactMarkdown>
          </div>
        ) : (
          <p className="text-sm text-muted-foreground italic ps-6">
            {t("Workforce.history.noContent", "No content")}
          </p>
        )}
        {isCollapsible && !isExpanded && (
          <div className="absolute bottom-0 inset-x-0 h-10 bg-gradient-to-t from-amber-50 dark:from-amber-500/10 to-transparent pointer-events-none" />
        )}
      </div>
      {isCollapsible && (
        <button
          onClick={() => setIsExpanded((v) => !v)}
          className="flex items-center gap-1 mt-1 ps-6 text-xs font-medium text-primary hover:text-primary/80 transition-colors"
        >
          {isExpanded ? (
            <>
              <ChevronUp className="h-3 w-3" />
              {t("common.showLess", "Show less")}
            </>
          ) : (
            <>
              <ChevronDown className="h-3 w-3" />
              {t("common.showMore", "Show more")}
            </>
          )}
        </button>
      )}
    </div>
  );
}

function ErrorEntryCard({
  entry,
  index,
}: {
  entry: TranscriptEntry;
  index: number;
}) {
  const { t } = useTranslation();

  return (
    <div
      className={cn(
        "rounded-xl border border-s-4 p-3",
        "bg-red-50 border-red-200 border-s-red-500",
        "dark:bg-red-500/10 dark:border-red-800 dark:border-s-red-500",
      )}
      style={{
        animation: "br-message-in 250ms ease-out both",
        animationDelay: `${Math.min(index * 40, 400)}ms`,
      }}
    >
      <div className="flex items-center gap-2">
        <AlertCircle className="h-4 w-4 text-red-500" />
        <span className="font-medium text-sm text-red-700 dark:text-red-300">
          {entry.speakerDisplayName}
        </span>
        <Badge variant="destructive" className="text-[10px]">
          {t("Workforce.history.error", "Error")}
        </Badge>
      </div>
      {entry.errorReason && (
        <p className="text-xs text-red-600 dark:text-red-400 mt-1 ps-6">
          {entry.errorReason}
        </p>
      )}
      {entry.content && (
        <p className="text-sm text-red-700 dark:text-red-300 whitespace-pre-wrap mt-2 ps-6">
          {entry.content}
        </p>
      )}
    </div>
  );
}

function SkippedEntryCard({
  entry,
  index,
}: {
  entry: TranscriptEntry;
  index: number;
}) {
  const { t } = useTranslation();

  return (
    <div
      className={cn(
        "rounded-xl border p-3 opacity-60",
        "bg-muted/50 border-border",
      )}
      style={{
        animation: "br-message-in 250ms ease-out both",
        animationDelay: `${Math.min(index * 40, 400)}ms`,
      }}
    >
      <p className="text-xs text-muted-foreground">
        {t("Workforce.history.skipped", "{{name}} — Skipped", {
          name: entry.speakerDisplayName,
        })}
        {entry.errorReason && <span className="ms-1">({entry.errorReason})</span>}
      </p>
    </div>
  );
}

function SynthesizedAnswerFooter({ content }: { content: string }) {
  const { t } = useTranslation();
  const parsedContent = parseTranscriptContent(content);
  const { contentRef, isCollapsible, isExpanded, setIsExpanded } = useCollapsibleContent(parsedContent);

  return (
    <div
      className={cn(
        "rounded-xl border-2 border-amber-400/50 p-5 mt-4",
        "bg-gradient-to-b from-amber-50 to-amber-100/50",
        "dark:from-amber-500/10 dark:to-amber-500/5 dark:border-amber-500/30",
      )}
    >
      <div className="flex items-center gap-2 mb-3">
        <Sparkles className="h-5 w-5 text-amber-600 dark:text-amber-400" />
        <h3 className="text-sm font-bold text-amber-800 dark:text-amber-200">
          {t("Workforce.history.finalAnswer", "Final Synthesized Answer")}
        </h3>
      </div>
      <div
        ref={contentRef}
        className={cn(
          "relative transition-[max-height] duration-300 ease-in-out overflow-hidden",
          isCollapsible && !isExpanded && "max-h-36",
        )}
      >
        <div className="prose prose-sm dark:prose-invert max-w-none text-foreground/80 [&_pre]:rounded-lg [&_pre]:bg-muted [&_pre]:p-3 [&_code]:rounded [&_code]:bg-muted [&_code]:px-1 [&_code]:py-0.5 [&_code]:text-xs [&_p:first-child]:mt-0 [&_p:last-child]:mb-0">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>
            {truncateContent(parsedContent, t("groups.contentTruncated", "[Content truncated]"))}
          </ReactMarkdown>
        </div>
        {isCollapsible && !isExpanded && (
          <div className="absolute bottom-0 inset-x-0 h-10 bg-gradient-to-t from-amber-100/50 dark:from-amber-500/5 to-transparent pointer-events-none" />
        )}
      </div>
      {isCollapsible && (
        <button
          onClick={() => setIsExpanded((v) => !v)}
          className="flex items-center gap-1 mt-1 text-xs font-medium text-primary hover:text-primary/80 transition-colors"
        >
          {isExpanded ? (
            <>
              <ChevronUp className="h-3 w-3" />
              {t("common.showLess", "Show less")}
            </>
          ) : (
            <>
              <ChevronDown className="h-3 w-3" />
              {t("common.showMore", "Show more")}
            </>
          )}
        </button>
      )}
    </div>
  );
}

// ─── Loading Skeleton ────────────────────────────────────────────

function ViewerSkeleton() {
  return (
    <div className="flex flex-col gap-4 p-6">
      <Skeleton className="h-6 w-3/4" />
      <Skeleton className="h-4 w-1/4" />
      <div className="h-px bg-muted my-2" />
      {Array.from({ length: 4 }).map((_, i) => (
        <div key={i} className="space-y-2">
          <div className="flex items-center gap-2">
            <Skeleton className="h-8 w-8 rounded-full" />
            <Skeleton className="h-4 w-24" />
          </div>
          <Skeleton className="h-16 w-full rounded-xl" />
        </div>
      ))}
    </div>
  );
}

// ─── Main Component ──────────────────────────────────────────────

function ConversationViewer({
  groupId,
  conversationId,
  onClose,
  className,
}: ConversationViewerProps) {
  const { t } = useTranslation();
  const { data: conversation, isLoading, isError } = useGroupConversation(
    groupId,
    conversationId,
  );
  const scrollRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to top when conversation changes
  useEffect(() => {
    scrollRef.current?.scrollTo({ top: 0, behavior: "smooth" });
  }, [conversationId]);

  // ── Export conversation as Markdown ────────────────────────
  const handleExport = useCallback(() => {
    if (!conversation) return;

    const lines: string[] = [];
    lines.push("# Task Force Discussion");
    lines.push("");
    lines.push(`**Question:** ${conversation.originalQuestion || "—"}`);
    lines.push(`**Date:** ${conversation.created ? new Date(conversation.created).toLocaleString() : "—"}`);
    lines.push(`**Status:** ${conversation.state}`);
    lines.push("");
    lines.push("---");
    lines.push("");

    let lastPhaseIndex = -1;
    for (const entry of conversation.transcript) {
      // Phase separator
      if (
        entry.phaseIndex >= 0 &&
        entry.phaseIndex !== lastPhaseIndex &&
        entry.type !== "QUESTION"
      ) {
        lastPhaseIndex = entry.phaseIndex;
        lines.push(`## Phase ${entry.phaseIndex + 1}: ${entry.phaseName ?? entry.type}`);
        lines.push("");
      }

      if (entry.type === "QUESTION") {
        lines.push(`> **Question:** ${entry.content ?? ""}`);
        lines.push("");
      } else if (entry.type === "SYNTHESIS") {
        lines.push("## Synthesis");
        lines.push("");
        lines.push(entry.content ?? "");
        lines.push("");
      } else if (entry.type === "ERROR") {
        lines.push(`### ⚠️ ${entry.speakerDisplayName} (Error)`);
        if (entry.errorReason) lines.push(`> ${entry.errorReason}`);
        if (entry.content) lines.push(entry.content);
        lines.push("");
      } else if (entry.type !== "SKIPPED") {
        lines.push(`### ${entry.speakerDisplayName} (${entry.type})`);
        lines.push("");
        lines.push(entry.content ?? "");
        lines.push("");
      }
    }

    // Final synthesized answer (if present and not already in transcript)
    if (
      conversation.synthesizedAnswer?.trim() &&
      !conversation.transcript.some((e) => e.type === "SYNTHESIS")
    ) {
      lines.push("---");
      lines.push("");
      lines.push("## Final Synthesized Answer");
      lines.push("");
      lines.push(conversation.synthesizedAnswer);
      lines.push("");
    }

    const markdown = lines.join("\n");
    const blob = new Blob([markdown], { type: "text/markdown" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `task-force-discussion-${conversationId}.md`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }, [conversation, conversationId]);

  // Process transcript to insert phase separators
  const processedEntries = useMemo(() => {
    if (!conversation?.transcript) return [];
    let lastPhase = -1;
    return conversation.transcript.map((entry) => {
      let showPhaseHeader = false;
      if (
        entry.phaseIndex >= 0 &&
        entry.phaseIndex !== lastPhase &&
        entry.type !== "QUESTION"
      ) {
        lastPhase = entry.phaseIndex;
        showPhaseHeader = true;
      }
      return { entry, showPhaseHeader };
    });
  }, [conversation?.transcript]);

  if (isLoading) {
    return (
      <div className={cn("flex flex-col h-full", className)}>
        <ViewerSkeleton />
      </div>
    );
  }

  if (isError) {
    return (
      <div className={cn("flex items-center justify-center h-full", className)}>
        <div className="text-center space-y-2">
          <AlertCircle className="h-8 w-8 text-red-400" />
          <p className="text-sm text-red-500 dark:text-red-400">
            {t("Workforce.history.loadError", "Failed to load conversation")}
          </p>
        </div>
      </div>
    );
  }

  if (!conversation) {
    return (
      <div className={cn("flex items-center justify-center h-full", className)}>
        <p className="text-sm text-muted-foreground">
          {t("Workforce.history.notFound", "Conversation not found")}
        </p>
      </div>
    );
  }

  const stateConfig = STATE_VARIANT[conversation.state] ?? STATE_VARIANT.CREATED;
  const timestamp = conversation.lastModified
    ? new Date(conversation.lastModified).getTime()
    : conversation.created
      ? new Date(conversation.created).getTime()
      : 0;
  const hasSynthesisEntry = conversation.transcript.some(
    (e) => e.type === "SYNTHESIS",
  );

  return (
    <div className={cn("flex flex-col h-full", className)}>
      {/* ── Header ─────────────────────────────────────────────── */}
      <div
        className={cn(
          "flex items-start gap-3 ps-5 pe-5 py-4",
          "border-b border-border",
          "bg-card/50 backdrop-blur-sm",
        )}
      >
        <div className="flex-1 min-w-0">
          <h2 className="text-base font-semibold text-foreground line-clamp-2">
            {conversation.originalQuestion ||
              t("Workforce.history.untitled", "Untitled Conversation")}
          </h2>
          <div className="flex items-center gap-2 mt-1.5 flex-wrap">
            <Badge variant={stateConfig.variant} className="text-[10px]">
              {t(stateI18nKey(conversation.state), stateConfig.label)}
            </Badge>
            {timestamp > 0 && (
              <span className="text-xs text-muted-foreground">
                {formatRelativeTime(timestamp)}
              </span>
            )}
            <span className="text-xs text-slate-400 dark:text-slate-500">
              ·{" "}
              {t("Workforce.history.entryCount", "{{count}} entries", {
                count: conversation.transcript.length,
              })}
            </span>
          </div>
        </div>
        <div className="flex items-center gap-1 shrink-0">
          {/* Export as Markdown */}
          <Button
            variant="ghost"
            size="icon"
            className="h-8 w-8"
            onClick={handleExport}
            aria-label={t("Workforce.history.export", "Export")}
          >
            <Download className="h-4 w-4" />
          </Button>
          {onClose && (
            <Button
              variant="ghost"
              size="icon"
              className="h-8 w-8"
              onClick={onClose}
              aria-label={t("Workforce.history.close", "Close")}
            >
              <X className="h-4 w-4" />
            </Button>
          )}
        </div>
      </div>

      {/* ── Transcript Body ────────────────────────────────────── */}
      <div
        ref={scrollRef}
        role="log"
        aria-label={t("Workforce.history.transcript", "Conversation transcript")}
        className="flex-1 overflow-y-auto ps-5 pe-5 py-4 space-y-3"
      >
        {/* Shared artifacts, negotiation ledger and windowing summary
            (I17/I11/I9) — the same component the Manager transcript and the
            live board render, so every surface showing a group discussion
            shows the same state. This viewer reads the single-conversation
            GET, which is the only response that carries `artifacts`. */}
        <DiscussionInsights conversation={conversation} />

        {processedEntries.map(({ entry, showPhaseHeader }, idx) => {
          const phaseHeader = showPhaseHeader ? (
            <PhaseSeparator
              key={`phase-${entry.phaseIndex}`}
              phaseName={entry.phaseName}
              phaseType={entry.type}
              index={idx}
            />
          ) : null;

          switch (entry.type) {
            case "QUESTION":
              return (
                <QuestionBubble
                  key={`q-${idx}`}
                  content={entry.content}
                  index={idx}
                />
              );

            case "SYNTHESIS":
              return (
                <div key={`syn-${idx}`}>
                  {phaseHeader}
                  <SynthesisEntryCard entry={entry} index={idx} />
                </div>
              );

            case "ERROR":
              return (
                <div key={`err-${idx}`}>
                  {phaseHeader}
                  <ErrorEntryCard entry={entry} index={idx} />
                </div>
              );

            case "SKIPPED":
              return (
                <div key={`skip-${idx}`}>
                  {phaseHeader}
                  <SkippedEntryCard entry={entry} index={idx} />
                </div>
              );

            default:
              return (
                <div key={`r-${idx}`}>
                  {phaseHeader}
                  <AgentEntryCard entry={entry} index={idx} />
                </div>
              );
          }
        })}

        {/* ── Footer: Synthesized Answer ──────────────────────── */}
        {conversation.synthesizedAnswer &&
          parseTranscriptContent(conversation.synthesizedAnswer).trim() &&
          !hasSynthesisEntry && (
          <SynthesizedAnswerFooter content={conversation.synthesizedAnswer} />
        )}

        {/* Empty transcript */}
        {conversation.transcript.length === 0 && (
          <div className="flex items-center justify-center py-12">
            <p className="text-sm text-muted-foreground">
              {t(
                "Workforce.history.emptyTranscript",
                "No transcript entries yet",
              )}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}

export { ConversationViewer };
export type { ConversationViewerProps };
