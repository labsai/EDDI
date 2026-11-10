import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ArrowDownRight, ArrowUpRight, Minus, Star } from "lucide-react";
import type { HealthMarker, Measure, SeriesPoint } from "./api";
import { cn } from "./deps";
import { BandBadge } from "./band-badge";
import {
  TONE_CLASS,
  bandMeta,
  deltaTone,
  formatDelta,
  formatInterval,
  formatMeasureValue,
  isResolutionRate,
  measureLabel,
} from "./format";
import { ResolutionSplit } from "./resolution-split";
import { Sparkline } from "./sparkline";

function Delta({ measure }: { measure: Measure }) {
  const { t } = useTranslation();
  // A change against nothing is no change: with no value there is no delta to show.
  if (measure.value === null || measure.delta === null || measure.delta === undefined) return null;
  const tone = deltaTone(measure);
  const Icon = measure.delta === 0 ? Minus : measure.delta > 0 ? ArrowUpRight : ArrowDownRight;
  const toneWord =
    tone === "better" ? t("health.delta.better", "better") : tone === "worse" ? t("health.delta.worse", "worse") : null;
  return (
    <p className={cn("flex flex-wrap items-center gap-x-1.5 text-xs", TONE_CLASS[tone])} data-testid="health-delta" data-tone={tone}>
      <Icon className="h-3.5 w-3.5 rtl:-scale-x-100" aria-hidden="true" />
      <span className="font-medium tabular-nums">{formatDelta(measure.delta, measure.unit, t("health.unit.pp", "pp"))}</span>
      <span className="text-muted-foreground">{t("health.delta.vsBaseline", "vs previous period")}</span>
      {toneWord && <span className="font-medium">· {toneWord}</span>}
      <span className="text-muted-foreground">
        ·{" "}
        {measure.significant
          ? t("health.delta.significant", "significant")
          : t("health.delta.notSignificant", "within normal variation")}
      </span>
    </p>
  );
}

function Thresholds({ measure }: { measure: Measure }) {
  const { t } = useTranslation();
  const th = measure.thresholds;
  if (!th) return null;
  const green = formatMeasureValue(th.green, measure.unit);
  const red = formatMeasureValue(th.red, measure.unit);
  return (
    <p className="text-xs text-muted-foreground" data-testid="health-thresholds">
      {th.higherIsBetter
        ? t("health.thresholds.higher", { green, red, defaultValue: "Green from {{green}}, red below {{red}}" })
        : t("health.thresholds.lower", { green, red, defaultValue: "Green up to {{green}}, red from {{red}}" })}
    </p>
  );
}

export interface MeasureCardProps {
  measure: Measure;
  points?: SeriesPoint[];
  markers?: HealthMarker[];
  /** Extra controls under the figures (drill-down, coverage call to action). */
  actions?: ReactNode;
  /** Called with a resolution source to open the matching session list. */
  onResolutionDrill?: Parameters<typeof ResolutionSplit>[0]["onDrill"];
}

/**
 * One measure: value in its unit, band, change against the baseline, the
 * interval, a hint when it has no value, and a trend. Unknown measure ids render
 * the same way — everything shown comes from the measure itself.
 */
export function MeasureCard({ measure, points, markers, actions, onResolutionDrill }: MeasureCardProps) {
  const { t } = useTranslation();
  const label = measureLabel(t, measure);
  const interval = formatInterval(measure.interval, measure.unit);
  // "No data" is muted; an informational figure (sessions, users) has data and is not.
  const noValue = measure.value === null || measure.value === undefined;
  const critical = t("health.critical", "Critical measure: drives the agent's status");
  return (
    <div className="space-y-2 rounded-lg border border-border bg-background/50 p-3" data-testid="health-measure" data-measure-id={measure.id}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h4 className="flex items-center gap-1.5 text-sm font-medium text-foreground">
          {label}
          {measure.critical && (
            <span title={critical}>
              <Star className="h-3 w-3 text-muted-foreground" role="img" aria-label={critical} />
            </span>
          )}
        </h4>
        {measure.kind === "INFORMATIONAL" ? (
          <span className="text-xs text-muted-foreground">{t("health.informational", "For information")}</span>
        ) : (
          <BandBadge band={measure.band} />
        )}
      </div>

      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <span className={cn("text-2xl font-semibold tabular-nums", noValue ? "text-muted-foreground" : "text-foreground")} data-testid="health-value">
          {formatMeasureValue(measure.value, measure.unit)}
        </span>
        <span className="text-xs text-muted-foreground tabular-nums">
          {t("health.sampleSize", { n: measure.n, defaultValue: "n={{n}}" })}
        </span>
        {interval && (
          <span className="text-xs text-muted-foreground tabular-nums" data-testid="health-interval">
            {t("health.interval", { range: interval, defaultValue: "range {{range}}" })}
          </span>
        )}
      </div>

      {(noValue || measure.band === "GREY") && measure.hint && (
        <p className="text-xs text-muted-foreground" data-testid="health-hint">
          {measure.hint}
        </p>
      )}
      <Delta measure={measure} />
      {!noValue && measure.band !== "GREY" && <Thresholds measure={measure} />}
      {isResolutionRate(measure) && <ResolutionSplit measure={measure} onDrill={onResolutionDrill} />}
      {points && <Sparkline points={points} markers={markers} unit={measure.unit} label={label} className={bandMeta(measure.band).text} />}
      {actions && <div className="flex flex-wrap gap-2 pt-1">{actions}</div>}
    </div>
  );
}
