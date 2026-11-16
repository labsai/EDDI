import { describe, expect, it } from "vitest";
import type { Measure } from "../api";
import {
  allowedReportSections,
  bandMeta,
  compareVerdict,
  deltaTone,
  formatDelta,
  formatInterval,
  formatMeasureValue,
  orderedSections,
  resolutionSplit,
  versionOptions,
} from "../format";

const section = { restricted: false, measures: [] };

describe("formatMeasureValue", () => {
  it("renders ratios as percentages, with a decimal below 10 %", () => {
    expect(formatMeasureValue(0.71, "ratio")).toBe("71%");
    expect(formatMeasureValue(0.012, "ratio")).toBe("1.2%");
    expect(formatMeasureValue(0, "ratio")).toBe("0%");
  });

  it("renders each unit in its own form", () => {
    expect(formatMeasureValue(0.031, "usd")).toBe("$0.03");
    expect(formatMeasureValue(0.0003, "usd")).toBe("$0.0003");
    expect(formatMeasureValue(3200, "ms")).toBe("3.20s");
    expect(formatMeasureValue(240, "ms")).toBe("240ms");
    expect(formatMeasureValue(0.93, "score")).toBe("0.93");
    expect(formatMeasureValue(1820, "count")).toBe("1,820");
    expect(formatMeasureValue(1850, "tokens")).toBe("1.9K");
  });

  it("shows a dash when there is no value", () => {
    expect(formatMeasureValue(null, "ratio")).toBe("—");
    expect(formatMeasureValue(undefined, "usd")).toBe("—");
    expect(formatMeasureValue(Number.NaN, "count")).toBe("—");
  });
});

describe("formatDelta", () => {
  it("states ratio changes in percentage points with an explicit sign", () => {
    expect(formatDelta(0.008, "ratio")).toBe("+0.8 pp");
    expect(formatDelta(-0.12, "ratio")).toBe("−12 pp");
    expect(formatDelta(0, "ratio")).toBe("±0 pp");
  });

  it("signs other units in their own form", () => {
    expect(formatDelta(-0.003, "usd")).toBe("−$0.0030");
    expect(formatDelta(100, "ms")).toBe("+100ms");
    expect(formatDelta(-160, "count")).toBe("−160");
    expect(formatDelta(null, "count")).toBe("—");
  });
});

describe("formatInterval", () => {
  it("joins the bounds in the measure's unit", () => {
    expect(formatInterval({ low: 0.009, high: 0.016 }, "ratio")).toBe("0.9%–1.6%");
    expect(formatInterval(null, "ratio")).toBeNull();
  });
});

describe("deltaTone", () => {
  const th = (higherIsBetter: boolean) => ({ green: 1, red: 2, higherIsBetter });

  it("reads a significant change against the measure's direction", () => {
    expect(deltaTone({ delta: -0.12, significant: true, thresholds: th(true) })).toBe("worse");
    expect(deltaTone({ delta: 0.03, significant: true, thresholds: th(true) })).toBe("better");
    expect(deltaTone({ delta: 0.008, significant: true, thresholds: th(false) })).toBe("worse");
    expect(deltaTone({ delta: -0.008, significant: true, thresholds: th(false) })).toBe("better");
  });

  it("stays neutral without significance, without a direction, or without change", () => {
    expect(deltaTone({ delta: -0.5, significant: false, thresholds: th(true) })).toBe("neutral");
    expect(deltaTone({ delta: -0.5, significant: true, thresholds: null })).toBe("neutral");
    expect(deltaTone({ delta: 0, significant: true, thresholds: th(true) })).toBe("neutral");
    expect(deltaTone({ delta: null, significant: true, thresholds: th(true) })).toBe("neutral");
  });
});

describe("bandMeta", () => {
  it("falls back to grey for an unknown band", () => {
    expect(bandMeta("PURPLE").key).toBe("grey");
    expect(bandMeta(undefined).key).toBe("grey");
    expect(bandMeta("RED").key).toBe("red");
  });
});

describe("resolutionSplit", () => {
  const measure = (silence: number, confirmed = 0.1, agentClosed = 0.1): Pick<Measure, "breakdown"> => ({
    breakdown: [
      { key: "confirmed", label: "Confirmed", value: confirmed, n: 1 },
      { key: "agentClosed", label: "Agent closed", value: agentClosed, n: 1 },
      { key: "silence", label: "Silence", value: silence, n: 1 },
    ],
  });

  it("splits the rate into its three sources with shares", () => {
    const split = resolutionSplit(measure(0.2, 0.3, 0.2))!;
    expect(split.parts.map((p) => p.key)).toEqual(["confirmed", "selfReported", "agentClosed", "silence"]);
    expect(split.parts.find((p) => p.key === "selfReported")!.value).toBe(0);
    expect(split.total).toBeCloseTo(0.7);
    expect(split.parts[0]!.share).toBeCloseTo(0.3 / 0.7);
    expect(split.mostlySilence).toBe(false);
  });

  it("flags a rate that rests mostly on silence", () => {
    const split = resolutionSplit(measure(0.5))!;
    expect(split.silenceShare).toBeCloseTo(0.5 / 0.7);
    expect(split.mostlySilence).toBe(true);
  });

  it("does not flag silence at exactly half", () => {
    expect(resolutionSplit(measure(0.2, 0.1, 0.1))!.mostlySilence).toBe(false);
    expect(resolutionSplit(measure(0.1, 0.1, 0.1))!.mostlySilence).toBe(false);
  });

  it("treats a missing source as zero and a missing breakdown as no split", () => {
    const split = resolutionSplit({ breakdown: [{ key: "confirmed", label: "c", value: 0.4, n: 5 }] })!;
    expect(split.parts.find((p) => p.key === "silence")!.value).toBe(0);
    expect(split.mostlySilence).toBe(false);
    expect(resolutionSplit({ breakdown: null })).toBeNull();
    expect(resolutionSplit({ breakdown: [] })).toBeNull();
  });

  it("is not mostly-silence when there is nothing resolved at all", () => {
    const split = resolutionSplit(measure(0, 0, 0))!;
    expect(split.total).toBe(0);
    expect(split.mostlySilence).toBe(false);
  });
});

describe("small helpers", () => {
  it("lists versions newest first, capped", () => {
    expect(versionOptions(3)).toEqual([3, 2, 1]);
    expect(versionOptions(100, 5)).toEqual([100, 99, 98, 97, 96]);
    expect(versionOptions(0)).toEqual([]);
  });

  it("orders known sections first and keeps unknown ones", () => {
    expect(
      orderedSections({ business: section, reliability: section, surprise: section } as never),
    ).toEqual(["reliability", "business", "surprise"]);
  });

  it("gives a verdict only to a significant, directional change", () => {
    expect(compareVerdict({ delta: -0.1, significant: true, better: false })).toBe("worse");
    expect(compareVerdict({ delta: 0.1, significant: true, better: true })).toBe("better");
    expect(compareVerdict({ delta: 0.1, significant: false, better: true })).toBe("neutral");
    expect(compareVerdict({ delta: 100, significant: true, better: null })).toBe("neutral");
  });

  it("offers the quality section only to someone who can read ratings", () => {
    expect(allowedReportSections(true)).toContain("quality");
    expect(allowedReportSections(false)).not.toContain("quality");
    expect(allowedReportSections(false)).toContain("reliability");
  });
});
