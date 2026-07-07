import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { cn, hashColor, getInitials, formatRelativeTime } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import {
  type DiscussionStyle,
  type GroupMember,
  STYLE_INFO,
} from "@/lib/api/groups";

// ─── Style color map ─────────────────────────────────────────────

const STYLE_COLORS: Record<string, string> = {
  ROUND_TABLE: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  DEBATE: "bg-indigo-500/10 text-indigo-600 dark:text-indigo-400",
  TASK_FORCE: "bg-orange-500/10 text-orange-600 dark:text-orange-400",
  DEVIL_ADVOCATE: "bg-rose-500/10 text-rose-600 dark:text-rose-400",
  PEER_REVIEW: "bg-teal-500/10 text-teal-600 dark:text-teal-400",
  DELPHI: "bg-violet-500/10 text-violet-600 dark:text-violet-400",
  CUSTOM: "bg-slate-500/10 text-slate-600 dark:text-slate-400",
};

// ─── Props ───────────────────────────────────────────────────────

interface BoardroomCardProps {
  id: string;
  name: string;
  description?: string;
  style?: DiscussionStyle;
  members?: GroupMember[];
  lastModified?: number;
  conversationCount?: number;
  version?: number;
  className?: string;
}

// ─── Stacked Avatars (inline, not exported) ──────────────────────

const MAX_VISIBLE = 4;

function StackedAvatarsInline({
  members,
}: {
  members: GroupMember[];
}) {
  const visible = members.slice(0, MAX_VISIBLE);
  const overflow = members.length - MAX_VISIBLE;

  return (
    <div className="flex items-center">
      {visible.map((member, i) => (
        <div
          key={member.agentId}
          className={cn(
            "flex h-7 w-7 items-center justify-center rounded-full text-[10px] font-medium text-white ring-2 ring-white dark:ring-slate-900",
            hashColor(member.agentId),
            i > 0 && "-ms-2",
          )}
          title={member.displayName}
        >
          {getInitials(member.displayName)}
        </div>
      ))}
      {overflow > 0 && (
        <div
          className={cn(
            "-ms-2 flex h-7 w-7 items-center justify-center rounded-full bg-slate-200 text-[10px] font-medium text-slate-600 ring-2 ring-white dark:bg-slate-700 dark:text-slate-300 dark:ring-slate-900",
          )}
        >
          +{overflow}
        </div>
      )}
    </div>
  );
}

// ─── Card Component ──────────────────────────────────────────────

function BoardroomCard({
  id,
  name,
  description,
  style,
  members = [],
  lastModified,
  // conversationCount reserved for future use
  version,
  className,
}: BoardroomCardProps) {
  const { t } = useTranslation();

  const styleInfo = style ? STYLE_INFO[style] : null;
  const styleColor =
    STYLE_COLORS[style ?? ""] ?? STYLE_COLORS.CUSTOM!;

  return (
    <Link
      to={`/boardroom/${id}?version=${version ?? 1}`}
      className={cn(
        "block rounded-xl border p-5 transition-all duration-150",
        "bg-white border-slate-200 hover:shadow-md hover:-translate-y-0.5",
        "dark:bg-slate-900 dark:border-slate-800 dark:hover:border-slate-700 dark:hover:shadow-lg",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
        className,
      )}
    >
      {/* Header row */}
      <div className="flex items-start gap-2">
        {styleInfo && (
          <span className="shrink-0 text-lg" aria-hidden="true">
            {styleInfo.icon}
          </span>
        )}
        <h3 className="min-w-0 flex-1 text-base font-semibold text-foreground line-clamp-1">
          {name || t("boardroom.card.untitled", "Untitled Board")}
        </h3>
        {/* Placeholder for future context menu */}
        <div className="h-5 w-5 shrink-0" />
      </div>

      {/* Description */}
      {description && (
        <p className="mt-1.5 text-sm text-slate-500 dark:text-slate-400 line-clamp-2">
          {description}
        </p>
      )}

      {/* Style badge */}
      {styleInfo && (
        <div className="mt-3">
          <Badge
            variant="secondary"
            className={cn("border-transparent", styleColor)}
          >
            {styleInfo.label}
          </Badge>
        </div>
      )}

      {/* Footer */}
      <div className="mt-4 flex items-center justify-between">
        {/* Start — stacked avatars */}
        <div className="flex items-center gap-2">
          {members.length > 0 ? (
            <StackedAvatarsInline members={members} />
          ) : (
            <span className="text-xs text-slate-400">
              {t("boardroom.card.noMembers", "No advisors")}
            </span>
          )}
        </div>

        {/* End — meta */}
        <div className="flex items-center gap-3 text-xs text-slate-400">
          <span>
            {t("boardroom.card.advisorCount", "{{count}} advisors", {
              count: members.length,
            })}
          </span>
          {lastModified && (
            <span>{formatRelativeTime(lastModified)}</span>
          )}
        </div>
      </div>
    </Link>
  );
}

export { BoardroomCard };
export type { BoardroomCardProps };
