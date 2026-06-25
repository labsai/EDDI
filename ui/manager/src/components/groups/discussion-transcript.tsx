import { useTranslation } from "react-i18next";
import { MessageSquareQuote, Copy, CheckCircle2, Code, ArrowRight, ChevronDown, ChevronUp, Check } from "lucide-react";
import { useState, useRef, useEffect, useMemo } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeRaw from "rehype-raw";
import { PhaseHeader } from "./phase-header";
import { AgentResponseCard } from "./agent-response-card";
import { TaskBoard } from "./task-board";
import { parseTranscriptContent, safeFormatDate } from "./group-utils";
import type { GroupConversation, TranscriptEntry, PhaseType, TranscriptEntryType, DiscussionStyle, SharedTaskList } from "@/lib/api/groups";
import type { GroupStreamState } from "@/hooks/use-group-discussion-stream";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { STYLE_INFO } from "@/lib/api/groups";

interface DiscussionTranscriptProps {
  conversation: GroupConversation | null;
  /** Live streaming state from SSE hook — takes priority over conversation */
  streamState?: GroupStreamState;
  isLoading?: boolean;
  /** Discussion style for visual theming */
  discussionStyle?: DiscussionStyle;
}

interface PhaseGroup {
  phaseIndex: number;
  phaseName: string;
  phaseType: PhaseType;
  entries: TranscriptEntry[];
}

/** Style-aware accent colors for transcript theming */
const STYLE_THEME: Record<DiscussionStyle, {
  accent: string;
  dotColor: string;
  phaseAccent: string;
  questionBg: string;
  flowBg: string;
  flowText: string;
  progressBg: string;
  progressText: string;
  progressBorder: string;
}> = {
  ROUND_TABLE: {
    accent: "text-amber-500",
    dotColor: "bg-amber-500",
    phaseAccent: "border-amber-500/30 bg-amber-500/5",
    questionBg: "bg-amber-500/5 border-b-amber-500/20",
    flowBg: "bg-amber-500/10",
    flowText: "text-amber-600 dark:text-amber-400",
    progressBg: "bg-amber-500/5",
    progressText: "text-amber-600 dark:text-amber-400",
    progressBorder: "border-amber-500/20",
  },
  PEER_REVIEW: {
    accent: "text-teal-500",
    dotColor: "bg-teal-500",
    phaseAccent: "border-teal-500/30 bg-teal-500/5",
    questionBg: "bg-teal-500/5 border-b-teal-500/20",
    flowBg: "bg-teal-500/10",
    flowText: "text-teal-600 dark:text-teal-400",
    progressBg: "bg-teal-500/5",
    progressText: "text-teal-600 dark:text-teal-400",
    progressBorder: "border-teal-500/20",
  },
  DEVIL_ADVOCATE: {
    accent: "text-rose-500",
    dotColor: "bg-rose-500",
    phaseAccent: "border-rose-500/30 bg-rose-500/5",
    questionBg: "bg-rose-500/5 border-b-rose-500/20",
    flowBg: "bg-rose-500/10",
    flowText: "text-rose-600 dark:text-rose-400",
    progressBg: "bg-rose-500/5",
    progressText: "text-rose-600 dark:text-rose-400",
    progressBorder: "border-rose-500/20",
  },
  DELPHI: {
    accent: "text-violet-500",
    dotColor: "bg-violet-500",
    phaseAccent: "border-violet-500/30 bg-violet-500/5",
    questionBg: "bg-violet-500/5 border-b-violet-500/20",
    flowBg: "bg-violet-500/10",
    flowText: "text-violet-600 dark:text-violet-400",
    progressBg: "bg-violet-500/5",
    progressText: "text-violet-600 dark:text-violet-400",
    progressBorder: "border-violet-500/20",
  },
  DEBATE: {
    accent: "text-indigo-500",
    dotColor: "bg-indigo-500",
    phaseAccent: "border-indigo-500/30 bg-indigo-500/5",
    questionBg: "bg-indigo-500/5 border-b-indigo-500/20",
    flowBg: "bg-indigo-500/10",
    flowText: "text-indigo-600 dark:text-indigo-400",
    progressBg: "bg-indigo-500/5",
    progressText: "text-indigo-600 dark:text-indigo-400",
    progressBorder: "border-indigo-500/20",
  },
  TASK_FORCE: {
    accent: "text-orange-500",
    dotColor: "bg-orange-500",
    phaseAccent: "border-orange-500/30 bg-orange-500/5",
    questionBg: "bg-orange-500/5 border-b-orange-500/20",
    flowBg: "bg-orange-500/10",
    flowText: "text-orange-600 dark:text-orange-400",
    progressBg: "bg-orange-500/5",
    progressText: "text-orange-600 dark:text-orange-400",
    progressBorder: "border-orange-500/20",
  },
  CUSTOM: {
    accent: "text-primary",
    dotColor: "bg-primary",
    phaseAccent: "border-primary/30 bg-primary/5",
    questionBg: "bg-card/50",
    flowBg: "bg-primary/10",
    flowText: "text-primary",
    progressBg: "bg-primary/5",
    progressText: "text-primary",
    progressBorder: "border-primary/20",
  },
};

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
    PLAN: "PLAN",
    TASK_RESULT: "EXECUTE",
    VERIFICATION: "VERIFY",
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
  AWAITING_APPROVAL: { variant: "warning" },
};

/** Height above which synthesis content is collapsed */
const SYNTHESIS_COLLAPSE_HEIGHT = 300;

export function DiscussionTranscript({
  conversation,
  streamState,
  isLoading,
  discussionStyle,
}: DiscussionTranscriptProps) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);
  const [allowHtml, setAllowHtml] = useState(false);
  const [synthExpanded, setSynthExpanded] = useState(false);
  const [synthCollapsible, setSynthCollapsible] = useState(false);
  const synthRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  // Resolve theme colors
  const style = discussionStyle || "ROUND_TABLE";
  const theme = STYLE_THEME[style] || STYLE_THEME.ROUND_TABLE;
  const styleInfo = STYLE_INFO[style];

  // Determine the effective data source: streaming or static
  const isStreaming = !!streamState && (streamState.isStreaming || streamState.state !== "CREATED");

  // Build effective transcript, state, and metadata
  const effectiveTranscript = useMemo(
    () => (isStreaming ? streamState!.transcript : (conversation?.transcript ?? [])),
    [isStreaming, streamState, conversation?.transcript]
  );
  const effectiveState = isStreaming ? streamState!.state : (conversation?.state ?? "CREATED");
  const effectiveCurrentPhase = isStreaming ? streamState!.currentPhase?.name : conversation?.currentPhaseName;
  const currentPhaseIndex = isStreaming ? streamState!.currentPhase?.index : conversation?.currentPhaseIndex;
  const effectiveSynthesis = isStreaming ? streamState!.synthesizedAnswer : conversation?.synthesizedAnswer;
  // S3 fix: memoize question extraction to avoid scanning transcript on every render
  const effectiveQuestion = useMemo(
    () => isStreaming
      ? (effectiveTranscript.find((e) => e.type === "QUESTION")?.content ?? "")
      : (conversation?.originalQuestion ?? ""),
    [isStreaming, effectiveTranscript, conversation?.originalQuestion]
  );
  // C6 fix: use stable startedAt from stream state instead of new Date() per render
  const effectiveCreated = isStreaming
    ? (streamState!.startedAt ?? new Date().toISOString())
    : (conversation?.created ?? new Date().toISOString());
  const activeSpeakers = isStreaming ? streamState!.activeSpeakers : new Set<string>();
  const streamError = isStreaming ? streamState!.error : null;

  // Memoize phases to avoid re-grouping on every render
  const phases = useMemo(() => groupByPhase(effectiveTranscript), [effectiveTranscript]);

  // Auto-scroll to bottom when new entries arrive during streaming (smooth)
  useEffect(() => {
    if (isStreaming && scrollRef.current) {
      scrollRef.current.scrollTo({ top: scrollRef.current.scrollHeight, behavior: "smooth" });
    }
  }, [isStreaming, effectiveTranscript.length]);

  // Measure synthesis content for collapsible
  useEffect(() => {
    if (synthRef.current) {
      setSynthCollapsible(synthRef.current.scrollHeight > SYNTHESIS_COLLAPSE_HEIGHT);
    }
  }, [effectiveSynthesis]);

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
        <div className={cn(
          "flex h-16 w-16 items-center justify-center rounded-2xl mb-4",
          theme.flowBg
        )}>
          <MessageSquareQuote className={cn("h-8 w-8", theme.accent)} />
        </div>
        <p className="text-lg font-semibold text-foreground mb-1">
          {t("groups.readyToDiscuss", "Ready to discuss")}
        </p>
        <p className="text-sm text-muted-foreground max-w-sm">
          {t("groups.selectOrStart", "Select a past discussion from the history, or type a question below to start a new one.")}
        </p>
        {styleInfo && (
          <div className={cn("flex items-center gap-2 mt-4 rounded-lg px-3 py-2 border border-border", theme.flowBg)}>
            <span className="text-base">{styleInfo.icon}</span>
            <span className={cn("text-sm font-medium", theme.flowText)}>{styleInfo.label}</span>
            <span className="text-xs text-muted-foreground">— {styleInfo.flow}</span>
          </div>
        )}
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
    AWAITING_APPROVAL: t("groups.stateAwaitingApproval", "Awaiting Approval"),
  };
  const stateLabel = discussionStateLabels[effectiveState] ?? effectiveState;

  function handleCopySynthesis() {
    const parsed = effectiveSynthesis ? parseTranscriptContent(effectiveSynthesis) : null;
    if (parsed) {
      navigator.clipboard.writeText(parsed);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  }

  // Parse synthesis for display
  const parsedSynthesis = effectiveSynthesis ? parseTranscriptContent(effectiveSynthesis) : null;

  // Phase flow steps for breadcrumb
  const flowSteps = STYLE_INFO_FLOW[style] || [];

  return (
    <div className="flex flex-col h-full">
      {/* Question header — style-aware background */}
      <div className={cn("border-b p-4 shrink-0", theme.questionBg)}>
        <div className="flex items-start gap-3">
          <div className={cn("flex h-8 w-8 items-center justify-center rounded-full text-xs font-bold text-white shrink-0 bg-primary")}>
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
                <Badge variant="outline" className={cn("text-[10px] animate-pulse border-current", theme.accent)}>
                  {t("groups.liveIndicator", "● LIVE")}
                </Badge>
              )}
              {/* Allow HTML toggle — opt-in for trusted content */}
              <button
                onClick={() => setAllowHtml((v) => !v)}
                className={cn(
                  "flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[10px] transition-colors border",
                  allowHtml
                    ? "bg-primary/10 border-primary/30 text-primary"
                    : "border-transparent text-muted-foreground hover:text-foreground hover:bg-secondary/50"
                )}
                title={t("groups.allowHtmlTooltip", "When enabled, renders HTML content (sanitized). Use only with trusted agents.")}
              >
                <Code className="h-3 w-3" />
                {t("groups.htmlToggle", "HTML")}
              </button>
              <span className="text-[10px] text-muted-foreground ms-auto">
                {safeFormatDate(effectiveCreated, "full")}
              </span>
            </div>
            <p className="text-base font-medium text-foreground">
              {effectiveQuestion}
            </p>
          </div>
        </div>
      </div>

      {/* Phase flow indicator — shows the style's phases as breadcrumb with progress */}
      {style !== "CUSTOM" && flowSteps.length > 0 && (
        <div className={cn("flex items-center gap-1 px-4 py-1.5 border-b border-border shrink-0", theme.flowBg)}>
          {flowSteps.map((step, idx) => {
            const isActive = effectiveCurrentPhase?.toLowerCase().includes(step.toLowerCase());
            const isCompleted = effectiveState === "COMPLETED"
              || (currentPhaseIndex != null && idx < currentPhaseIndex);
            return (
              <span key={idx} className="flex items-center gap-1">
                <span className={cn(
                  "flex items-center gap-0.5 text-[10px] font-medium rounded px-1.5 py-0.5 transition-colors",
                  isActive
                    ? `${theme.flowText} font-bold bg-white/50 dark:bg-white/10`
                    : isCompleted
                      ? `${theme.flowText} opacity-60`
                      : "text-muted-foreground"
                )}>
                  {isCompleted && !isActive && (
                    <Check className="h-2.5 w-2.5" />
                  )}
                  {t(`groups.flow.${step.replace(/\s+/g, "")}`, step)}
                </span>
                {idx < flowSteps.length - 1 && (
                  <ArrowRight className="h-2.5 w-2.5 text-muted-foreground/50" />
                )}
              </span>
            );
          })}
        </div>
      )}

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
                allowHtml={allowHtml}
                discussionStyle={style}
              />
            ))}
          </PhaseHeader>
        ))}

        {/* Task Board — shown for TASK_FORCE style during/after streaming (until API data loads) */}
        {style === "TASK_FORCE" && streamState?.taskPlan && !conversation?.taskList && (
          <TaskBoard
            taskPlan={streamState.taskPlan}
            tasksInProgress={streamState.tasksInProgress}
            tasksCompleted={streamState.tasksCompleted}
            taskVerifications={streamState.taskVerifications}
            isStreaming={streamState.isStreaming}
          />
        )}
        {/* Show empty task board placeholder during TASK_FORCE streaming before plan arrives */}
        {style === "TASK_FORCE" && isStreaming && streamState && !streamState.taskPlan && !conversation?.taskList && (
          <TaskBoard
            taskPlan={null}
            tasksInProgress={new Set()}
            tasksCompleted={new Set()}
            taskVerifications={new Map()}
            isStreaming={true}
          />
        )}

        {/* Also show task board for completed TASK_FORCE conversations loaded from API */}
        {style === "TASK_FORCE" && !isStreaming && conversation?.taskList && (
          <MemoizedApiTaskBoard taskList={conversation.taskList} t={t} />
        )}

        {/* Synthesized answer highlight */}
        {parsedSynthesis && (
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
            {/* Collapsible synthesis body */}
            <div
              ref={synthRef}
              className={cn(
                "relative transition-[max-height] duration-300 ease-in-out overflow-hidden",
                synthCollapsible && !synthExpanded && "max-h-72"
              )}
            >
              <div className="prose prose-sm dark:prose-invert max-w-none text-foreground [&_pre]:rounded-lg [&_pre]:bg-muted [&_pre]:p-3 [&_code]:rounded [&_code]:bg-muted [&_code]:px-1 [&_code]:py-0.5 [&_code]:text-xs [&_hr]:border-border">
                <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeRaw]}>
                  {parsedSynthesis}
                </ReactMarkdown>
              </div>
              {/* Fade gradient when collapsed */}
              {synthCollapsible && !synthExpanded && (
                <div className="absolute bottom-0 inset-x-0 h-12 bg-gradient-to-t from-primary/5 to-transparent pointer-events-none" />
              )}
            </div>
            {synthCollapsible && (
              <button
                onClick={() => setSynthExpanded((v) => !v)}
                className="flex items-center gap-1 mt-2 text-xs font-medium text-primary hover:text-primary/80 transition-colors"
              >
                {synthExpanded ? (
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
        )}

        {/* In-progress indicator — style-aware */}
        {(effectiveState === "IN_PROGRESS" || effectiveState === "SYNTHESIZING") && (
          <div className={cn("flex items-center gap-3 p-3 rounded-lg border", theme.progressBg, theme.progressBorder)}>
            <div className="flex gap-1">
              <span className={cn("h-2 w-2 rounded-full animate-bounce [animation-delay:0ms]", theme.dotColor)} />
              <span className={cn("h-2 w-2 rounded-full animate-bounce [animation-delay:150ms]", theme.dotColor)} />
              <span className={cn("h-2 w-2 rounded-full animate-bounce [animation-delay:300ms]", theme.dotColor)} />
            </div>
            <span className={cn("text-sm font-medium", theme.progressText)}>
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
                {t("groups.speakingCount", "{{count}} speaking", { count: activeSpeakers.size })}
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

/** Memoized wrapper for API-loaded task boards to avoid re-creating Set/Map on every render */
function MemoizedApiTaskBoard({ taskList, t }: { taskList: SharedTaskList; t: (key: string, fallback: string) => string }) {
  const taskPlan = useMemo(
    () => taskList.tasks.map(task => ({
      id: task.id,
      subject: task.subject,
      assignedTo: task.assignedDisplayName || task.assignedAgentId || t("taskBoard.unassigned", "Unassigned"),
      priority: task.priority,
    })),
    [taskList, t],
  );
  const tasksInProgress = useMemo(
    () => new Set(taskList.tasks.filter(task => task.status === "IN_PROGRESS").map(task => task.id)),
    [taskList],
  );
  const tasksCompleted = useMemo(
    () => new Set(taskList.tasks.filter(task => task.status === "COMPLETED").map(task => task.id)),
    [taskList],
  );
  const taskVerifications = useMemo(
    () => new Map(
      taskList.tasks
        .filter(task => task.status === "VERIFIED" || task.verificationNote != null)
        .map(task => [task.id, { passed: task.verified, feedback: task.verificationNote || "" }] as const),
    ),
    [taskList],
  );

  return (
    <TaskBoard
      taskPlan={taskPlan}
      tasksInProgress={tasksInProgress}
      tasksCompleted={tasksCompleted}
      taskVerifications={taskVerifications}
      isStreaming={false}
    />
  );
}

/** Phase flow steps per discussion style for the breadcrumb indicator */
const STYLE_INFO_FLOW: Record<string, string[]> = {
  ROUND_TABLE: ["Opinion", "Discussion", "Synthesis"],
  PEER_REVIEW: ["Opinion", "Critique", "Revision", "Synthesis"],
  DEVIL_ADVOCATE: ["Opinion", "Challenge", "Defense", "Synthesis"],
  DELPHI: ["Independent", "Anonymous", "Revised", "Synthesis"],
  DEBATE: ["Pro Opening", "Con Opening", "Rebuttals", "Judgment"],
  TASK_FORCE: ["Plan", "Execute", "Verify", "Synthesize"],
};

// Re-export for use in group-detail
export { STYLE_THEME };
