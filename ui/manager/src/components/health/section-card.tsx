import { useTranslation } from "react-i18next";
import { Lock, ListTree, Wand2 } from "lucide-react";
import type { HealthQuery, HealthSection } from "@/lib/api/health";
import { useHealthSeries } from "@/hooks/use-agent-health";
import { Button, Card, CardContent, CardHeader, CardTitle, Skeleton } from "./deps";
import { drillForMeasure, drillForResolution, type DrillFilter } from "./drill";
import { formatMeasureValue, sectionTitle } from "./format";
import { MeasureCard } from "./measure-card";

/** The backend accepts at most six measures per series call. */
const MAX_SERIES_MEASURES = 6;

export interface SectionCardProps {
  agentId: string;
  sectionId: string;
  section: HealthSection;
  query: HealthQuery;
  onDrill: (filter: DrillFilter) => void;
  onMapOutcomes: () => void;
}

/** One section: its measures with their trends, or the access message when restricted. */
export function SectionCard({ agentId, sectionId, section, query, onDrill, onMapOutcomes }: SectionCardProps) {
  const { t } = useTranslation();
  const ids = section.measures.slice(0, MAX_SERIES_MEASURES).map((m) => m.id);
  const series = useHealthSeries(agentId, ids, query, !section.restricted);
  const byMeasure = new Map((series.data?.series ?? []).filter((s) => !s.group).map((s) => [s.measure, s.points]));

  return (
    <Card data-testid={`health-section-${sectionId}`}>
      <CardHeader className="flex-row items-center justify-between space-y-0 pb-0">
        <CardTitle className="text-base">{sectionTitle(t, sectionId)}</CardTitle>
        {sectionId === "effectiveness" && section.coverage !== undefined && section.coverage !== null && (
          <span className="text-xs text-muted-foreground" data-testid="health-coverage">
            {t("health.coverage", { value: formatMeasureValue(section.coverage, "ratio"), defaultValue: "Outcome coverage {{value}}" })}
          </span>
        )}
      </CardHeader>
      <CardContent className="space-y-3 pt-3">
        {section.restricted ? (
          <p className="flex items-center gap-2 text-sm text-muted-foreground" data-testid="health-restricted">
            <Lock className="h-4 w-4" aria-hidden="true" />
            {t("health.restricted", "You don't have access to this section.")}
          </p>
        ) : section.measures.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("health.noMeasures", "No measures in this section yet.")}</p>
        ) : (
          section.measures.map((m) => {
            const drill = sectionId === "effectiveness" ? drillForMeasure(m.id) : null;
            const lowCoverage = m.id === "outcomeCoverage";
            return (
              <MeasureCard
                key={m.id}
                measure={m}
                points={byMeasure.get(m.id)}
                markers={series.data?.markers}
                onResolutionDrill={sectionId === "effectiveness" ? (key) => onDrill(drillForResolution(key)) : undefined}
                actions={
                  <>
                    {drill && (
                      <Button size="sm" variant="outline" onClick={() => onDrill(drill)} data-testid={`health-drill-${m.id}`}>
                        <ListTree className="me-1.5 h-3.5 w-3.5" aria-hidden="true" />
                        {t("health.viewSessions", "View sessions")}
                      </Button>
                    )}
                    {lowCoverage && (
                      <Button size="sm" variant={m.band === "GREEN" ? "outline" : "primary"} onClick={onMapOutcomes} data-testid="health-map-outcomes">
                        <Wand2 className="me-1.5 h-3.5 w-3.5" aria-hidden="true" />
                        {t("health.mapOutcomes", "Map outcomes")}
                      </Button>
                    )}
                  </>
                }
              />
            );
          })
        )}
        {!section.restricted && series.isLoading && ids.length > 0 && <Skeleton className="h-6 w-full" />}
      </CardContent>
    </Card>
  );
}
