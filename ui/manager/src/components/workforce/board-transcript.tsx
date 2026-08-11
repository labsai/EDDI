import { useRef, useEffect, useLayoutEffect, useMemo, useCallback, useState } from "react";
import { useTranslation } from "react-i18next";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { ChevronDown, ChevronUp, Copy, CheckCircle2, GitMerge } from "lucide-react";
import { cn, getInitials } from "@/lib/utils";
import { AdvisorResponseCard } from "@/components/workforce/advisor-response-card";
import { DecisionRecordCard } from "@/components/groups/decision-record-card";
import { hasDisplayableDecision } from "@/lib/group-config";
import type { DecisionRecord, TranscriptEntry, TranscriptEntryType } from "@/lib/api/groups";
import { entryTypeInfo } from "@/lib/api/groups";
import type { ConvergenceProgress } from "@/hooks/use-group-discussion-stream";
import { formatMarkdownText } from "@/components/groups/group-utils";

// ─── Types ───────────────────────────────────────────────────────

interface BoardTranscriptProps {
  transcript: TranscriptEntry[];
  boardId: string;
  synthesizedAnswer?: string | null;
  /** The discussion is still running — keeps a visible "working" row pinned to
   *  the bottom so the transcript never looks finished while it isn't. */
  isLive?: boolean;
  /**
   * Rendered above the first entry, INSIDE the scroll box — this component owns
   * the scroll container, so anything the caller wants to scroll with the
   * transcript (rather than sit pinned above it) has to come through here.
   * Used for the shared discussion-insights panels and the task board.
   */
  header?: React.ReactNode;
  /**
   * Structured conclusion (EDDI F3) — a debate verdict, vote tally or
   * negotiation agreement, with its minority report. Rendered directly BEFORE
   * the prose synthesis, because for a DEBATE the synthesis body IS the judge's
   * reasoning: the finding it argues for has to come first. The Manager
   * transcript places it the same way.
   */
  decision?: DecisionRecord | null;
  /**
   * Live convergence checks per phase index (I2, DELPHI-style repeats). Stream
   * state only — no persisted field carries it — so history views pass nothing.
   */
  convergence?: Map<number, ConvergenceProgress> | null;

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
  // Wave-3 phase/entry types (I14/I11/I8/I18). Keyed by both the PhaseType and
  // the TranscriptEntryType name where they differ, since `inferPhaseType`
  // looks entries up here directly.
  VOTE: "🗳️",
  PROPOSAL: "🤝",
  BARGAIN: "🔄",
  RETRO: "🪞",
  BID: "💰",
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
    case "DISSENT":
      return "destructive";
    case "ABSTAINED":
      return "secondary";
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
  convergence,
  delay,
}: {
  phaseName: string | null;
  phaseType?: string;
  convergence?: ConvergenceProgress;
  delay: number;
}) {
  const { t } = useTranslation();
  const icon = getPhaseIcon(phaseType);

  return (
    // role="separator" sits on the pill, NOT this container: a separator's
    // descendants are presentational to assistive tech, and the convergence
    // badge below is content a screen-reader user needs read out.
    <div
      className="flex flex-col items-center my-4 gap-1"
      style={{ animation: "br-message-in 250ms ease-out both", animationDelay: `${delay}ms` }}
    >
      <div
        role="separator"
        aria-label={phaseName ?? phaseType ?? t("Workforce.board.phase", "Phase")}
        className={cn(
          "flex items-center gap-1.5 ps-3 pe-3 py-1.5 rounded-full",
          "text-[11px] uppercase tracking-wider font-medium",
          "bg-muted/50 text-muted-foreground border border-border/30",
        )}
      >
        <span>{icon}</span>
        <span>{phaseName ?? phaseType ?? t("Workforce.board.phase", "Phase")}</span>
      </div>
      {/* Convergence result (I2) — same i18n keys as the Manager's phase header,
          so both surfaces describe an early stop with the same words. */}
      {convergence && (
        <div
          className={cn(
            "flex items-center gap-1.5 rounded-full ps-2.5 pe-2.5 py-0.5 text-[10px]",
            convergence.converged
              ? "border border-violet-500/30 bg-violet-500/5 text-violet-600 dark:text-violet-400"
              : "border border-border/30 bg-secondary/20 text-muted-foreground",
          )}
          data-testid={`board-phase-convergence-${convergence.phaseIndex}`}
        >
          <GitMerge className="h-2.5 w-2.5 shrink-0" aria-hidden="true" />
          <span className="font-medium">
            {convergence.converged
              ? t("groups.convergenceReached", "Converged")
              : t("groups.convergenceChecked", "Convergence check")}
          </span>
          {convergence.agreementScore != null && (
            <span className="tabular-nums">
              {t("groups.convergenceScore", "agreement {{score}}", {
                score: convergence.agreementScore.toFixed(2),
              })}
            </span>
          )}
          {convergence.repeatsSkipped != null && convergence.repeatsSkipped > 0 && (
            <span>
              {t("groups.convergenceSkipped", {
                defaultValue: "· {{count}} further round skipped",
                defaultValue_other: "· {{count}} further rounds skipped",
                count: convergence.repeatsSkipped,
              })}
            </span>
          )}
        </div>
      )}
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
  // `entryTypeInfo`, not a raw ENTRY_TYPE_INFO lookup: the backend's entry-type
  // enum grows every collaboration wave, and the previous `info && …` guard meant
  // a DISSENT rendered as an unlabelled contribution — indistinguishable from an
  // ordinary opinion, which is the one thing a minority report must not be.
  const info = entryTypeInfo(entry.type);
  const variant = badgeVariant(entry.type);
  const time = formatTime(entry.timestamp);

  return (
    <div
      style={{ animationDelay: `${delay}ms` }}
    >
      <AdvisorResponseCard
        displayName={entry.speakerDisplayName}
        agentId={entry.speakerAgentId}
        role={t(`groups.entryType.${entry.type}`, info.label)}
        roleBadgeVariant={variant}
        content={entry.content}
        boardId={boardId}
        timestamp={time}
      />
    </div>
  );
}

/** Bottom-of-transcript "still running" row. */
function LiveRow() {
  const { t } = useTranslation();

  return (
    // No role="status" — the transcript container is already an aria-live
    // region, and nesting one inside it announces the row twice.
    <div
      className="flex items-center gap-2 py-3 text-xs text-muted-foreground"
      data-testid="transcript-live-row"
    >
      <span className="flex items-center gap-1" aria-hidden>
        <span className="inline-block h-1.5 w-1.5 rounded-full bg-primary animate-bounce" style={{ animationDelay: "0ms" }} />
        <span className="inline-block h-1.5 w-1.5 rounded-full bg-primary animate-bounce" style={{ animationDelay: "160ms" }} />
        <span className="inline-block h-1.5 w-1.5 rounded-full bg-primary animate-bounce" style={{ animationDelay: "320ms" }} />
      </span>
      {t("Workforce.board.stillDiscussing", "The task force is still discussing…")}
    </div>
  );
}

// ─── Main Component ──────────────────────────────────────────────

function BoardTranscript({
  transcript,
  boardId,
  synthesizedAnswer,
  isLive = false,
  header,
  decision,
  convergence,
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

  // Auto-scroll to bottom as content arrives. `lastContent` is in the deps so a
  // placeholder turning into a real answer scrolls too, not just new entries.
  // Layout effect, not effect: scrolling after paint shows one frame at the old
  // offset before snapping down, which reads as a jump on every streamed turn.
  // `header` is in the deps because it renders INSIDE the scroll box: an
  // insight panel appearing mid-discussion changes scrollHeight without
  // touching any transcript field, so without it a user pinned to the bottom
  // silently drifts up by the panel's height and misses new turns. `decision`
  // and `convergence` are in for the same reason: both add content on an event
  // that changes no transcript field.
  const lastContent = transcript[transcript.length - 1]?.content ?? null;
  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (el && isNearBottomRef.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [transcript.length, lastContent, synthesizedAnswer, isLive, header, decision, convergence]);

  // Check if transcript already contains a SYNTHESIS entry
  const hasSynthesisEntry = transcript.some((e) => e.type === "SYNTHESIS");

  // Where the structured decision card goes: immediately before the LAST
  // synthesis element (the judge's reasoning argues FOR the finding, so the
  // finding comes first). With no synthesis on screen yet — mid-stream, or a
  // style that never synthesizes — it trails the entries instead.
  const showDecision = hasDisplayableDecision(decision);
  // No findLastIndex — the build targets ES2022.
  let lastSynthesisIdx = -1;
  if (showDecision) {
    for (let i = transcript.length - 1; i >= 0; i--) {
      if (transcript[i]!.type === "SYNTHESIS") {
        lastSynthesisIdx = i;
        break;
      }
    }
  }
  const decisionCard = showDecision ? <DecisionRecordCard decision={decision!} /> : null;

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
      {header}

      {processedEntries.map(({ entry, showPhaseHeader }, idx) => {
        const delay = Math.min(idx * 60, 600);

        const phaseHeader = showPhaseHeader ? (
          <PhaseHeader
            key={`phase-${entry.phaseIndex}`}
            phaseName={entry.phaseName}
            phaseType={inferPhaseType(entry)}
            convergence={convergence?.get(entry.phaseIndex)}
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
              <div key={`syn-${idx}`} className="space-y-2">
                {phaseHeader}
                {idx === lastSynthesisIdx && decisionCard}
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

      {/* Structured decision when no synthesis element exists to anchor it */}
      {lastSynthesisIdx < 0 && !(synthesizedAnswer && !hasSynthesisEntry) && decisionCard}

      {/* Trailing synthesis from synthesizedAnswer prop */}
      {synthesizedAnswer && !hasSynthesisEntry && (
        <>
          {lastSynthesisIdx < 0 && decisionCard}
          <SynthesisCard content={synthesizedAnswer} delay={Math.min(transcript.length * 60, 600)} />
        </>
      )}

      {isLive && <LiveRow />}
    </div>
  );
}

export { BoardTranscript };
export type { BoardTranscriptProps };
