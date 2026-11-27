import type { HealthMarker, SeriesPoint } from "@eddi/app-kit/lib/api/health";

/** Width of the sparkline viewBox, in SVG units. */
export const SPARK_W = 160;
/** Height of the sparkline viewBox, in SVG units. */
export const SPARK_H = 36;
const PAD = 3;

export interface SparklineGeometry {
  /** The lines: one `M…L…` subpath per run of two or more days with data; a gap (null day) ends a run. */
  path: string;
  /** A day with data between two gaps (or the series' ends): it has no neighbour to draw a line to. */
  dots: Array<{ x: number; y: number }>;
  /** x of each marker that falls inside the series, by marker id. */
  markers: Array<{ id: string; x: number; label: string }>;
  min: number;
  max: number;
  /** Number of days with data. */
  filled: number;
}

/** Pure geometry for the sparkline; a day without data is a gap, never a zero. */
export function sparklineGeometry(points: SeriesPoint[], markers: HealthMarker[] = []): SparklineGeometry {
  const values = points.map((p) => p.value).filter((v): v is number => v !== null && Number.isFinite(v));
  const min = values.length ? Math.min(...values) : 0;
  const max = values.length ? Math.max(...values) : 0;
  const span = max - min || 1;
  const step = points.length > 1 ? (SPARK_W - 2 * PAD) / (points.length - 1) : 0;
  const x = (i: number) => PAD + i * step;
  const y = (v: number) => SPARK_H - PAD - ((v - min) / span) * (SPARK_H - 2 * PAD);

  // Runs of consecutive days with data. A run of two or more is a line; a run of
  // one has no segment to draw (an `M` alone paints nothing), so it is a dot.
  const runs: Array<Array<{ x: number; y: number }>> = [];
  let run: Array<{ x: number; y: number }> = [];
  points.forEach((p, i) => {
    if (p.value === null || !Number.isFinite(p.value)) {
      if (run.length) runs.push(run);
      run = [];
      return;
    }
    run.push({ x: x(i), y: y(p.value) });
  });
  if (run.length) runs.push(run);

  const at = (q: { x: number; y: number }) => `${q.x.toFixed(1)} ${q.y.toFixed(1)}`;
  const path = runs
    .filter((r) => r.length > 1)
    .map((r) => r.map((q, i) => `${i === 0 ? "M" : "L"}${at(q)}`).join(""))
    .join("");
  const dots = runs.filter((r) => r.length === 1).map((r) => r[0]!);

  const dayIndex = new Map(points.map((p, i) => [p.day, i]));
  const placed = markers.flatMap((m) => {
    const i = dayIndex.get(m.at.slice(0, 10));
    return i === undefined ? [] : [{ id: m.id, x: x(i), label: m.label }];
  });
  return { path, dots, markers: placed, min, max, filled: values.length };
}
