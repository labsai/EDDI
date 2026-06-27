import { useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Users, Bot, Copy, Trash2, MoreVertical, ExternalLink } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn, formatRelativeTime } from "@/lib/utils";
import { STYLE_INFO, type DiscussionStyle } from "@/lib/api/groups";

interface GroupCardProps {
  group: {
    id: string;
    version: number;
    name: string;
    description: string;
    lastModifiedOn: number;
  };
  memberCount?: number;
  members?: { displayName: string; memberType?: string }[];
  style?: DiscussionStyle;
  onDuplicate?: (id: string, version: number) => void;
  onDelete?: (id: string, version: number) => void;
}

export function GroupCard({
  group,
  memberCount = 0,
  members = [],
  style,
  onDuplicate,
  onDelete,
}: GroupCardProps) {
  const { t } = useTranslation();
  const [menuOpen, setMenuOpen] = useState(false);
  const styleInfo = style ? STYLE_INFO[style] : null;
  const timeAgo = formatRelativeTime(group.lastModifiedOn);
  const effectiveMemberCount = members.length > 0 ? members.length : memberCount;

  return (
    <div
      className={cn(
        "group relative flex flex-col rounded-xl border bg-card p-5 shadow-sm transition-all duration-200",
        "hover:shadow-md hover:border-primary/30",
        "ring-1 ring-border"
      )}
      data-testid={`group-card-${group.id}`}
    >
      {/* Style badge + menu */}
      <div className="flex items-start justify-between">
        {styleInfo ? (
          <div
            className="inline-flex items-center gap-1.5 rounded-full bg-primary/10 px-2.5 py-1 text-xs font-medium text-primary ring-1 ring-primary/20"
            title={styleInfo.flow}
          >
            <span>{styleInfo.icon}</span>
            {styleInfo.label}
          </div>
        ) : (
          <div className="inline-flex items-center gap-1.5 rounded-full bg-muted px-2.5 py-1 text-xs font-medium text-muted-foreground ring-1 ring-border">
            <Users className="h-3.5 w-3.5" />
            {t("groups.defaultLabel", "Group")}
          </div>
        )}

        {/* Context menu */}
        <div className="relative">
          <button
            onClick={() => setMenuOpen(!menuOpen)}
            className="rounded-md p-1 text-muted-foreground opacity-0 transition-opacity hover:bg-secondary hover:text-foreground group-hover:opacity-100"
            aria-label={t("common.actions", "Actions")}
            data-testid={`group-menu-${group.id}`}
          >
            <MoreVertical className="h-4 w-4" />
          </button>
          {menuOpen && (
            <>
              <div
                className="fixed inset-0 z-40"
                onClick={() => setMenuOpen(false)}
              />
              <div className="absolute inset-e-0 z-50 mt-1 w-44 rounded-lg border bg-popover py-1 shadow-lg">
                {onDuplicate && (
                  <button
                    onClick={() => {
                      onDuplicate(group.id, group.version);
                      setMenuOpen(false);
                    }}
                    className="flex w-full items-center gap-2 px-3 py-2 text-sm text-popover-foreground hover:bg-secondary"
                  >
                    <Copy className="h-4 w-4" />
                    {t("common.duplicate", "Duplicate")}
                  </button>
                )}
                {onDelete && (
                  <button
                    onClick={() => {
                      onDelete(group.id, group.version);
                      setMenuOpen(false);
                    }}
                    className="flex w-full items-center gap-2 px-3 py-2 text-sm text-destructive hover:bg-destructive/10"
                  >
                    <Trash2 className="h-4 w-4" />
                    {t("common.delete")}
                  </button>
                )}
              </div>
            </>
          )}
        </div>
      </div>

      {/* Group info */}
      <div className="mt-4 flex-1">
        <Link
          to={`/manage/groups/${group.id}?version=${group.version}`}
          className="text-lg font-semibold text-foreground hover:text-primary transition-colors"
        >
          {group.name || t("groups.unnamed", "Unnamed Group")}
          <ExternalLink className="ms-1 inline h-3.5 w-3.5 opacity-0 group-hover:opacity-50" />
        </Link>
        <p className="mt-0.5 font-mono text-xs text-muted-foreground/70 truncate" title={group.id}>
          {group.id}
        </p>
        <p className="mt-1 line-clamp-2 text-sm text-muted-foreground" title={group.description || undefined}>
          {group.description || t("groups.noDescription", "No description")}
        </p>

        {/* Member preview */}
        {members && members.length > 0 && (
          <div
            className="mt-2 flex flex-wrap items-center gap-1"
            title={members.map(m => m.displayName).join(", ")}
            role="list"
            aria-label={t("groups.membersColumn", "Members")}
            data-testid={`group-card-members-${group.id}`}
          >
            {members.slice(0, 4).map((m, i) => (
              <span
                key={`${m.displayName}-${i}`}
                className="inline-flex items-center gap-1 rounded-full bg-secondary/80 px-2 py-0.5 text-[10px] font-medium text-muted-foreground ring-1 ring-border"
                role="listitem"
                aria-label={m.displayName}
              >
                {m.memberType === "GROUP" ? (
                  <Users className="h-2.5 w-2.5" aria-hidden="true" />
                ) : (
                  <Bot className="h-2.5 w-2.5" aria-hidden="true" />
                )}
                <span className="max-w-[6rem] truncate" title={m.displayName}>{m.displayName}</span>
              </span>
            ))}
            {members.length > 4 && (
              <span className="text-[10px] text-muted-foreground">
                {t("groups.memberOverflow", "+{{count}} more", { count: members.length - 4 })}
              </span>
            )}
          </div>
        )}
      </div>

      {/* Footer: meta + badges */}
      <div className="mt-4 flex items-center justify-between border-t border-border pt-3">
        <span className="text-xs text-muted-foreground" title={new Date(group.lastModifiedOn).toLocaleString()}>
          {timeAgo}
        </span>

        <div className="flex items-center gap-2">
          <Badge variant="secondary" className="text-[10px]">
            <Users className="me-0.5 h-3 w-3" />
            {effectiveMemberCount}
          </Badge>
          <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
            v{group.version}
          </span>
        </div>
      </div>
    </div>
  );
}
