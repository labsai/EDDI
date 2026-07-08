import { useRef, useEffect, useMemo, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import { AdvisorResponseCard } from "@/components/boardroom/advisor-response-card";
import type { TranscriptEntry } from "@/lib/api/groups";

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

// ─── Helpers ─────────────────────────────────────────────────────

function getPhaseIcon(phaseType?: string | null): string {
  if (!phaseType) return "📌";
  return PHASE_ICONS[phaseType] ?? "📌";
}

/**
 * Determine the phaseType from a transcript entry.
 * The actual TranscriptEntry has `type` (TranscriptEntryType) which often
 * maps 1:1 with the phase type string. We also check `phaseName` for hints.
 */
function inferPhaseType(entry: TranscriptEntry): string | undefined {
  // Direct type match
  if (entry.type && entry.type in PHASE_ICONS) return entry.type;
  // phaseName may contain the phase type
  if (entry.phaseName) {
    const upper = entry.phaseName.toUpperCase();
    if (upper in PHASE_ICONS) return upper;
  }
  return undefined;
}

// ─── Sub-components ──────────────────────────────────────────────

/** User question bubble (right-aligned) */
function QuestionBubble({ content, delay }: { content: string | null; delay: number }) {
  return (
    <div
      className="flex justify-end"
      style={{ animation: "br-message-in 250ms ease-out both", animationDelay: `${delay}ms` }}
    >
      <div className="bg-primary text-primary-foreground rounded-2xl rounded-ee-md ps-4 pe-4 py-3 max-w-lg">
        <p className="text-sm whitespace-pre-wrap">{content ?? ""}</p>
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
      aria-label={phaseName ?? phaseType ?? t("boardroom.board.phase", "Phase")}
      className="flex justify-center my-3"
      style={{ animation: "br-message-in 250ms ease-out both", animationDelay: `${delay}ms` }}
    >
      <div
        className={cn(
          "flex items-center gap-1.5 ps-3 pe-3 py-1 rounded-full",
          "text-xs uppercase tracking-wider font-medium",
          "bg-muted text-muted-foreground",
        )}
      >
        <span>{icon}</span>
        <span>{phaseName ?? phaseType ?? t("boardroom.board.phase", "Phase")}</span>
      </div>
    </div>
  );
}

/** Synthesis card */
function SynthesisCard({ content, delay }: { content: string; delay: number }) {
  const { t } = useTranslation();

  return (
    <div
      role="status"
      aria-label={t("boardroom.board.synthesisResult", "Synthesis result")}
      className={cn(
        "border-s-4 border-primary rounded-xl p-4",
        "bg-primary/10",
      )}
      style={{ animation: "br-message-in 250ms ease-out both", animationDelay: `${delay}ms` }}
    >
      <div className="flex items-center gap-2 mb-2">
        <span>✨</span>
        <span className="text-sm font-semibold text-primary">
          {t("boardroom.board.synthesis", "Synthesis")}
        </span>
      </div>
      <p className="text-sm text-foreground/80 whitespace-pre-wrap leading-relaxed">
        {content}
      </p>
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
        "rounded-xl border p-3 opacity-60",
        "bg-muted/50 border-border",
      )}
      style={{ animation: "br-message-in 250ms ease-out both", animationDelay: `${delay}ms` }}
    >
      <p className="text-xs text-muted-foreground">
        {t("boardroom.board.skipped", "{{name}} — Skipped", { name: displayName })}
        {reason && <span className="ms-1">({reason})</span>}
      </p>
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
    <div ref={scrollRef} onScroll={handleScroll} aria-live="polite" aria-relevant="additions" className={cn("flex flex-col gap-3 overflow-y-auto", className)}>
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
            // All response types (OPINION, CRITIQUE, REVISION, etc.)
            return (
              <div
                key={`r-${idx}`}
                style={{ animationDelay: `${delay}ms` }}
              >
                {phaseHeader}
                <AdvisorResponseCard
                  displayName={entry.speakerDisplayName}
                  agentId={entry.speakerAgentId}
                  role={null}
                  content={entry.content}
                  boardId={boardId}
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
