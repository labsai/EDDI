import { useTranslation } from "react-i18next";
import { MessageSquareQuote, Copy, CheckCircle2 } from "lucide-react";
import { useState, useRef, useEffect, useMemo } from "react";
import { PhaseHeader } from "./phase-header";
import { AgentResponseCard } from "./agent-response-card";
import type { GroupConversation, TranscriptEntry, PhaseType, TranscriptEntryType } from "@/lib/api/groups";
import type { GroupStreamState } from "@/hooks/use-group-discussion-stream";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";

interface DiscussionTranscriptProps {
  conversation: GroupConversation | null;
  /** Live streaming state from SSE hook — takes priority over conversation */
  streamState?: GroupStreamState;
  isLoading?: boolean;
}

interface PhaseGroup {
  phaseIndex: number;
  phaseName: string;
  phaseType: PhaseType;
  entries: TranscriptEntry[];
}

/** Infer PhaseType from TranscriptEntryType */
function entryTypeToPhaseType(type: TranscriptEntryType): PhaseType {
  const map: Partial<Record<TranscriptEntryType, PhaseType>> = {
    OPINION: "OPINION",
    CRITIQUE: "CRITIQUE",
    REVISION: "REVISION",
    CHALLENGE: "CHALLENGE",
    DEFENSE: "DEFENSE",
    ARGUMENT: "ARGUE",
    REBUTTAL: "REBUTTAL",
    SYNTHESIS: "SYNTHESIS",
  };
  return map[type] || "OPINION";
}

function groupByPhase(entries: TranscriptEntry[]): PhaseGroup[] {
  const groups: PhaseGroup[] = [];
  let currentGroup: PhaseGroup | null = null;

  for (const entry of entries) {
    // Skip the user question — rendered separately
    if (entry.type === "QUESTION") continue;

    const phaseName = entry.phaseName || `Phase ${entry.phaseIndex}`;

    if (!currentGroup || currentGroup.phaseIndex !== entry.phaseIndex || currentGroup.phaseName !== phaseName) {
      currentGroup = {
        phaseIndex: entry.phaseIndex,
        phaseName,
        phaseType: entryTypeToPhaseType(entry.type),
        entries: [],
      };
      groups.push(currentGroup);
    }
    currentGroup.entries.push(entry);
  }

  return groups;
}

// State variants — labels resolved via i18n in component
const STATE_VARIANTS: Record<string, { variant: "default" | "success" | "warning" | "destructive" }> = {
  CREATED: { variant: "default" },
  IN_PROGRESS: { variant: "warning" },
  SYNTHESIZING: { variant: "warning" },
  COMPLETED: { variant: "success" },
  FAILED: { variant: "destructive" },
};

export function DiscussionTranscript({
  conversation,
  streamState,
  isLoading,
}: DiscussionTranscriptProps) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  // Determine the effective data source: streaming or static
  const isStreaming = !!streamState && (streamState.isStreaming || streamState.state !== "CREATED");

  // Build effective transcript, state, and metadata
  const effectiveTranscript = useMemo(
    () => (isStreaming ? streamState!.transcript : (conversation?.transcript ?? [])),
    [isStreaming, streamState, conversation?.transcript]
  );
  const effectiveState = isStreaming ? streamState!.state : (conversation?.state ?? "CREATED");
  const effectiveCurrentPhase = isStreaming ? streamState!.currentPhase?.name : conversation?.currentPhaseName;
  const effectiveSynthesis = isStreaming ? streamState!.synthesizedAnswer : conversation?.synthesizedAnswer;
  const effectiveQuestion = isStreaming
    ? (effectiveTranscript.find((e) => e.type === "QUESTION")?.content ?? "")
    : (conversation?.originalQuestion ?? "");
  const effectiveCreated = isStreaming
    ? new Date().toISOString()
    : (conversation?.created ?? new Date().toISOString());
  const activeSpeakers = isStreaming ? streamState!.activeSpeakers : new Set<string>();
  const streamError = isStreaming ? streamState!.error : null;

  // Memoize phases to avoid re-grouping on every render
  const phases = useMemo(() => groupByPhase(effectiveTranscript), [effectiveTranscript]);

  // Auto-scroll to bottom when new entries arrive during streaming
  useEffect(() => {
    if (isStreaming && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [isStreaming, effectiveTranscript.length]);

  if (isLoading) {
    return (
      <div className="space-y-4 p-4">
        <Skeleton className="h-16 w-full" />
        <Skeleton className="h-32 w-full" />
        <Skeleton className="h-32 w-full" />
      </div>
    );
  }

  if (!isStreaming && !conversation) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-center p-8">
        <MessageSquareQuote className="h-12 w-12 text-muted-foreground/30 mb-3" />
        <p className="text-muted-foreground">
          {t("groups.selectDiscussion", "Select a discussion or start a new one")}
        </p>
      </div>
    );
  }

  const stateVariant = STATE_VARIANTS[effectiveState] || STATE_VARIANTS.CREATED;
  const discussionStateLabels: Record<string, string> = {
    CREATED: t("groups.stateCreated", "Created"),
    IN_PROGRESS: t("conversations.stateInProgress", "In Progress"),
    SYNTHESIZING: t("groups.stateSynthesizing", "Synthesizing…"),
    COMPLETED: t("groups.stateCompleted", "Completed"),
    FAILED: t("groups.stateFailed", "Failed"),
  };
  const stateLabel = discussionStateLabels[effectiveState] ?? effectiveState;

  function handleCopySynthesis() {
    if (effectiveSynthesis) {
      navigator.clipboard.writeText(effectiveSynthesis);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  }

  return (
    <div className="flex flex-col h-full">
      {/* Question header */}
      <div className="border-b border-border p-4 bg-card/50">
        <div className="flex items-start gap-3">
          <div className="flex h-8 w-8 items-center justify-center rounded-full bg-primary text-primary-foreground text-xs font-bold shrink-0">
            Q
          </div>
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2 mb-1 flex-wrap">
              <span className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                {t("groups.question", "Question")}
              </span>
              <Badge variant={stateVariant!.variant} className="text-[10px]">
                {stateLabel}
              </Badge>
              {isStreaming && (
                <Badge variant="outline" className="text-[10px] text-primary border-primary/30 animate-pulse">
                  ● LIVE
                </Badge>
              )}
              <span className="text-[10px] text-muted-foreground ms-auto">
                {new Date(effectiveCreated).toLocaleString()}
              </span>
            </div>
            <p className="text-base font-medium text-foreground">
              {effectiveQuestion}
            </p>
          </div>
        </div>
      </div>

      {/* Transcript body */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto p-4 space-y-3">
        {phases.map((phase, idx) => (
          <PhaseHeader
            key={`${phase.phaseIndex}-${phase.phaseName}-${idx}`}
            name={phase.phaseName}
            type={phase.phaseType}
            entryCount={phase.entries.length}
            isActive={
              (effectiveState === "IN_PROGRESS" || effectiveState === "SYNTHESIZING") &&
              phase.phaseIndex === (isStreaming ? streamState!.currentPhase?.index : conversation?.currentPhaseIndex)
            }
            defaultExpanded={true}
          >
            {phase.entries.map((entry, entryIdx) => (
              <AgentResponseCard
                key={`${entry.speakerAgentId}-${entry.phaseIndex}-${entryIdx}`}
                entry={entry}
                isSpeaking={activeSpeakers.has(entry.speakerAgentId) && entry.content === null}
              />
            ))}
          </PhaseHeader>
        ))}

        {/* Synthesized answer highlight */}
        {effectiveSynthesis && (
          <div
            className={cn(
              "rounded-xl border-2 border-primary/40 bg-linear-to-b from-primary/10 to-primary/5 p-4 shadow-sm"
            )}
            data-testid="synthesis-card"
          >
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2">
                <span className="text-lg">⭐</span>
                <span className="text-sm font-bold text-primary">
                  {t("groups.synthesis", "Synthesis")}
                </span>
              </div>
              <button
                onClick={handleCopySynthesis}
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
            <div className="text-sm text-foreground leading-relaxed whitespace-pre-wrap">
              {effectiveSynthesis}
            </div>
          </div>
        )}

        {/* In-progress indicator */}
        {(effectiveState === "IN_PROGRESS" || effectiveState === "SYNTHESIZING") && (
          <div className="flex items-center gap-3 p-3 rounded-lg bg-primary/5 border border-primary/20">
            <div className="flex gap-1">
              <span className="h-2 w-2 rounded-full bg-primary animate-bounce [animation-delay:0ms]" />
              <span className="h-2 w-2 rounded-full bg-primary animate-bounce [animation-delay:150ms]" />
              <span className="h-2 w-2 rounded-full bg-primary animate-bounce [animation-delay:300ms]" />
            </div>
            <span className="text-sm text-primary font-medium">
              {effectiveState === "SYNTHESIZING"
                ? t("groups.synthesizing", "Moderator is synthesizing…")
                : t("groups.discussing", "Agents are discussing…")}
            </span>
            {effectiveCurrentPhase && (
              <Badge variant="outline" className="text-[10px]">
                {effectiveCurrentPhase}
              </Badge>
            )}
            {isStreaming && activeSpeakers.size > 0 && (
              <span className="text-[10px] text-muted-foreground">
                {activeSpeakers.size} speaking
              </span>
            )}
          </div>
        )}

        {/* Error state */}
        {streamError && (
          <div className="flex items-center gap-2 p-3 rounded-lg bg-destructive/5 border border-destructive/20">
            <span className="text-sm text-destructive font-medium">⚠️ {streamError}</span>
          </div>
        )}
      </div>
    </div>
  );
}

