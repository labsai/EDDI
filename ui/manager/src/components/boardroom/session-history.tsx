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

function getStateI18nKey(state: GroupConversationState): string {
  switch (state) {
    case "COMPLETED": return "boardroom.board.completed";
    case "IN_PROGRESS": return "boardroom.board.inProgress";
    case "SYNTHESIZING": return "boardroom.board.synthesizing";
    case "CREATED": return "boardroom.board.created";
    case "FAILED": return "boardroom.board.failed";
    case "CANCELLED": return "boardroom.board.cancelled";
    case "AWAITING_APPROVAL": return "boardroom.board.awaitingApproval";
    default: return "boardroom.board.created";
  }
}

// ─── Component ───────────────────────────────────────────────────

function SessionHistory({
  groupId,
  selectedId,
  onSelect,
  onClose,
  className,
}: SessionHistoryProps) {
  const { t } = useTranslation();
  const { data: conversations, isLoading } = useGroupConversations(groupId);

  return (
    <div className={cn("flex flex-col h-full", className)}>
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-800">
        <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
          {t("boardroom.board.sessions", "Sessions")}
        </h3>
        {onClose && (
          <Button variant="ghost" size="icon" onClick={onClose} className="h-8 w-8" aria-label={t("boardroom.board.close", "Close")}>
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
            <p className="text-sm text-slate-400 dark:text-slate-500">
              {t("boardroom.board.noSessions", "No sessions yet")}
            </p>
          </div>
        )}

        {!isLoading &&
          conversations?.map((conv) => {
            const isSelected = conv.id === selectedId;
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
                  "w-full text-start px-4 py-3 transition-colors",
                  "hover:bg-slate-50 dark:hover:bg-slate-800/50",
                  "border-b border-slate-100 dark:border-slate-800/50",
                  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-indigo-500",
                  isSelected && "bg-indigo-50 dark:bg-indigo-500/10 border-s-2 border-s-indigo-500",
                )}
              >
                {/* Question text */}
                <p className="text-sm text-slate-700 dark:text-slate-300 line-clamp-1">
                  {conv.originalQuestion || t("boardroom.board.untitled", "Untitled")}
                </p>

                {/* Bottom row: badge + timestamp */}
                <div className="flex items-center gap-2 mt-1.5">
                  <Badge variant={badgeConfig.variant} className="text-[10px]">
                    {t(getStateI18nKey(conv.state), badgeConfig.label)}
                  </Badge>
                  {timestamp > 0 && (
                    <span className="text-xs text-slate-400 dark:text-slate-500">
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
