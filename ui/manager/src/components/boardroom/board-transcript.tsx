import { useRef, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import { AdvisorResponseCard } from "@/components/boardroom/advisor-response-card";
import type { TranscriptEntry } from "@/lib/api/groups";

// ─── Types ───────────────────────────────────────────────────────

interface BoardTranscriptProps {
  transcript: TranscriptEntry[];
  boardId: string;
  synthesizedAnswer?: string | null;
  activeSpeakers?: Set<string>;
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
      <div className="bg-indigo-500 text-white rounded-2xl rounded-ee-md px-4 py-3 max-w-lg">
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
  const icon = getPhaseIcon(phaseType);

  return (
    <div
      className="flex justify-center my-3"
      style={{ animation: "br-message-in 250ms ease-out both", animationDelay: `${delay}ms` }}
    >
      <div
        className={cn(
          "flex items-center gap-1.5 px-3 py-1 rounded-full",
          "text-xs uppercase tracking-wider font-medium",
          "bg-slate-100 text-slate-600",
          "dark:bg-slate-800 dark:text-slate-400",
        )}
      >
        <span>{icon}</span>
        <span>{phaseName ?? phaseType ?? "Phase"}</span>
      </div>
    </div>
  );
}

/** Synthesis card */
function SynthesisCard({ content, delay }: { content: string; delay: number }) {
  const { t } = useTranslation();

  return (
    <div
      className={cn(
        "border-s-4 border-indigo-500 rounded-xl p-4",
        "bg-indigo-50 dark:bg-indigo-500/10",
      )}
      style={{ animation: "br-message-in 250ms ease-out both", animationDelay: `${delay}ms` }}
    >
      <div className="flex items-center gap-2 mb-2">
        <span>✨</span>
        <span className="text-sm font-semibold text-indigo-700 dark:text-indigo-300">
          {t("boardroom.board.synthesis", "Synthesis")}
        </span>
      </div>
      <p className="text-sm text-slate-700 dark:text-slate-300 whitespace-pre-wrap leading-relaxed">
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
        "bg-slate-50 border-slate-200",
        "dark:bg-slate-900/30 dark:border-slate-800",
      )}
      style={{ animation: "br-message-in 250ms ease-out both", animationDelay: `${delay}ms` }}
    >
      <p className="text-xs text-slate-400">
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

  // Auto-scroll to bottom on new entries
  useEffect(() => {
    const el = scrollRef.current;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  }, [transcript.length, synthesizedAnswer]);

  // Check if transcript already contains a SYNTHESIS entry
  const hasSynthesisEntry = transcript.some((e) => e.type === "SYNTHESIS");

  // Track which phases we've already rendered headers for
  let lastPhaseIndex = -1;

  return (
    <div ref={scrollRef} className={cn("flex flex-col gap-3 overflow-y-auto", className)}>
      {transcript.map((entry, idx) => {
        const delay = idx * 60;

        // Render phase header when phase changes
        let phaseHeader: React.ReactNode = null;
        if (
          entry.phaseIndex >= 0 &&
          entry.phaseIndex !== lastPhaseIndex &&
          entry.type !== "QUESTION"
        ) {
          lastPhaseIndex = entry.phaseIndex;
          phaseHeader = (
            <PhaseHeader
              key={`phase-${entry.phaseIndex}`}
              phaseName={entry.phaseName}
              phaseType={inferPhaseType(entry)}
              delay={delay}
            />
          );
        }

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
                  phaseType={entry.type}
                  boardId={boardId}
                />
              </div>
            );
        }
      })}

      {/* Trailing synthesis from synthesizedAnswer prop */}
      {synthesizedAnswer && !hasSynthesisEntry && (
        <SynthesisCard content={synthesizedAnswer} delay={transcript.length * 60} />
      )}
    </div>
  );
}

export { BoardTranscript };
export type { BoardTranscriptProps };
