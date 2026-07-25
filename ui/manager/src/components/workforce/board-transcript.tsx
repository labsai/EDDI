import { useRef, useEffect, useMemo, useCallback, useState } from "react";
import { useTranslation } from "react-i18next";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { ChevronDown, ChevronUp, Copy, CheckCircle2 } from "lucide-react";
import { cn, getInitials } from "@/lib/utils";
import { AdvisorResponseCard } from "@/components/workforce/advisor-response-card";
import type { TranscriptEntry, TranscriptEntryType } from "@/lib/api/groups";
import { ENTRY_TYPE_INFO } from "@/lib/api/groups";
import { formatMarkdownText } from "@/components/groups/group-utils";

// ─── Types ───────────────────────────────────────────────────────

interface BoardTranscriptProps {
  transcript: TranscriptEntry[];
  boardId: string;
  synthesizedAnswer?: string | null;

  className?: string;
}

// ─── Phase Icon Map ──────────────────────────────────────────────

const PHASE_ICONS: Record<string, string> = {
  OPINION: "📋",
  CRITIQUE: "🔍",
  REVISION: "🔄",
  EXECUTE: "⚡",
  VERIFY: "✅",
  SYNTHESIS: "💡",
  CHALLENGE: "⚔️",
  DEFENSE: "🛡️",
  ARGUE: "📢",
  REBUTTAL: "↩️",
  PLAN: "📝",
};

// ─── Entry-type badge variants (borrowed from manager) ──────────

function badgeVariant(
  type: TranscriptEntryType,
): "default" | "secondary" | "success" | "warning" | "destructive" | "outline" {
  switch (type) {
    case "SYNTHESIS":
    case "PLAN":
      return "default";
    case "ERROR":
      return "destructive";
    case "SKIPPED":
      return "secondary";
    case "CRITIQUE":
    case "CHALLENGE":
    case "VERIFICATION":
      return "warning";
    case "OPINION":
    case "REVISION":
    case "DEFENSE":
    case "TASK_RESULT":
      return "success";
    default:
      return "outline";
  }
}

// ─── Helpers ─────────────────────────────────────────────────────

function getPhaseIcon(phaseType?: string | null): string {
  if (!phaseType) return "📌";
  return PHASE_ICONS[phaseType] ?? "📌";
}

function inferPhaseType(entry: TranscriptEntry): string | undefined {
  if (entry.type && entry.type in PHASE_ICONS) return entry.type;
  if (entry.phaseName) {
    const upper = entry.phaseName.toUpperCase();
    if (upper in PHASE_ICONS) return upper;
  }
  return undefined;
}

function formatTime(ts?: string | null): string {
  if (!ts) return "";
  try {
    return new Date(ts).toLocaleTimeString(undefined, {
      hour: "2-digit",
      minute: "2-digit",
    });
  } catch {
    return "";
  }
}

// ─── Sub-components ──────────────────────────────────────────────

/** User question bubble (right-aligned, chat-style) */
function QuestionBubble({ content, delay }: { content: string | null; delay: number }) {
  return (
    <div
      className="flex justify-end"
      style={{ animation: "br-message-in 250ms ease-out both", animationDelay: `${delay}ms` }}
    >
      <div className="flex gap-3 flex-row-reverse max-w-[85%]">
        {/* User avatar */}
        <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground text-xs font-semibold">
          You
        </div>
        <div className="flex flex-col items-end gap-1">
          <div className="bg-foreground/10 text-foreground rounded-2xl rounded-ee-md px-4 py-2.5">
            <p className="text-sm whitespace-pre-wrap leading-relaxed">{content ?? ""}</p>
          </div>
        </div>
      </div>
    </div>
  );
}

/** Phase header pill */
function PhaseHeader({
  phaseName,
  phaseType,
  delay,
}: {
  phaseName: string | null;
  phaseType?: string;
  delay: number;
}) {
  const { t } = useTranslation();
  const icon = getPhaseIcon(phaseType);

  return (
    <div
      role="separator"
      aria-label={phaseName ?? phaseType ?? t("Workforce.board.phase", "Phase")}
      className="flex justify-center my-4"
      style={{ animation: "br-message-in 250ms ease-out both", animationDelay: `${delay}ms` }}
    >
      <div
        className={cn(
          "flex items-center gap-1.5 ps-3 pe-3 py-1.5 rounded-full",
          "text-[11px] uppercase tracking-wider font-medium",
          "bg-muted/50 text-muted-foreground border border-border/30",
        )}
      >
        <span>{icon}</span>
        <span>{phaseName ?? phaseType ?? t("Workforce.board.phase", "Phase")}</span>
      </div>
    </div>
  );
}

/** Synthesis card — rich, with markdown, copy, and collapsible */
function SynthesisCard({ content, delay }: { content: string; delay: number }) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [collapsible, setCollapsible] = useState(false);
  const synthRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (synthRef.current) {
      setCollapsible(synthRef.current.scrollHeight > 300);
    }
  }, [content]);

  const handleCopy = () => {
    navigator.clipboard.writeText(content);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div
      role="status"
      aria-label={t("Workforce.board.synthesisResult", "Synthesis result")}
      className={cn(
        "rounded-xl border border-border p-4",
        "bg-card",
      )}
      style={{ animation: "br-message-in 250ms ease-out both", animationDelay: `${delay}ms` }}
    >
      {/* Header row */}
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <span className="text-lg">⭐</span>
          <span className="text-sm font-semibold text-foreground">
            {t("Workforce.board.synthesis", "Synthesis")}
          </span>
        </div>
        <button
          onClick={handleCopy}
          className="flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted-foreground hover:bg-primary/10 hover:text-primary transition-colors"
          title={t("common.copy", "Copy")}
        >
          {copied ? (
            <>
              <CheckCircle2 className="h-3 w-3" /> {t("common.copied", "Copied")}
            </>
          ) : (
            <>
              <Copy className="h-3 w-3" /> {t("common.copy", "Copy")}
            </>
          )}
        </button>
      </div>

      {/* Collapsible markdown body */}
      <div
        ref={synthRef}
        className={cn(
          "relative transition-[max-height] duration-300 ease-in-out overflow-hidden",
          collapsible && !expanded && "max-h-72",
        )}
      >
        <div className="prose prose-sm dark:prose-invert max-w-none text-foreground [&_pre]:rounded-lg [&_pre]:bg-muted [&_pre]:p-3 [&_code]:rounded [&_code]:bg-muted [&_code]:px-1 [&_code]:py-0.5 [&_code]:text-xs [&_p]:my-1 [&_ul]:my-1 [&_ol]:my-1">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{formatMarkdownText(content)}</ReactMarkdown>
        </div>
        {collapsible && !expanded && (
          <div className="absolute bottom-0 inset-x-0 h-12 bg-gradient-to-t from-card to-transparent pointer-events-none" />
        )}
      </div>
      {collapsible && (
        <button
          onClick={() => setExpanded((v) => !v)}
          className="flex items-center gap-1 mt-2 text-xs font-medium text-muted-foreground hover:text-foreground transition-colors"
        >
          {expanded ? (
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

/** Skipped entry */
function SkippedCard({
  displayName,
  reason,
  delay,
}: {
  displayName: string;
  reason?: string | null;
  delay: number;
}) {
  const { t } = useTranslation();

  return (
    <div
      className={cn(
        "flex items-center gap-3 rounded-xl border p-3 opacity-50",
        "bg-muted/20 border-border/30",
      )}
      style={{ animation: "br-message-in 250ms ease-out both", animationDelay: `${delay}ms` }}
    >
      <div
        className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[10px] font-bold bg-muted text-muted-foreground opacity-60"
      >
        {getInitials(displayName)}
      </div>
      <p className="text-xs text-muted-foreground">
        {t("Workforce.board.skipped", "{{name}} — Skipped", { name: displayName })}
        {reason && <span className="ms-1 italic">({reason})</span>}
      </p>
    </div>
  );
}

/** Enhanced response card wrapper — adds entry-type badge and timestamp */
function EnhancedResponseEntry({
  entry,
  boardId,
  delay,
}: {
  entry: TranscriptEntry;
  boardId: string;
  delay: number;
}) {
  const { t } = useTranslation();
  const info = ENTRY_TYPE_INFO[entry.type];
  const variant = badgeVariant(entry.type);
  const time = formatTime(entry.timestamp);

  return (
    <div
      style={{ animationDelay: `${delay}ms` }}
    >
      <AdvisorResponseCard
        displayName={entry.speakerDisplayName}
        agentId={entry.speakerAgentId}
        role={
          info
            ? t(`groups.entryType.${entry.type}`, info.label)
            : null
        }
        roleBadgeVariant={variant}
        content={entry.content}
        boardId={boardId}
        timestamp={time}
      />
    </div>
  );
}

// ─── Main Component ──────────────────────────────────────────────

function BoardTranscript({
  transcript,
  boardId,
  synthesizedAnswer,
  className,
}: BoardTranscriptProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const isNearBottomRef = useRef(true);

  const handleScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const threshold = 100;
    isNearBottomRef.current =
      el.scrollHeight - el.scrollTop - el.clientHeight < threshold;
  }, []);

  // Auto-scroll to bottom on new entries
  useEffect(() => {
    const el = scrollRef.current;
    if (el && isNearBottomRef.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [transcript.length, synthesizedAnswer]);

  // Check if transcript already contains a SYNTHESIS entry
  const hasSynthesisEntry = transcript.some((e) => e.type === "SYNTHESIS");

  const processedEntries = useMemo(() => {
    let lastPhase = -1;
    return transcript.map((entry) => {
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
  }, [transcript]);

  return (
    <div ref={scrollRef} onScroll={handleScroll} aria-live="polite" aria-relevant="additions" className={cn("flex flex-col gap-2 overflow-y-auto", className)}>
      {processedEntries.map(({ entry, showPhaseHeader }, idx) => {
        const delay = Math.min(idx * 60, 600);

        const phaseHeader = showPhaseHeader ? (
          <PhaseHeader
            key={`phase-${entry.phaseIndex}`}
            phaseName={entry.phaseName}
            phaseType={inferPhaseType(entry)}
            delay={delay}
          />
        ) : null;

        switch (entry.type) {
          case "QUESTION":
            return (
              <QuestionBubble key={`q-${idx}`} content={entry.content} delay={delay} />
            );

          case "SKIPPED":
            return (
              <div key={`s-${idx}`}>
                {phaseHeader}
                <SkippedCard
                  displayName={entry.speakerDisplayName}
                  reason={entry.errorReason}
                  delay={delay}
                />
              </div>
            );

          case "SYNTHESIS":
            return (
              <div key={`syn-${idx}`}>
                {phaseHeader}
                <SynthesisCard content={entry.content ?? ""} delay={delay} />
              </div>
            );

          default:
            return (
              <div key={`r-${idx}`}>
                {phaseHeader}
                <EnhancedResponseEntry
                  entry={entry}
                  boardId={boardId}
                  delay={delay}
                />
              </div>
            );
        }
      })}

      {/* Trailing synthesis from synthesizedAnswer prop */}
      {synthesizedAnswer && !hasSynthesisEntry && (
        <SynthesisCard content={synthesizedAnswer} delay={Math.min(transcript.length * 60, 600)} />
      )}
    </div>
  );
}

export { BoardTranscript };
export type { BoardTranscriptProps };
