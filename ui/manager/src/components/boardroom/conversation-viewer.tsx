import { useMemo, useRef, useEffect, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { X, AlertCircle, Sparkles, Download } from "lucide-react";
import { cn, hashColor, formatRelativeTime } from "@/lib/utils";
import { useGroupConversation } from "@/hooks/use-groups";
import { AdvisorAvatar } from "@/components/boardroom/advisor-avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  ENTRY_TYPE_INFO,
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
};

function stateI18nKey(state: GroupConversationState): string {
  const map: Record<string, string> = {
    COMPLETED: "boardroom.history.completed",
    IN_PROGRESS: "boardroom.history.inProgress",
    SYNTHESIZING: "boardroom.history.synthesizing",
    CREATED: "boardroom.history.created",
    FAILED: "boardroom.history.failed",
    CANCELLED: "boardroom.history.cancelled",
    AWAITING_APPROVAL: "boardroom.history.awaitingApproval",
  };
  return map[state] ?? "boardroom.history.created";
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
  return BG_TO_BORDER[bg] ?? "border-s-slate-400";
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
};

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
  const typeInfo = ENTRY_TYPE_INFO[phaseType as keyof typeof ENTRY_TYPE_INFO];

  return (
    <div
      className="flex items-center gap-3 my-4"
      style={{
        animation: "br-message-in 250ms ease-out both",
        animationDelay: `${Math.min(index * 40, 400)}ms`,
      }}
    >
      <div className="flex-1 h-px bg-slate-200 dark:bg-slate-700" />
      <div
        className={cn(
          "flex items-center gap-1.5 ps-3 pe-3 py-1 rounded-full",
          "text-xs uppercase tracking-wider font-medium",
          "bg-slate-100 text-slate-600",
          "dark:bg-slate-800 dark:text-slate-400",
        )}
      >
        <span>{icon}</span>
        <span>
          {phaseName ?? t("boardroom.history.phase", "Phase")}
        </span>
        {typeInfo && (
          <Badge variant="secondary" className="text-[9px] px-1.5 py-0">
            {typeInfo.label}
          </Badge>
        )}
      </div>
      <div className="flex-1 h-px bg-slate-200 dark:bg-slate-700" />
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
          "bg-indigo-500 text-white rounded-2xl rounded-ee-md ps-4 pe-4 py-3 max-w-lg",
          "shadow-sm",
        )}
      >
        <p className="text-sm whitespace-pre-wrap leading-relaxed">
          {content || t("boardroom.history.noContent", "No content")}
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
  const typeInfo = ENTRY_TYPE_INFO[entry.type as keyof typeof ENTRY_TYPE_INFO];
  const borderClass = agentBorderClass(entry.speakerAgentId);

  return (
    <div
      className={cn(
        "rounded-xl border border-s-4 p-4",
        "bg-white border-slate-200",
        "dark:bg-slate-900/50 dark:border-slate-800",
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
        <span className="font-medium text-sm text-slate-900 dark:text-slate-100">
          {entry.speakerDisplayName}
        </span>
        {typeInfo && (
          <Badge variant="secondary" className="text-[10px]">
            {typeInfo.label}
          </Badge>
        )}
        {entry.targetAgentId && (
          <span className="text-xs text-slate-400 dark:text-slate-500">
            → {entry.targetAgentId}
          </span>
        )}
      </div>

      {/* Content */}
      <div className="ps-10">
        {entry.content ? (
          <p className="text-sm text-slate-700 dark:text-slate-300 whitespace-pre-wrap leading-relaxed">
            {entry.content}
          </p>
        ) : (
          <p className="text-sm text-slate-400 italic">
            {t("boardroom.history.noContent", "No content")}
          </p>
        )}
      </div>

      {/* Timestamp */}
      {entry.timestamp && (
        <div className="ps-10 mt-2">
          <span className="text-[10px] text-slate-400 dark:text-slate-500">
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
          {t("boardroom.history.synthesis", "Synthesis")}
        </span>
        {entry.speakerDisplayName && (
          <span className="text-xs text-amber-600/70 dark:text-amber-400/70">
            — {entry.speakerDisplayName}
          </span>
        )}
      </div>
      <p className="text-sm text-slate-700 dark:text-slate-300 whitespace-pre-wrap leading-relaxed ps-6">
        {entry.content ?? ""}
      </p>
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
          {t("boardroom.history.error", "Error")}
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
        "bg-slate-50 border-slate-200",
        "dark:bg-slate-900/30 dark:border-slate-800",
      )}
      style={{
        animation: "br-message-in 250ms ease-out both",
        animationDelay: `${Math.min(index * 40, 400)}ms`,
      }}
    >
      <p className="text-xs text-slate-400">
        {t("boardroom.history.skipped", "{{name}} — Skipped", {
          name: entry.speakerDisplayName,
        })}
        {entry.errorReason && <span className="ms-1">({entry.errorReason})</span>}
      </p>
    </div>
  );
}

function SynthesizedAnswerFooter({ content }: { content: string }) {
  const { t } = useTranslation();

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
          {t("boardroom.history.finalAnswer", "Final Synthesized Answer")}
        </h3>
      </div>
      <p className="text-sm text-slate-700 dark:text-slate-300 whitespace-pre-wrap leading-relaxed">
        {content}
      </p>
    </div>
  );
}

// ─── Loading Skeleton ────────────────────────────────────────────

function ViewerSkeleton() {
  return (
    <div className="flex flex-col gap-4 p-6">
      <Skeleton className="h-6 w-3/4" />
      <Skeleton className="h-4 w-1/4" />
      <div className="h-px bg-slate-200 dark:bg-slate-700 my-2" />
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
    lines.push("# Boardroom Discussion");
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
    a.download = `boardroom-discussion-${conversationId}.md`;
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
            {t("boardroom.history.loadError", "Failed to load conversation")}
          </p>
        </div>
      </div>
    );
  }

  if (!conversation) {
    return (
      <div className={cn("flex items-center justify-center h-full", className)}>
        <p className="text-sm text-slate-400 dark:text-slate-500">
          {t("boardroom.history.notFound", "Conversation not found")}
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
          "border-b border-slate-200 dark:border-slate-800",
          "bg-white/50 dark:bg-slate-900/50 backdrop-blur-sm",
        )}
      >
        <div className="flex-1 min-w-0">
          <h2 className="text-base font-semibold text-slate-900 dark:text-slate-100 line-clamp-2">
            {conversation.originalQuestion ||
              t("boardroom.history.untitled", "Untitled Conversation")}
          </h2>
          <div className="flex items-center gap-2 mt-1.5 flex-wrap">
            <Badge variant={stateConfig.variant} className="text-[10px]">
              {t(stateI18nKey(conversation.state), stateConfig.label)}
            </Badge>
            {timestamp > 0 && (
              <span className="text-xs text-slate-400 dark:text-slate-500">
                {formatRelativeTime(timestamp)}
              </span>
            )}
            <span className="text-xs text-slate-400 dark:text-slate-500">
              ·{" "}
              {t("boardroom.history.entryCount", "{{count}} entries", {
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
            aria-label={t("boardroom.history.export", "Export")}
          >
            <Download className="h-4 w-4" />
          </Button>
          {onClose && (
            <Button
              variant="ghost"
              size="icon"
              className="h-8 w-8"
              onClick={onClose}
              aria-label={t("boardroom.history.close", "Close")}
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
        aria-label={t("boardroom.history.transcript", "Conversation transcript")}
        className="flex-1 overflow-y-auto ps-5 pe-5 py-4 space-y-3"
      >
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
        {conversation.synthesizedAnswer?.trim() && !hasSynthesisEntry && (
          <SynthesizedAnswerFooter content={conversation.synthesizedAnswer} />
        )}

        {/* Empty transcript */}
        {conversation.transcript.length === 0 && (
          <div className="flex items-center justify-center py-12">
            <p className="text-sm text-slate-400 dark:text-slate-500">
              {t(
                "boardroom.history.emptyTranscript",
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
