import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Activity, AlertTriangle, CircleAlert, Info, Pause, Play } from "lucide-react";
import type { ActivityEvent } from "@/lib/api/cluster";
import { ACTIVITY_GROUPS, activityGroup, activityGroupLabel, activityTitle, type ActivityGroup } from "@/lib/cluster-labels";
import type { ClusterActivityState } from "@/hooks/use-cluster";
import { Button } from "@/components/ui/button";
import { StreamBadge } from "@/components/ui/stream-badge";
import { EmptyState } from "@/components/shared/empty-state";
import { RefetchErrorNotice } from "@/components/shared/refetch-error-notice";
import { cn } from "@/lib/utils";

function SeverityIcon({ severity }: { severity: ActivityEvent["severity"] }) {
  const { t } = useTranslation();
  if (severity === "error") return <CircleAlert className="h-4 w-4 shrink-0 text-destructive" aria-label={t("cluster.activity.error", "Error")} />;
  if (severity === "warning") return <AlertTriangle className="h-4 w-4 shrink-0 text-warning" aria-label={t("cluster.activity.warning", "Warning")} />;
  return <Info className="h-4 w-4 shrink-0 text-muted-foreground" aria-label={t("cluster.activity.info", "Info")} />;
}

/**
 * "What happened?" — the cluster-wide timeline, live. Filter by kind and node;
 * pause to read without the list moving (nothing is lost meanwhile).
 */
export function ClusterActivityFeed({ activity, compact = false }: { activity: ClusterActivityState; compact?: boolean }) {
  const { t } = useTranslation();
  const [groups, setGroups] = useState<Set<ActivityGroup>>(new Set(ACTIVITY_GROUPS));
  const [node, setNode] = useState("");
  const [onlyProblems, setOnlyProblems] = useState(false);

  const nodes = useMemo(() => [...new Set(activity.events.map((e) => e.node))].sort(), [activity.events]);
  const visible = useMemo(
    () =>
      activity.events.filter(
        (e) => groups.has(activityGroup(e.type)) && (!node || e.node === node) && (!onlyProblems || e.severity !== "info"),
      ),
    [activity.events, groups, node, onlyProblems],
  );
  // The overview's short list leaves out the per-minute cache summaries: on a busy
  // cluster every node records one a minute, and they pushed everything else off it.
  const shown = compact ? visible.filter((e) => activityGroup(e.type) !== "caches").slice(0, 8) : visible;

  const toggleGroup = (g: ActivityGroup) =>
    setGroups((prev) => {
      const next = new Set(prev);
      if (next.has(g)) next.delete(g);
      else next.add(g);
      return next;
    });

  return (
    <section className="rounded-xl border border-border bg-card" aria-labelledby="cluster-activity-heading" data-testid="cluster-activity">
      <div className="flex flex-wrap items-center gap-2 border-b border-border px-5 py-4">
        <Activity className="h-5 w-5 text-primary" aria-hidden="true" />
        <h2 id="cluster-activity-heading" className="text-lg font-semibold text-foreground">
          {compact ? t("cluster.activity.recentTitle", "Recent activity") : t("cluster.activity.title", "Cluster activity")}
        </h2>
        <StreamBadge connected={activity.live} />
        {!compact && (
          <div className="ms-auto flex items-center gap-2">
            {activity.paused && activity.pending > 0 && (
              <span className="text-xs text-muted-foreground" role="status" data-testid="cluster-activity-pending">
                {t("cluster.activity.pending", "{{n}} new while paused", { n: activity.pending })}
              </span>
            )}
            <Button
              size="sm"
              variant="outline"
              onClick={() => activity.setPaused(!activity.paused)}
              aria-pressed={activity.paused}
              data-testid="cluster-activity-pause"
            >
              {activity.paused ? <Play aria-hidden="true" /> : <Pause aria-hidden="true" />}
              {activity.paused ? t("cluster.activity.resume", "Resume") : t("cluster.activity.pause", "Pause")}
            </Button>
          </div>
        )}
      </div>

      {!compact && (
        <div className="flex flex-wrap items-center gap-2 border-b border-border px-5 py-3" role="group" aria-label={t("cluster.dl.filters", "Filters")}>
          {ACTIVITY_GROUPS.map((g) => (
            <button
              key={g}
              type="button"
              onClick={() => toggleGroup(g)}
              aria-pressed={groups.has(g)}
              className={cn(
                "rounded-full border px-3 py-1 text-xs font-medium transition-colors",
                groups.has(g) ? "border-primary/40 bg-primary/10 text-foreground" : "border-border text-muted-foreground line-through",
              )}
              data-testid={`cluster-activity-group-${g}`}
            >
              {activityGroupLabel(t, g)}
            </button>
          ))}
          <select
            className="h-8 rounded-lg border border-input bg-background px-2 text-xs text-foreground"
            value={node}
            onChange={(e) => setNode(e.target.value)}
            aria-label={t("cluster.dl.filterNode", "Node")}
            data-testid="cluster-activity-node"
          >
            <option value="">{t("cluster.dl.anyNode", "Any node")}</option>
            {nodes.map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
          <label className="inline-flex items-center gap-1.5 text-xs text-foreground">
            <input type="checkbox" checked={onlyProblems} onChange={(e) => setOnlyProblems(e.target.checked)} data-testid="cluster-activity-problems" />
            {t("cluster.activity.onlyProblems", "Only warnings and errors")}
          </label>
        </div>
      )}

      {activity.error && activity.events.length === 0 ? (
        <div className="p-5">
          <RefetchErrorNotice message={t("cluster.activity.loadError", "The activity history could not be loaded.")} onRetry={activity.refresh} />
        </div>
      ) : shown.length === 0 ? (
        <div className="p-5">
          <EmptyState icon={Activity} title={t("cluster.activity.empty", "Nothing has happened yet")} />
        </div>
      ) : (
        <ol className={cn("divide-y divide-border/50", compact ? "" : "max-h-[32rem] overflow-y-auto")} aria-live={activity.paused ? "off" : "polite"} data-testid="cluster-activity-list">
          {shown.map((e) => (
            <li key={e.id} className="flex items-start gap-3 px-5 py-2.5 text-sm" data-testid={`cluster-activity-${e.type}`} data-severity={e.severity}>
              <SeverityIcon severity={e.severity} />
              <div className="min-w-0 flex-1">
                <p className="text-foreground">{activityTitle(t, e)}</p>
                <p className="text-xs text-muted-foreground">
                  <time dateTime={new Date(e.ts).toISOString()}>{new Date(e.ts).toLocaleTimeString()}</time> · {t("cluster.activity.seenBy", "seen by {{node}}", { node: e.node })}
                </p>
              </div>
            </li>
          ))}
        </ol>
      )}
      {activity.exhausted && (
        <p className="border-t border-border px-5 py-2 text-xs text-muted-foreground" data-testid="cluster-activity-offline">
          {t("cluster.activity.offline", "The live feed is not connected; the list refreshes when you reopen the tab.")}
        </p>
      )}
    </section>
  );
}
