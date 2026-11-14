import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { CloudAlert, HeartPulse } from "lucide-react";
import type { HealthPeriod, HealthStatus, PortfolioItem, PortfolioSort } from "@/components/health/api";
import { HEALTH_PERIODS } from "@/components/health/api";
import { useHealthPortfolio } from "@/components/health/use-agent-health";
import { Button, Skeleton, cn, getErrorMessage } from "@/components/health/deps";
import { BandBadge, StatusBadge } from "@/components/health/band-badge";
import { formatDays, formatDelta, formatMeasureValue, formatShortDate, formatSignedPercent, measureLabel, periodDays, selectClass, STATUS_META } from "@/components/health/format";

const STATUS_ORDER: HealthStatus[] = ["NEEDS_ATTENTION", "WATCH", "HEALTHY", "NOT_ENOUGH_DATA"];
const SORTS: PortfolioSort[] = ["attention", "name", "traffic"];
const SORT_LABEL: Record<PortfolioSort, [string, string]> = {
  attention: ["health.overview.sortAttention", "Needs me first"],
  name: ["health.overview.sortName", "Name"],
  traffic: ["health.overview.sortTraffic", "Traffic"],
};

/** One agent of the portfolio: name (links to its Health panel), status, sessions with their change, top reason and key measures. Flags a platform incident so a fault outside the agent is not read as the agent's. */
function Row({ item }: { item: PortfolioItem }) {
  const { t } = useTranslation();
  return (
    <li className="rounded-xl border border-border bg-card p-4" data-testid="health-overview-row" data-agent-id={item.agentId}>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <Link to={`/manage/agentview/${encodeURIComponent(item.agentId)}#health`} className="text-base font-semibold text-foreground hover:underline">
          {item.agentName}
        </Link>
        {item.status !== null ? (
          <StatusBadge status={item.status} />
        ) : (
          <span className="text-xs text-muted-foreground" data-testid="health-row-no-status">{t("health.overview.noStatus", "Status not available yet")}</span>
        )}
        {item.platformIncident && (
          <span className="inline-flex items-center gap-1 rounded-full border border-border bg-muted px-2 py-0.5 text-xs text-muted-foreground" data-testid="health-row-incident">
            <CloudAlert className="h-3 w-3" aria-hidden="true" />
            {t("health.overview.affected", "Affected by a platform incident")}
          </span>
        )}
        <span className="ms-auto text-xs text-muted-foreground tabular-nums" data-testid="health-row-sessions">
          {item.sessions === null || item.sessions === undefined ? (
            // Uncounted is not zero: the period reaches past the turn-row retention.
            <span title={item.hint ?? undefined} data-testid="health-row-sessions-unknown">—</span>
          ) : (
            t("health.count.sessions", { count: item.sessions, defaultValue: "{{count}} sessions" })
          )}
          {item.sessionsDelta !== null && item.sessionsDelta !== undefined && <span> ({formatSignedPercent(item.sessionsDelta)})</span>}
        </span>
      </div>
      {item.hint && <p className="mt-1 text-xs text-muted-foreground" data-testid="health-row-hint">{item.hint}</p>}
      {item.topReason && <p className="mt-1 text-sm text-foreground" data-testid="health-row-reason">{item.topReason.text}</p>}
      {item.keyMeasures.length > 0 && (
        <ul className="mt-2 grid gap-2 sm:grid-cols-3">
          {item.keyMeasures.slice(0, 3).map((m) => (
            <li key={m.id} className="rounded-lg bg-background/60 p-2 text-xs" data-testid="health-row-measure">
              <div className="flex items-center justify-between gap-2">
                <span className="truncate text-muted-foreground">{measureLabel(t, m)}</span>
                {m.kind !== "INFORMATIONAL" && <BandBadge band={m.band} />}
              </div>
              <div className="mt-0.5 flex items-baseline gap-2">
                <span className="text-base font-semibold tabular-nums">{formatMeasureValue(m.value, m.unit)}</span>
                {m.value !== null && m.delta !== null && m.delta !== undefined && (
                  <span className="text-muted-foreground tabular-nums">{formatDelta(m.delta, m.unit, t("health.unit.pp", "pp"))}</span>
                )}
              </div>
              {m.hint && <p className="mt-0.5 text-muted-foreground" data-testid="health-row-measure-hint">{m.hint}</p>}
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}

/** `/manage/health`: every agent the caller may view, needs-me-first. */
export function HealthOverviewPage() {
  const { t } = useTranslation();
  const [period, setPeriod] = useState<HealthPeriod>("7d");
  const [sort, setSort] = useState<PortfolioSort>("attention");
  const [status, setStatus] = useState<HealthStatus[]>([]);
  const portfolio = useHealthPortfolio(period, sort, status);
  // The unfiltered list (the same request as `portfolio` while no status is chosen) is where the
  // backend says whether statuses are calculated: until H4 every agent's `status` is null.
  const unfiltered = useHealthPortfolio(period, sort, []);
  const unfilteredItems = unfiltered.data?.pages.flatMap((p) => p.items) ?? [];
  const statusesUncalculated = unfilteredItems.length > 0 && unfilteredItems.every((i) => i.status === null);
  /** Adds or removes a status from the filter; none selected shows every agent. */
  const toggleStatus = (s: HealthStatus) => setStatus((cur) => (cur.includes(s) ? cur.filter((c) => c !== s) : [...cur, s]));
  const select = selectClass;
  const pages = portfolio.data?.pages ?? [];
  const items = pages.flatMap((p) => p.items);
  // Incidents are a property of the period, repeated on every page: the first is enough.
  const incidents = pages[0]?.platformIncidents ?? [];

  return (
    <div className="mx-auto max-w-5xl space-y-5" data-testid="health-overview-page">
      <header className="flex flex-wrap items-center gap-3">
        <HeartPulse className="h-6 w-6 text-primary" aria-hidden="true" />
        <h1 className="text-2xl font-semibold">{t("health.overview.title", "Agent health")}</h1>
      </header>

      <div className="flex flex-wrap items-center gap-3">
        <select className={select} value={period} aria-label={t("health.filters.period", "Period")} onChange={(e) => setPeriod(e.target.value as HealthPeriod)} data-testid="health-overview-period">
          {HEALTH_PERIODS.map((p) => (
            <option key={p} value={p}>{formatDays(periodDays(p))}</option>
          ))}
        </select>
        <select className={select} value={sort} aria-label={t("health.overview.sort", "Sort")} onChange={(e) => setSort(e.target.value as PortfolioSort)} data-testid="health-overview-sort">
          {SORTS.map((s) => (
            <option key={s} value={s}>{t(SORT_LABEL[s][0], SORT_LABEL[s][1])}</option>
          ))}
        </select>
        <div role="group" aria-label={t("health.overview.statusFilter", "Status")} className="flex flex-wrap gap-1.5">
          {STATUS_ORDER.map((s) => (
            <button
              key={s}
              type="button"
              aria-pressed={status.includes(s)}
              onClick={() => toggleStatus(s)}
              className={cn(
                "rounded-full border px-2.5 py-1 text-xs font-medium transition-colors",
                status.includes(s) ? STATUS_META[s].classes : "border-input bg-background text-muted-foreground hover:bg-secondary",
              )}
              data-testid={`health-status-filter-${s}`}
            >
              {t(`health.status.${STATUS_META[s].key}`, STATUS_META[s].fallback)}
            </button>
          ))}
        </div>
      </div>

      {incidents.length > 0 && (
        <div className="flex items-start gap-3 rounded-xl border border-warning/40 bg-warning/10 p-4 text-sm" role="status" data-testid="health-platform-incident">
          <CloudAlert className="mt-0.5 h-4 w-4 shrink-0 text-warning" aria-hidden="true" />
          <div className="space-y-1">
            <p className="font-medium text-foreground">{t("health.overview.incidentTitle", "A platform incident touched several agents")}</p>
            <p className="text-muted-foreground">
              {t("health.overview.incidentBody", "These failures come from the model providers or the platform, not from the agents' configuration, so they are not counted against the agents.")}
            </p>
            <ul className="list-disc ps-5 text-xs text-muted-foreground">
              {incidents.map((i) => (
                <li key={`${i.failureClass}-${i.from}`}>
                  {t("health.overview.incident", {
                    failureClass: i.failureClass,
                    count: i.agents,
                    // Incidents are detected per day: `from` and `to` are days, not instants.
                    from: formatShortDate(i.from),
                    to: formatShortDate(i.to),
                    defaultValue: "{{failureClass}}: {{count}} agents, {{from}} to {{to}}",
                  })}
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}

      {portfolio.isLoading && <Skeleton className="h-40 w-full" />}
      {portfolio.isError && <p className="text-sm text-destructive" role="alert">{getErrorMessage(portfolio.error)}</p>}
      {portfolio.data && items.length === 0 && (
        <p className="text-sm text-muted-foreground" data-testid="health-overview-empty">{t("health.overview.empty", "No agents match.")}</p>
      )}
      {portfolio.data && items.length === 0 && status.length > 0 && statusesUncalculated && (
        // Every agent's status is null, which no status filter matches; an empty filter result otherwise is just "no agents match".
        <p className="text-xs text-muted-foreground" data-testid="health-overview-empty-status">
          {t("health.overview.emptyStatus", "Agent statuses are not calculated on this deployment yet, so a status filter matches no agent.")}
        </p>
      )}
      <ul className="space-y-3">
        {items.map((item) => <Row key={item.agentId} item={item} />)}
      </ul>
      {portfolio.hasNextPage && (
        <Button variant="outline" size="sm" disabled={portfolio.isFetchingNextPage} onClick={() => void portfolio.fetchNextPage()} data-testid="health-overview-more">
          {t("health.overview.loadMore", "Load more")}
        </Button>
      )}
    </div>
  );
}
