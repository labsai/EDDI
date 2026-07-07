import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import { AdvisorAvatar } from "@/components/boardroom/advisor-avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

// ─── Types ───────────────────────────────────────────────────────

interface MembersSheetProps {
  members: Array<{ agentId: string; displayName: string; role?: string | null }>;
  boardId: string;
  moderatorId?: string | null;
  onClose?: () => void;
  className?: string;
}

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

// ─── Component ───────────────────────────────────────────────────

function MembersSheet({
  members,
  boardId,
  moderatorId,
  onClose,
  className,
}: MembersSheetProps) {
  const { t } = useTranslation();

  return (
    <div className={cn("flex flex-col h-full", className)}>
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-800">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
            {t("boardroom.board.members", "Members")}
          </h3>
          <Badge variant="secondary" className="text-[10px]">
            {members.length}
          </Badge>
        </div>
        {onClose && (
          <Button variant="ghost" size="icon" onClick={onClose} className="h-8 w-8">
            <CloseIcon />
          </Button>
        )}
      </div>

      {/* Member list */}
      <div className="flex-1 overflow-y-auto">
        {members.map((member) => {
          const isModerator = member.agentId === moderatorId;

          return (
            <div
              key={member.agentId}
              className={cn(
                "flex items-center gap-3 px-4 py-3",
                "border-b border-slate-100 dark:border-slate-800/50",
                "hover:bg-slate-50 dark:hover:bg-slate-800/30",
                "transition-colors",
              )}
            >
              {/* Avatar */}
              <AdvisorAvatar
                name={member.displayName}
                agentId={member.agentId}
                size="md"
              />

              {/* Info */}
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-1.5">
                  <span className="font-medium text-sm text-slate-900 dark:text-slate-100 truncate">
                    {member.displayName}
                  </span>
                  {isModerator && (
                    <span
                      className="text-xs"
                      title={t("boardroom.board.moderator", "Moderator")}
                    >
                      ⭐
                    </span>
                  )}
                </div>
                {member.role && (
                  <Badge variant="secondary" className="mt-0.5 text-[10px]">
                    {member.role}
                  </Badge>
                )}
              </div>

              {/* 1:1 chat link */}
              <Button
                variant="ghost"
                size="sm"
                className="text-indigo-500 shrink-0"
                asChild
              >
                <Link to={`/boardroom/${boardId}/thread/${member.agentId}`}>
                  {t("boardroom.board.chatOneOnOne", "Chat 1:1")}
                </Link>
              </Button>
            </div>
          );
        })}

        {members.length === 0 && (
          <div className="p-8 text-center">
            <p className="text-sm text-slate-400 dark:text-slate-500">
              {t("boardroom.board.noMembers", "No members")}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}

export { MembersSheet };
export type { MembersSheetProps };
