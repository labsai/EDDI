import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { cn } from "@/lib/utils";
import type { RecentDiscussion } from "@/hooks/use-boardroom-analytics";
import type { GroupConversationState } from "@/lib/api/groups";

interface TopDiscussionsProps {
  discussions: RecentDiscussion[];
}

const BADGE_CLASSES: Record<GroupConversationState, string> = {
  COMPLETED: "bg-primary/10 text-primary",
  FAILED: "bg-destructive/10 text-destructive",
  IN_PROGRESS: "bg-muted text-muted-foreground",
  SYNTHESIZING: "bg-muted text-muted-foreground",
  CREATED: "bg-muted text-muted-foreground",
  CANCELLED: "bg-muted text-muted-foreground",
  AWAITING_APPROVAL: "bg-muted text-muted-foreground",
};

function formatRelative(dateStr: string): string {
  const now = Date.now();
  const then = new Date(dateStr).getTime();
  const diff = now - then;
  if (!Number.isFinite(diff) || diff < 0) return "—";
  const seconds = Math.floor(diff / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  const days = Math.floor(hours / 24);

  if (days > 0) return `${days}d ago`;
  if (hours > 0) return `${hours}h ago`;
  if (minutes > 0) return `${minutes}m ago`;
  return "just now";
}

function TopDiscussions({ discussions }: TopDiscussionsProps) {
  const { t } = useTranslation();

  return (
    <div className="rounded-xl border border-border bg-card p-5 br-card-premium">
      <h3 className="mb-4 text-sm font-semibold">
        {t("analyticsPage.recentDiscussions", "Recent Discussions")}
      </h3>

      {discussions.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          {t("analyticsPage.noDiscussions", "No discussions yet.")}
        </p>
      ) : (
        <div className="space-y-2">
          {discussions.map((d) => (
            <Link
              key={d.id}
              to={`/boardroom/${d.groupId}/history`}
              className={cn(
                "flex items-start gap-3 rounded-lg p-2.5",
                "transition-colors hover:bg-muted/50",
              )}
            >
              <div className="min-w-0 flex-1">
                <p className="line-clamp-2 text-sm font-medium leading-snug">
                  {d.question}
                </p>
                <p className="mt-0.5 text-xs text-muted-foreground">
                  {d.groupName} · {formatRelative(d.created)}
                </p>
              </div>
              <span
                className={cn(
                  "shrink-0 rounded-md px-2 py-0.5 text-xs font-medium",
                  BADGE_CLASSES[d.state] ?? "bg-muted text-muted-foreground",
                )}
              >
                {d.state}
              </span>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}

export { TopDiscussions };
export type { TopDiscussionsProps };
