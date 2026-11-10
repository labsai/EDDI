import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ArrowDownRight, ArrowUpRight, Minus, Star } from "lucide-react";
import type { HealthMarker, Measure, SeriesPoint } from "@/lib/api/health";
import { cn } from "./deps";
import { BandBadge } from "./band-badge";
import { bandMeta, deltaTone, formatDelta, formatInterval, formatMeasureValue, isResolutionRate, measureLabel } from "./format";
import { ResolutionSplit } from "./resolution-split";
import { Sparkline } from "./sparkline";

const TONE_CLASS = { better: "text-emerald-700 dark:text-emerald-400", worse: "text-destructive", neutral: "text-muted-foreground" } as const;

function Delta({ measure }: { measure: Measure }) {
  const { t } = useTranslation();
  if (measure.delta === null || measure.delta === undefined) return null;
  const tone = deltaTone(measure);
  const Icon = measure.delta === 0 ? Minus : measure.delta > 0 ? ArrowUpRight : ArrowDownRight;
  const toneWord =
    tone === "better" ? t("health.delta.better", "better") : tone === "worse" ? t("health.delta.worse", "worse") : null;
  return (
    <p className={cn("flex flex-wrap items-center gap-x-1.5 text-xs", TONE_CLASS[tone])} data-testid="health-delta" data-tone={tone}>
      <Icon className="h-3.5 w-3.5 rtl:-scale-x-100" aria-hidden="true" />
      <span className="font-medium tabular-nums">{formatDelta(measure.delta, measure.unit)}</span>
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
 * interval, a hint when it has no band, and a trend. Unknown measure ids render
 * the same way — everything shown comes from the measure itself.
 */
export function MeasureCard({ measure, points, markers, actions, onResolutionDrill }: MeasureCardProps) {
  const { t } = useTranslation();
  const label = measureLabel(t, measure);
  const interval = formatInterval(measure.interval, measure.unit);
  const grey = measure.band === "GREY";
  return (
    <div className="space-y-2 rounded-lg border border-border bg-background/50 p-3" data-testid="health-measure" data-measure-id={measure.id}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h4 className="flex items-center gap-1.5 text-sm font-medium text-foreground">
          {label}
          {measure.critical && (
            <span title={t("health.critical", "Critical measure: drives the agent's status")}>
              <Star className="h-3 w-3 text-muted-foreground" aria-label={t("health.critical", "Critical measure: drives the agent's status")} />
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
        <span className={cn("text-2xl font-semibold tabular-nums", grey ? "text-muted-foreground" : "text-foreground")} data-testid="health-value">
          {formatMeasureValue(measure.value, measure.unit)}
        </span>
        <span className="text-xs text-muted-foreground tabular-nums">
          {t("health.sampleSize", { n: measure.n, defaultValue: "n={{n}}" })}
        </span>
        {interval && (
          <span className="text-xs text-muted-foreground tabular-nums" data-testid="health-interval" title={t("health.intervalHint", "Plausible range given the sample size")}>
            {t("health.interval", { range: interval, defaultValue: "range {{range}}" })}
          </span>
        )}
      </div>

      {grey && measure.hint && (
        <p className={cn("text-xs", bandMeta("GREY").classes.split(" ").find((c) => c.startsWith("text-")))} data-testid="health-hint">
          {measure.hint}
        </p>
      )}
      <Delta measure={measure} />
      {!grey && <Thresholds measure={measure} />}
      {isResolutionRate(measure) && <ResolutionSplit measure={measure} onDrill={onResolutionDrill} />}
      {points && <Sparkline points={points} markers={markers} unit={measure.unit} label={label} className={bandMeta(measure.band).stroke} />}
      {actions && <div className="flex flex-wrap gap-2 pt-1">{actions}</div>}
    </div>
  );
}
