import { useTranslation } from "react-i18next";
import { cn, formatRelativeTime } from "@/lib/utils";
import { useGroupConversations } from "@/hooks/use-groups";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import type { GroupConversationState } from "@/lib/api/groups";

// ─── Types ───────────────────────────────────────────────────────

interface SessionHistoryProps {
  groupId: string;
  selectedId: string | null;
  /** Conversation this tab is currently streaming, if any — always rendered as
   *  live, without waiting for the list poll to catch up. */
  streamingId?: string | null;
  onSelect: (convId: string) => void;
  onClose?: () => void;
  className?: string;
}

// ─── State Badge Config ──────────────────────────────────────────

const STATE_BADGE: Record<GroupConversationState, { label: string; variant: "success" | "warning" | "destructive" | "secondary" }> = {
  COMPLETED: { label: "Completed", variant: "success" },
  IN_PROGRESS: { label: "In Progress", variant: "warning" },
  SYNTHESIZING: { label: "Synthesizing", variant: "warning" },
  CREATED: { label: "Created", variant: "secondary" },
  FAILED: { label: "Failed", variant: "destructive" },
  CANCELLED: { label: "Cancelled", variant: "secondary" },
  AWAITING_APPROVAL: { label: "Awaiting Approval", variant: "warning" },
  AWAITING_HUMAN_INPUT: { label: "Awaiting Your Turn", variant: "warning" },
  CLOSED: { label: "Closed", variant: "secondary" },
};

// ─── Close Icon ──────────────────────────────────────────────────

function CloseIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className="h-4 w-4"
    >
      <path d="M18 6 6 18" />
      <path d="M6 6 18 18" />
    </svg>
  );
}

// ─── State i18n Key Mapping ──────────────────────────────────────

/**
 * Keyed off the shared `groups.state.*` namespace rather than a per-surface
 * switch. The switch this replaced ended in `default: "…created"`, so every
 * state it did not enumerate — CLOSED, and now AWAITING_HUMAN_INPUT — was
 * labelled "Created": a session waiting on a member to speak claimed it had not
 * started. A key derived from the state cannot silently mislabel a new one; at
 * worst it falls back to the English default passed alongside it.
 */
function getStateI18nKey(state: GroupConversationState): string {
  return `groups.state.${state}`;
}

// ─── Component ───────────────────────────────────────────────────

function SessionHistory({
  groupId,
  selectedId,
  streamingId,
  onSelect,
  onClose,
  className,
}: SessionHistoryProps) {
  const { t } = useTranslation();
  const { data: conversations, isLoading } = useGroupConversations(groupId);

  return (
    <div className={cn("flex flex-col h-full", className)}>
      {/* Header */}
      <div className="flex items-center justify-between ps-4 pe-4 py-3 border-b border-border">
        <h3 className="text-sm font-semibold text-foreground">
          {t("Workforce.board.sessions", "Sessions")}
        </h3>
        {onClose && (
          <Button variant="ghost" size="icon" onClick={onClose} className="h-8 w-8" aria-label={t("Workforce.board.close", "Close")}>
            <CloseIcon />
          </Button>
        )}
      </div>

      {/* List */}
      <div className="flex-1 overflow-y-auto">
        {isLoading && (
          <div className="p-4 space-y-3">
            {Array.from({ length: 3 }).map((_, i) => (
              <div key={i} className="space-y-2">
                <Skeleton className="h-4 w-3/4" />
                <Skeleton className="h-3 w-1/3" />
              </div>
            ))}
          </div>
        )}

        {!isLoading && (!conversations || conversations.length === 0) && (
          <div className="p-8 text-center">
            <p className="text-sm text-muted-foreground">
              {t("Workforce.board.noSessions", "No sessions yet")}
            </p>
          </div>
        )}

        {!isLoading &&
          conversations?.map((conv) => {
            const isSelected = conv.id === selectedId;
            const isLive =
              conv.id === streamingId ||
              conv.state === "IN_PROGRESS" ||
              conv.state === "SYNTHESIZING";
            const badgeConfig = STATE_BADGE[conv.state] ?? STATE_BADGE.CREATED;
            const timestamp = conv.lastModified
              ? new Date(conv.lastModified).getTime()
              : conv.created
                ? new Date(conv.created).getTime()
                : 0;

            return (
              <button
                key={conv.id}
                type="button"
                aria-current={isSelected ? "true" : undefined}
                onClick={() => onSelect(conv.id)}
                className={cn(
                  "w-full text-start ps-4 pe-4 py-3 transition-colors",
                  "hover:bg-muted/50",
                  "border-b border-border/50",
                  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring",
                  isSelected && "bg-primary/10 border-s-2 border-s-primary",
                )}
              >
                {/* Question text */}
                <p className="text-sm text-foreground/80 line-clamp-1">
                  {conv.originalQuestion || t("Workforce.board.untitled", "Untitled")}
                </p>

                {/* Bottom row: badge + timestamp */}
                <div className="flex items-center gap-2 mt-1.5">
                  <Badge
                    variant={badgeConfig.variant}
                    className="text-[10px] gap-1"
                    data-testid={isLive ? `session-live-${conv.id}` : undefined}
                  >
                    {isLive && (
                      <span className="inline-block h-1.5 w-1.5 rounded-full bg-current animate-pulse" />
                    )}
                    {/* While we hold the stream, "Live" beats the polled state,
                        which lags by up to one poll — except during synthesis,
                        where the specific label is more informative. */}
                    {conv.id === streamingId && conv.state !== "SYNTHESIZING"
                      ? t("Workforce.board.liveNow", "Live")
                      : t(getStateI18nKey(conv.state), badgeConfig.label)}
                  </Badge>
                  {timestamp > 0 && (
                    <span className="text-xs text-muted-foreground">
                      {formatRelativeTime(timestamp)}
                    </span>
                  )}
                </div>
              </button>
            );
          })}
      </div>
    </div>
  );
}

export { SessionHistory };
export type { SessionHistoryProps };
