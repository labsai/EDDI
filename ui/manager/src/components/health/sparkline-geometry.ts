import type { HealthMarker, SeriesPoint } from "./api";

export const SPARK_W = 160;
export const SPARK_H = 36;
const PAD = 3;

export interface SparklineGeometry {
  /** Path segments: a gap (null day) starts a new `M`. */
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

  let path = "";
  let pen = false;
  points.forEach((p, i) => {
    if (p.value === null || !Number.isFinite(p.value)) {
      pen = false;
      return;
    }
    path += `${pen ? "L" : "M"}${x(i).toFixed(1)} ${y(p.value).toFixed(1)}`;
    pen = true;
  });

  const dayIndex = new Map(points.map((p, i) => [p.day, i]));
  const placed = markers.flatMap((m) => {
    const i = dayIndex.get(m.at.slice(0, 10));
    return i === undefined ? [] : [{ id: m.id, x: x(i), label: m.label }];
  });
  return { path, markers: placed, min, max, filled: values.length };
}
