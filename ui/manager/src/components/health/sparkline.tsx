import { useTranslation } from "react-i18next";
import type { HealthMarker, MeasureUnit, SeriesPoint } from "./api";
import { cn } from "./deps";
import { formatMeasureValue } from "./format";
import { SPARK_H, SPARK_W, sparklineGeometry } from "./sparkline-geometry";

/**
 * A trend line over the period with change markers as dashed verticals. A day
 * without data is a gap, never a zero. Colour comes from `className` (a text
 * colour) via `currentColor`, so it follows the theme.
 */
export function Sparkline({
  points,
  markers,
  unit,
  label,
  className,
}: {
  points: SeriesPoint[];
  markers?: HealthMarker[];
  unit: MeasureUnit;
  label: string;
  className?: string;
}) {
  const { t } = useTranslation();
  const g = sparklineGeometry(points, markers);
  if (g.filled < 2) {
    return (
      <p className="text-xs text-muted-foreground" data-testid="health-sparkline-empty">
        {t("health.noTrend", "Not enough days with data for a trend.")}
      </p>
    );
  }
  const summary = t("health.sparklineLabel", {
    label,
    min: formatMeasureValue(g.min, unit),
    max: formatMeasureValue(g.max, unit),
    // The extremes, not the start and the end: the line need not run from one to the other.
    defaultValue: "{{label}} over the period, between {{min}} and {{max}}",
  });
  return (
    <svg
      viewBox={`0 0 ${SPARK_W} ${SPARK_H}`}
      className={cn("h-9 w-full", className)}
      role="img"
      aria-label={summary}
      data-testid="health-sparkline"
      preserveAspectRatio="none"
    >
      <title>{summary}</title>
      {g.markers.map((m) => (
        <line
          key={m.id}
          x1={m.x}
          x2={m.x}
          y1={0}
          y2={SPARK_H}
          stroke="currentColor"
          strokeOpacity={0.45}
          strokeDasharray="2 2"
          vectorEffect="non-scaling-stroke"
          data-testid="health-marker"
        >
          <title>{m.label}</title>
        </line>
      ))}
      <path d={g.path} fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}
