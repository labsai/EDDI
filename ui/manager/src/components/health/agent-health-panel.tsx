import { useEffect, useId, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useLocation } from "react-router-dom";
import { ChevronDown, ChevronRight, GitCompareArrows, HeartPulse, LayoutList, Link2, SlidersHorizontal } from "lucide-react";
import { laterHealthPhasesAvailable, type HealthQuery, type MeasureUnit } from "./api";
import { useAgentHealth } from "./use-agent-health";
import { isApiError, Button, Skeleton, getErrorMessage } from "./deps";
import { ChecksStrip } from "./checks-strip";
import { CompareDialog } from "./compare-dialog";
import type { DrillFilter } from "./drill";
import { ExportButtons } from "./export-buttons";
import { HealthFilters, type HealthFilterState } from "./filters";
import { formatShortDate, measureLabel, orderedSections } from "./format";
import { OutcomeSuggestionsDialog } from "./outcome-suggestions-dialog";
import { ProfileDialog } from "./profile-dialog";
import { ReportLinksDialog } from "./report-links-dialog";
import { SectionCard } from "./section-card";
import { SessionsDialog } from "./sessions-dialog";
import { StatusRing } from "./status-ring";

const OPEN_KEY = "eddi.manager.agentHealthOpen";

/** Reads the remembered open/closed state of the panel from localStorage (`1` = open). Falls back to `defaultOpen` when nothing is stored or storage is blocked (private mode, disabled cookies). */
function readOpen(defaultOpen: boolean): boolean {
  try {
    const v = window.localStorage.getItem(OPEN_KEY);
    return v === null ? defaultOpen : v === "1";
  } catch {
    return defaultOpen;
  }
}

/** Remembers the panel's open/closed state. A blocked or full storage is ignored: the preference is a convenience, not data. */
function writeOpen(open: boolean) {
  try {
    window.localStorage.setItem(OPEN_KEY, open ? "1" : "0");
  } catch {
    // Storage blocked: the section simply opens collapsed next time.
  }
}

type Dialog = "compare" | "profile" | "reports" | "suggestions" | null;

/**
 * Agent detail → Health. Collapsed until opened (nothing is fetched before),
 * then status and top reasons, one card per section, the checks, and the
 * actions: compare versions, edit the profile, owner report links, export.
 * `canEdit` gates the profile and the report links (the backend enforces it
 * too) and the sessions drill-down, which needs EDIT on top of review being
 * enabled; everything else needs only the VIEW that reaching this page implies.
 * The profile, report links and export are hidden until their backend phases
 * land (see `laterHealthPhasesAvailable`).
 */
export function AgentHealthPanel({
  agentId,
  latestVersion,
  canEdit,
  defaultOpen = false,
}: {
  agentId: string;
  latestVersion: number;
  canEdit: boolean;
  defaultOpen?: boolean;
}) {
  const { t } = useTranslation();
  const { hash, key: locationKey } = useLocation();
  const sectionRef = useRef<HTMLElement>(null);
  // `#health` (the overview's link) opens the section and brings it into view.
  const linked = hash === "#health";
  const [open, setOpen] = useState(() => linked || readOpen(defaultOpen));
  // Also when the panel is already mounted and collapsed: navigating to `#health`
  // (again — each navigation has its own location key) reopens it, which enables the
  // query, and scrolls to it. Opening by link is not remembered as a preference.
  useEffect(() => {
    if (!linked) return;
    setOpen(true);
    sectionRef.current?.scrollIntoView?.({ block: "start" });
  }, [linked, locationKey]);
  const [filters, setFilters] = useState<HealthFilterState>({ period: "30d", version: null, origins: [] });
  const [dialog, setDialog] = useState<Dialog>(null);
  const [drill, setDrill] = useState<DrillFilter | null>(null);

  const query: HealthQuery = useMemo(
    () => ({ period: filters.period, version: filters.version, origins: filters.origins }),
    [filters],
  );
  // Nothing is requested while the section is collapsed.
  const health = useAgentHealth(agentId, query, open);
  const data = health.data;

  const info = useMemo(() => {
    const units: Record<string, MeasureUnit> = {};
    const labels: Record<string, string> = {};
    for (const section of Object.values(data?.sections ?? {})) {
      for (const m of section?.measures ?? []) {
        units[m.id] = m.unit;
        labels[m.id] = measureLabel(t, m);
      }
    }
    return { units, labels };
  }, [data, t]);

  /** Flips the panel and persists the choice. Nothing is fetched while it is collapsed. */
  const toggle = () => {
    writeOpen(!open);
    setOpen(!open);
  };
  const Chevron = open ? ChevronDown : ChevronRight;
  const forbidden = health.isError && isApiError(health.error) && health.error.status === 403;
  const canReadQuality = data?.sections.quality ? !data.sections.quality.restricted : false;
  const bodyId = useId();
  // Profile (H4), report links (H4b) and export (H5) have no backend endpoint yet.
  const laterPhases = laterHealthPhasesAvailable();

  return (
    <section ref={sectionRef} id="health" className="rounded-xl border border-border bg-card p-4" data-testid="agent-health-panel">
      <h2 className="text-base font-semibold">
        <button type="button" onClick={toggle} aria-expanded={open} aria-controls={bodyId} className="flex w-full items-center gap-2 text-start" data-testid="agent-health-toggle">
          <Chevron className="h-4 w-4 text-muted-foreground rtl:rotate-180" aria-hidden="true" />
          <HeartPulse className="h-4 w-4 text-primary" aria-hidden="true" />
          {t("health.title", "Health")}
          <span className="text-xs font-normal text-muted-foreground">{t("health.subtitle", "Is this agent doing its job?")}</span>
        </button>
      </h2>

      {open && (
        <div id={bodyId} className="mt-4 space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <HealthFilters value={filters} onChange={setFilters} latestVersion={latestVersion} />
            <div className="flex flex-wrap items-center gap-2">
              <Button size="sm" variant="outline" onClick={() => setDialog("compare")} disabled={latestVersion < 2 || forbidden} data-testid="health-open-compare">
                <GitCompareArrows aria-hidden="true" />
                {t("health.compare.open", "Compare versions")}
              </Button>
              {canEdit && laterPhases && (
                <>
                  <Button size="sm" variant="outline" onClick={() => setDialog("profile")} data-testid="health-open-profile">
                    <SlidersHorizontal aria-hidden="true" />
                    {t("health.profile.open", "Profile")}
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => setDialog("reports")} data-testid="health-open-reports">
                    <Link2 aria-hidden="true" />
                    {t("health.reports.open", "Report links")}
                  </Button>
                </>
              )}
              {laterPhases && <ExportButtons agentId={agentId} period={filters.period} disabled={forbidden} />}
            </div>
          </div>

          {health.isLoading && <Skeleton className="h-40 w-full" />}
          {forbidden && (
            <p className="text-sm text-muted-foreground" role="alert" data-testid="health-forbidden">
              {t("health.forbidden", "You do not have permission to view this agent's health.")}
            </p>
          )}
          {health.isError && !forbidden && (
            <p className="text-sm text-destructive" role="alert" data-testid="health-error">{getErrorMessage(health.error)}</p>
          )}

          {data && (
            <>
              {data.status !== null && <StatusRing status={data.status} reasons={data.reasons} />}
              <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
                <span>
                  {t("health.period", { from: formatShortDate(data.period.from), to: formatShortDate(data.period.to), defaultValue: "{{from}} to {{to}}" })}
                </span>
                <span data-testid="health-data-through">
                  {t("health.dataThrough", { date: formatShortDate(data.dataThrough), defaultValue: "Data through {{date}}" })}
                </span>
                <span>{t("health.preset", { preset: data.preset, defaultValue: "Preset: {{preset}}" })}</span>
                <Link to="/manage/health" className="inline-flex items-center gap-1 text-brand-700 hover:underline dark:text-brand-300">
                  <LayoutList className="h-3 w-3" aria-hidden="true" />
                  {t("health.allAgents", "All agents")}
                </Link>
              </div>
              <ChecksStrip checks={data.checks} />
              <div className="grid gap-4 lg:grid-cols-2">
                {orderedSections(data.sections).map((id) => (
                  <SectionCard
                    key={id}
                    agentId={agentId}
                    sectionId={id}
                    section={data.sections[id]!}
                    query={query}
                    onDrill={setDrill}
                    onMapOutcomes={() => setDialog("suggestions")}
                  />
                ))}
              </div>
            </>
          )}
        </div>
      )}

      <SessionsDialog open={drill !== null} onClose={() => setDrill(null)} agentId={agentId} query={query} filter={drill} canEdit={canEdit} />
      <OutcomeSuggestionsDialog open={dialog === "suggestions"} onClose={() => setDialog(null)} agentId={agentId} />
      <CompareDialog open={dialog === "compare"} onClose={() => setDialog(null)} agentId={agentId} latestVersion={latestVersion} />
      {canEdit && laterPhases && <ProfileDialog open={dialog === "profile"} onClose={() => setDialog(null)} agentId={agentId} info={info} />}
      {canEdit && laterPhases && <ReportLinksDialog open={dialog === "reports"} onClose={() => setDialog(null)} agentId={agentId} canReadQuality={canReadQuality} />}
    </section>
  );
}
