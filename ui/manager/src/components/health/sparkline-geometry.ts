import type { HealthMarker, SeriesPoint } from "./api";

/** Width of the sparkline viewBox, in SVG units. */
export const SPARK_W = 160;
/** Height of the sparkline viewBox, in SVG units. */
export const SPARK_H = 36;
const PAD = 3;

export interface SparklineGeometry {
  /** Path segments: a gap (null day) starts a new `M`; a day with data between two gaps is a zero-length `h0`, drawn as a dot by the round cap. */
  path: string;
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

  const has = (p: SeriesPoint | undefined) => p !== undefined && p.value !== null && Number.isFinite(p.value);
  let path = "";
  points.forEach((p, i) => {
    if (!has(p)) return;
    const pen = has(points[i - 1]);
    path += `${pen ? "L" : "M"}${x(i).toFixed(1)} ${y(p.value!).toFixed(1)}`;
    // A subpath of a single move strokes nothing: give an isolated day a zero-length segment.
    if (!pen && !has(points[i + 1])) path += "h0";
  });

  const dayIndex = new Map(points.map((p, i) => [p.day, i]));
  const placed = markers.flatMap((m) => {
    const i = dayIndex.get(m.at.slice(0, 10));
    return i === undefined ? [] : [{ id: m.id, x: x(i), label: m.label }];
  });
  return { path, markers: placed, min, max, filled: values.length };
}
