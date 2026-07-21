import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { cn } from "@/lib/utils";
import type { RecentDiscussion } from "@/hooks/use-workforce-analytics";
import type { GroupConversationState } from "@/lib/api/groups";

interface TopDiscussionsProps {
  discussions: RecentDiscussion[];
  emptyMessage?: string;
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

const STATE_LABELS: Record<GroupConversationState, string> = {
  COMPLETED: "Completed",
  FAILED: "Failed",
  IN_PROGRESS: "In Progress",
  SYNTHESIZING: "Synthesizing",
  CREATED: "Created",
  CANCELLED: "Cancelled",
  AWAITING_APPROVAL: "Pending",
};

function formatRelative(dateStr: string, t: (key: string, fallback: string, opts?: Record<string, unknown>) => string): string {
  const now = Date.now();
  const then = new Date(dateStr).getTime();
  const diff = now - then;
  if (!Number.isFinite(diff) || diff < 0) return "—";
  const seconds = Math.floor(diff / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  const days = Math.floor(hours / 24);

  if (days > 0) return t("analyticsPage.daysAgo", "{{count}}d ago", { count: days });
  if (hours > 0) return t("analyticsPage.hoursAgo", "{{count}}h ago", { count: hours });
  if (minutes > 0) return t("analyticsPage.minutesAgo", "{{count}}m ago", { count: minutes });
  return t("analyticsPage.justNow", "just now");
}

function TopDiscussions({ discussions, emptyMessage }: TopDiscussionsProps) {
  const { t } = useTranslation();

  return (
    <div className="rounded-xl border border-border bg-card p-5 br-card-premium">
      <h3 className="mb-4 text-sm font-semibold">
        {t("analyticsPage.recentDiscussions", "Recent Discussions")}
      </h3>

      {discussions.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          {emptyMessage ?? t("analyticsPage.noDiscussions", "No discussions yet.")}
        </p>
      ) : (
        <div className="space-y-2">
          {discussions.map((d) => (
            <Link
              key={d.id}
              to={`/workforce/${d.groupId}/history`}
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
                  {d.groupName} · {formatRelative(d.created, t)}
                </p>
              </div>
              <span
                className={cn(
                  "shrink-0 rounded-md ps-2 pe-2 py-0.5 text-xs font-medium",
                  BADGE_CLASSES[d.state] ?? "bg-muted text-muted-foreground",
                )}
              >
                {STATE_LABELS[d.state] ?? d.state}
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
