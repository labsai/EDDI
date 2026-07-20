import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { MoreVertical, Settings, History, Copy, Trash2, Star } from "lucide-react";
import { toast } from "sonner";
import { cn, getInitials, formatRelativeTime } from "@/lib/utils";
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
  ROUND_TABLE: "bg-muted text-muted-foreground",
  DEBATE: "bg-muted text-muted-foreground",
  TASK_FORCE: "bg-muted text-muted-foreground",
  DEVIL_ADVOCATE: "bg-muted text-muted-foreground",
  PEER_REVIEW: "bg-muted text-muted-foreground",
  DELPHI: "bg-muted text-muted-foreground",
  CUSTOM: "bg-muted text-muted-foreground",
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
  isPinned?: boolean;
  onTogglePin?: () => void;
  lastConversationState?: string;
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
            "flex h-7 w-7 items-center justify-center rounded-full text-[10px] font-medium bg-muted text-muted-foreground ring-2 ring-card dark:ring-background",
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
            "-ms-2 flex h-7 w-7 items-center justify-center rounded-full bg-muted text-[10px] font-medium text-muted-foreground ring-2 ring-card dark:ring-background",
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
  isPinned,
  onTogglePin,
  lastConversationState,
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
        onSuccess: (data) => {
          // Extract new group ID from the location header (e.g. "/groupstore/groups/{newId}")
          const newId = data.location?.split("/").pop();
          if (newId && newId.length > 0) {
            toast.success(
              t("boardroom.card.duplicated", "Task Force duplicated"),
              {
                action: {
                  label: t("boardroom.card.openSettings", "Open Settings"),
                  onClick: () => navigate(`/workforce/${newId}/settings`),
                },
              },
            );
          } else {
            toast.success(
              t("boardroom.card.duplicated", "Task Force duplicated"),
            );
          }
        },
        onError: () =>
          toast.error(
            t("boardroom.dashboard.duplicateError", "Failed to duplicate task force"),
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
            t("boardroom.dashboard.deleteSuccess", "Task Force deleted"),
          );
          setDeleteOpen(false);
        },
        onError: () =>
          toast.error(
            t("boardroom.dashboard.deleteError", "Failed to delete task force"),
          ),
      },
    );
  }

  const isList = viewMode === "list";

  return (
    <>
      <Link
        to={`/workforce/${id}?version=${currentVersion}`}
        className={cn(
          "block rounded-xl border p-5 transition-all duration-200 br-card-premium",
          "bg-card border-border hover:shadow-lg hover:-translate-y-1",
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
                {name || t("boardroom.card.untitled", "Untitled Task Force")}
              </h3>
              {/* Pin toggle + Quick actions dropdown */}
              <div className="flex items-center gap-1 shrink-0">
              {onTogglePin && (
                <button
                  type="button"
                  onClick={(e) => { e.preventDefault(); e.stopPropagation(); onTogglePin(); }}
                  className={cn(
                    "h-8 w-8 rounded-lg flex items-center justify-center transition-colors",
                    isPinned ? "text-primary" : "text-muted-foreground hover:text-foreground",
                  )}
                  aria-label={isPinned ? t("boardroom.card.unpin", "Unpin") : t("boardroom.card.pin", "Pin")}
                >
                  <Star className={cn("h-4 w-4", isPinned && "fill-primary")} />
                </button>
              )}
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
                      navigate(`/workforce/${id}/settings?version=${currentVersion}`);
                    }}
                  >
                    <Settings className="h-4 w-4" />
                    {t("boardroom.card.settings", "Settings")}
                  </DropdownMenuItem>
                  <DropdownMenuItem
                    onClick={(e) => {
                      e.stopPropagation();
                      navigate(`/workforce/${id}/history`);
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
            </div>

            {/* Description */}
            {description && (
              <p className="mt-1.5 text-sm text-muted-foreground line-clamp-2">
                {description}
              </p>
            )}

            {/* Style badge + Live status */}
            <div className="mt-3 flex items-center gap-2">
              {styleInfo && (
                <Badge
                  variant="secondary"
                  className={cn("border-transparent", styleColor)}
                >
                  {styleInfo.label}
                </Badge>
              )}
              {lastConversationState === "IN_PROGRESS" && (
                <Badge variant="outline" className="text-[10px] border-primary/50 text-primary gap-1">
                  <span className="h-1.5 w-1.5 rounded-full bg-primary animate-pulse" />
                  {t("boardroom.card.live", "Live")}
                </Badge>
              )}
            </div>

            {/* Footer */}
            <div className="mt-4 flex items-center justify-between">
              {/* Start — stacked avatars */}
              <div className="flex items-center gap-2">
                {members.length > 0 ? (
                  <StackedAvatarsInline members={members} />
                ) : (
                  <span className="text-xs text-muted-foreground">
                    {t("boardroom.card.noMembers", "No experts")}
                  </span>
                )}
              </div>

              {/* End — meta */}
              <div className="flex items-center gap-3 text-xs text-muted-foreground">
                <span>
                  {t("boardroom.card.advisorCount", "{{count}} experts", {
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
                {name || t("boardroom.card.untitled", "Untitled Task Force")}
              </h3>
              {description && (
                <p className="mt-0.5 text-xs text-muted-foreground line-clamp-1">
                  {description}
                </p>
              )}
            </div>

            {/* Style badge + Live status */}
            {styleInfo && (
              <Badge
                variant="secondary"
                className={cn("border-transparent shrink-0", styleColor)}
              >
                {styleInfo.label}
              </Badge>
            )}
            {lastConversationState === "IN_PROGRESS" && (
              <Badge variant="outline" className="text-[10px] border-primary/50 text-primary gap-1 shrink-0">
                <span className="h-1.5 w-1.5 rounded-full bg-primary animate-pulse" />
                {t("boardroom.card.live", "Live")}
              </Badge>
            )}

            {/* Avatars */}
            <div className="hidden sm:flex items-center shrink-0">
              {members.length > 0 ? (
                <StackedAvatarsInline members={members} />
              ) : (
                <span className="text-xs text-muted-foreground">
                  {t("boardroom.card.noMembers", "No experts")}
                </span>
              )}
            </div>

            {/* Meta */}
            <div className="hidden md:flex items-center gap-3 text-xs text-muted-foreground shrink-0">
              <span>
                {t("boardroom.card.advisorCount", "{{count}} experts", {
                  count: members.length,
                })}
              </span>
              {lastModified && (
                <span>{formatRelativeTime(lastModified)}</span>
              )}
            </div>

            {/* Pin toggle + Actions */}
            {onTogglePin && (
              <button
                type="button"
                onClick={(e) => { e.preventDefault(); e.stopPropagation(); onTogglePin(); }}
                className={cn(
                  "h-8 w-8 rounded-lg flex items-center justify-center transition-colors shrink-0",
                  isPinned ? "text-primary" : "text-muted-foreground hover:text-foreground",
                )}
                aria-label={isPinned ? t("boardroom.card.unpin", "Unpin") : t("boardroom.card.pin", "Pin")}
              >
                <Star className={cn("h-4 w-4", isPinned && "fill-primary")} />
              </button>
            )}
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
                    navigate(`/workforce/${id}/settings?version=${currentVersion}`);
                  }}
                >
                  <Settings className="h-4 w-4" />
                  {t("boardroom.card.settings", "Settings")}
                </DropdownMenuItem>
                <DropdownMenuItem
                  onClick={(e) => {
                    e.stopPropagation();
                    navigate(`/workforce/${id}/history`);
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
        title={t("boardroom.dashboard.deleteBoardroom", "Dissolve Task Force")}
        description={t(
          "boardroom.dashboard.deleteConfirm",
          "Are you sure you want to dissolve this task force? This action cannot be undone.",
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
