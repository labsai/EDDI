import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { MoreVertical, Settings, History, Copy, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { cn, hashColor, getInitials, formatRelativeTime } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { useDuplicateGroup, useDeleteGroup } from "@/hooks/use-groups";
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
  viewMode?: "grid" | "list";
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
  viewMode = "grid",
  className,
}: BoardroomCardProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [deleteOpen, setDeleteOpen] = useState(false);

  const duplicateGroup = useDuplicateGroup();
  const deleteGroup = useDeleteGroup();

  const styleInfo = style ? STYLE_INFO[style] : null;
  const styleColor =
    STYLE_COLORS[style ?? ""] ?? STYLE_COLORS.CUSTOM!;

  const currentVersion = version ?? 1;

  function handleDuplicate() {
    duplicateGroup.mutate(
      { id, version: currentVersion },
      {
        onSuccess: () =>
          toast.success(
            t("boardroom.dashboard.duplicateSuccess", "Boardroom duplicated"),
          ),
        onError: () =>
          toast.error(
            t("boardroom.dashboard.duplicateError", "Failed to duplicate boardroom"),
          ),
      },
    );
  }

  function handleDelete() {
    deleteGroup.mutate(
      { id, version: currentVersion, permanent: true },
      {
        onSuccess: () => {
          toast.success(
            t("boardroom.dashboard.deleteSuccess", "Boardroom deleted"),
          );
          setDeleteOpen(false);
        },
        onError: () =>
          toast.error(
            t("boardroom.dashboard.deleteError", "Failed to delete boardroom"),
          ),
      },
    );
  }

  const isList = viewMode === "list";

  return (
    <>
      <Link
        to={`/boardroom/${id}?version=${currentVersion}`}
        className={cn(
          "block rounded-xl border p-5 transition-all duration-150",
          "bg-white border-slate-200 hover:shadow-md hover:-translate-y-0.5",
          "dark:bg-slate-900 dark:border-slate-800 dark:hover:border-slate-700 dark:hover:shadow-lg",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
          isList && "flex items-center gap-4",
          className,
        )}
      >
        {/* Grid layout */}
        {!isList && (
          <>
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
              {/* Quick actions dropdown */}
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <button
                    className="shrink-0 rounded-md p-1 text-muted-foreground opacity-0 transition-opacity hover:bg-secondary hover:text-foreground group-hover:opacity-100 focus:opacity-100 focus-visible:opacity-100"
                    onClick={(e) => {
                      e.preventDefault();
                      e.stopPropagation();
                    }}
                    aria-label={t("boardroom.card.moreActions", "More actions")}
                    data-testid={`boardroom-menu-${id}`}
                  >
                    <MoreVertical className="h-5 w-5" />
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="w-48" onClick={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                }}>
                  <DropdownMenuItem
                    onClick={(e) => {
                      e.stopPropagation();
                      navigate(`/boardroom/${id}/settings?version=${currentVersion}`);
                    }}
                  >
                    <Settings className="h-4 w-4" />
                    {t("boardroom.card.settings", "Settings")}
                  </DropdownMenuItem>
                  <DropdownMenuItem
                    onClick={(e) => {
                      e.stopPropagation();
                      navigate(`/boardroom/${id}/history`);
                    }}
                  >
                    <History className="h-4 w-4" />
                    {t("boardroom.card.history", "History")}
                  </DropdownMenuItem>
                  <DropdownMenuItem
                    onClick={(e) => {
                      e.stopPropagation();
                      handleDuplicate();
                    }}
                  >
                    <Copy className="h-4 w-4" />
                    {t("boardroom.card.duplicate", "Duplicate")}
                  </DropdownMenuItem>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem
                    className="text-destructive focus:text-destructive"
                    onClick={(e) => {
                      e.stopPropagation();
                      setDeleteOpen(true);
                    }}
                  >
                    <Trash2 className="h-4 w-4" />
                    {t("boardroom.card.delete", "Delete")}
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
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
          </>
        )}

        {/* List layout */}
        {isList && (
          <>
            {styleInfo && (
              <span className="shrink-0 text-lg" aria-hidden="true">
                {styleInfo.icon}
              </span>
            )}
            <div className="min-w-0 flex-1">
              <h3 className="text-sm font-semibold text-foreground line-clamp-1">
                {name || t("boardroom.card.untitled", "Untitled Board")}
              </h3>
              {description && (
                <p className="mt-0.5 text-xs text-slate-500 dark:text-slate-400 line-clamp-1">
                  {description}
                </p>
              )}
            </div>

            {/* Style badge */}
            {styleInfo && (
              <Badge
                variant="secondary"
                className={cn("border-transparent shrink-0", styleColor)}
              >
                {styleInfo.label}
              </Badge>
            )}

            {/* Avatars */}
            <div className="hidden sm:flex items-center shrink-0">
              {members.length > 0 ? (
                <StackedAvatarsInline members={members} />
              ) : (
                <span className="text-xs text-slate-400">
                  {t("boardroom.card.noMembers", "No advisors")}
                </span>
              )}
            </div>

            {/* Meta */}
            <div className="hidden md:flex items-center gap-3 text-xs text-slate-400 shrink-0">
              <span>
                {t("boardroom.card.advisorCount", "{{count}} advisors", {
                  count: members.length,
                })}
              </span>
              {lastModified && (
                <span>{formatRelativeTime(lastModified)}</span>
              )}
            </div>

            {/* Actions */}
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  className="shrink-0 rounded-md p-1 text-muted-foreground hover:bg-secondary hover:text-foreground"
                  onClick={(e) => {
                    e.preventDefault();
                    e.stopPropagation();
                  }}
                  aria-label={t("boardroom.card.moreActions", "More actions")}
                  data-testid={`boardroom-menu-${id}`}
                >
                  <MoreVertical className="h-5 w-5" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-48" onClick={(e) => {
                e.preventDefault();
                e.stopPropagation();
              }}>
                <DropdownMenuItem
                  onClick={(e) => {
                    e.stopPropagation();
                    navigate(`/boardroom/${id}/settings?version=${currentVersion}`);
                  }}
                >
                  <Settings className="h-4 w-4" />
                  {t("boardroom.card.settings", "Settings")}
                </DropdownMenuItem>
                <DropdownMenuItem
                  onClick={(e) => {
                    e.stopPropagation();
                    navigate(`/boardroom/${id}/history`);
                  }}
                >
                  <History className="h-4 w-4" />
                  {t("boardroom.card.history", "History")}
                </DropdownMenuItem>
                <DropdownMenuItem
                  onClick={(e) => {
                    e.stopPropagation();
                    handleDuplicate();
                  }}
                >
                  <Copy className="h-4 w-4" />
                  {t("boardroom.card.duplicate", "Duplicate")}
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem
                  className="text-destructive focus:text-destructive"
                  onClick={(e) => {
                    e.stopPropagation();
                    setDeleteOpen(true);
                  }}
                >
                  <Trash2 className="h-4 w-4" />
                  {t("boardroom.card.delete", "Delete")}
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </>
        )}
      </Link>

      {/* Delete confirmation dialog */}
      <AlertDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        title={t("boardroom.dashboard.deleteBoardroom", "Delete Boardroom")}
        description={t(
          "boardroom.dashboard.deleteConfirm",
          "Are you sure you want to delete this boardroom? This action cannot be undone.",
        )}
        confirmLabel={t("common.delete", "Delete")}
        cancelLabel={t("common.cancel", "Cancel")}
        onConfirm={handleDelete}
        variant="destructive"
        isPending={deleteGroup.isPending}
      />
    </>
  );
}

export { BoardroomCard };
export type { BoardroomCardProps };
