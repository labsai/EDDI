import { useCallback } from "react";
import { useTranslation } from "react-i18next";
import { X, AlertCircle, Download } from "lucide-react";
import { cn, formatRelativeTime } from "@eddi/app-kit/lib/utils";
import { useGroupConversation } from "@eddi/app-kit/hooks/use-groups";
import { Badge, Button } from "@eddi/ui";
import { Skeleton } from "@eddi/app-kit/components/ui/skeleton";
import { DecisionRecordCard, DiscussionInsights, PersistedTaskBoard } from "@eddi/conversation/team";
import { DiscussionPanel } from "@eddi/app-kit/components/groups/overview/discussion-panel";
import { hasDisplayableDecision } from "@eddi/app-kit/lib/group-config";
import type { DiscussionStyle, TaskDefinition, GroupConversationState } from "@eddi/app-kit/lib/api/groups";
import { BoardTranscript } from "@/components/workforce/board-transcript";
import { downloadFile, generateMarkdown } from "@/lib/group-transcript-export";

// ─── Types ───────────────────────────────────────────────────────

interface ConversationViewerProps {
  groupId: string;
  conversationId: string;
  /** Titles the exported file. Without it the export is headed "Discussion". */
  groupName?: string;
  /** The group's configured tasks, which a pre-configured PLAN entry's one-line summary stands for. */
  preConfiguredTasks?: TaskDefinition[];
  /**
   * The group's discussion style. The overview picks its per-style emphasis
   * from it (a TASK_FORCE opens on the task board, a DEBATE on the verdict);
   * without it the history view rendered every discussion as a generic one.
   */
  style?: DiscussionStyle | null;
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
  // A recorded human decision, not a fault — neutral, never destructive.
  REJECTED: { label: "Rejected", variant: "secondary" },
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
    REJECTED: "Workforce.history.rejected",
    CANCELLED: "Workforce.history.cancelled",
    AWAITING_APPROVAL: "Workforce.history.awaitingApproval",
    AWAITING_HUMAN_INPUT: "Workforce.history.awaitingHumanInput",
    CLOSED: "Workforce.history.closed",
  };
  return map[state] ?? "Workforce.history.created";
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
  groupName,
  preConfiguredTasks,
  style,
  onClose,
  className,
}: ConversationViewerProps) {
  const { t } = useTranslation();
  const { data: conversation, isLoading, isError } = useGroupConversation(
    groupId,
    conversationId,
  );
  // ── Export conversation as Markdown ────────────────────────
  /**
   * Export the discussion as Markdown.
   *
   * This used to be a second, hand-rolled copy of the board export menu's
   * renderer — its own code comment said so. The two had drifted: this one
   * titled every file "# Task Force Discussion" whatever the discussion style,
   * and omitted the group name, the structured decision, the minority report
   * and an unparsed judgment. One renderer now serves both toolbars.
   */
  const handleExport = useCallback(() => {
    if (!conversation) return;
    downloadFile(
      generateMarkdown(conversation, groupName, (key, fallback, options) =>
        t(key, { ...options, defaultValue: fallback }),
      ),
      `discussion-${conversationId.slice(0, 8)}.md`,
      "text/markdown",
    );
  }, [conversation, conversationId, groupName, t]);

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
  // The structured decision (F3) is the overview's outcome; the transcript
  // places it itself, before the synthesis it argues for.
  const decisionCard = hasDisplayableDecision(conversation.decision) ? (
    <DecisionRecordCard decision={conversation.decision!} />
  ) : null;

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
      {/* Wrapped so the history viewer offers the same transcript /
          overview / split switch as the Manager and the live board. The
          scroll box below is passed through untouched — it keeps owning
          its own scrolling and its export/copy affordances. */}
      <DiscussionPanel
        className="flex-1 min-h-0"
        surface="workforce-history"
        conversation={conversation}
        style={style}
        // No group config on this surface: it opens a stored conversation by
        // id and never fetches the group. The digest derives phases from the
        // transcript instead, so the rail shows the phases that ran — only
        // phases never reached are missing, and a finished discussion has none.
        outcome={decisionCard ?? undefined}
        extras={
          <>
            {/* The viewer renders this inside the scroll box below, which
                Overview mode unmounts — so TASK_FORCE history would lose its
                task board entirely. */}
            {(conversation.taskList?.tasks?.length ?? 0) > 0 && (
              <PersistedTaskBoard
                taskList={conversation.taskList!}
                memberDisplayNames={conversation.memberDisplayNames}
              />
            )}
            <DiscussionInsights conversation={conversation} />
          </>
        }
        transcript={
          // One transcript for a team's discussion, live or stored: the board's.
          // Read-only here — no live row, no convergence stream — and opened at
          // the top rather than following new entries. Keyed by conversation so
          // picking another one starts at its top again.
          <BoardTranscript
            key={conversationId}
            transcript={conversation.transcript}
            boardId={groupId}
            sessionId={conversationId}
            synthesizedAnswer={conversation.synthesizedAnswer}
            decision={conversation.decision}
            memberDisplayNames={conversation.memberDisplayNames}
            preConfiguredTasks={preConfiguredTasks}
            follow={false}
            label={t("Workforce.history.transcript", "Conversation transcript")}
            className="flex-1 ps-5 pe-5 py-4"
            header={
              <>
                {/* Persisted task board (TASK_FORCE plans and agent-filed tasks,
                    I5/I18) first, so a task-force session opens on WHAT was done;
                    then shared artifacts, the negotiation ledger and the windowing
                    summary (I17/I11/I9). The single-conversation GET is the only
                    response that carries `artifacts`. */}
                {(conversation.taskList?.tasks?.length ?? 0) > 0 && (
                  <PersistedTaskBoard
                    taskList={conversation.taskList!}
                    memberDisplayNames={conversation.memberDisplayNames}
                  />
                )}
                <DiscussionInsights conversation={conversation} />
                {conversation.transcript.length === 0 && (
                  <div className="flex items-center justify-center py-12">
                    <p className="text-sm text-muted-foreground">
                      {t("Workforce.history.emptyTranscript", "No transcript entries yet")}
                    </p>
                  </div>
                )}
              </>
            }
          />
        }
      />
    </div>
  );
}

export { ConversationViewer };
export type { ConversationViewerProps };
