import { describe, expect, it } from "vitest";
import { drillForMeasure, drillForResolution } from "../drill";
import { formatAge, formatDays, formatDelta, formatSignedPercent, resolutionSplit } from "../format";
import { chunkMeasures } from "../use-agent-health";
import { fromInput, parseDecimal, toInput, unmatchedViolations } from "../profile-utils";

describe("formatDelta with a translated unit", () => {
  it("uses the given percentage-point label", () => {
    expect(formatDelta(-0.12, "ratio", "Prozentpunkte")).toBe("−12 Prozentpunkte");
    expect(formatDelta(0.008, "ratio")).toBe("+0.8 pp");
  });
});

describe("formatSignedPercent", () => {
  it("signs a relative change", () => {
    expect(formatSignedPercent(-0.08)).toBe("−8%");
    expect(formatSignedPercent(0.12)).toBe("+12%");
    expect(formatSignedPercent(0)).toBe("±0%");
    expect(formatSignedPercent(null)).toBe("—");
  });
});

describe("formatAge", () => {
  const now = Date.parse("2026-11-10T12:00:00Z");
  it("reads a timestamp as a relative phrase in the app's language", () => {
    expect(formatAge("2026-11-08T12:00:00Z", now)).toBe("2 days ago");
    expect(formatAge("2026-11-10T09:00:00Z", now)).toBe("3 hours ago");
    expect(formatAge("2026-11-10T11:30:00Z", now)).toBe("30 minutes ago");
  });
  it("shows a dash for nothing or nonsense", () => {
    expect(formatAge(null, now)).toBe("—");
    expect(formatAge("not a date", now)).toBe("—");
  });
});

describe("formatDays", () => {
  it("is a localised unit, not a hand-built string", () => {
    expect(formatDays(7)).toBe("7 days");
    expect(formatDays(1)).toBe("1 day");
  });
});

describe("the four-part resolution split", () => {
  it("shares the resolutions between confirmed, self-reported, agent closed and silence", () => {
    const split = resolutionSplit({
      breakdown: [
        { key: "confirmed", label: "c", value: 30, n: 30 },
        { key: "selfReported", label: "s", value: 10, n: 10 },
        { key: "agentClosed", label: "a", value: 20, n: 20 },
        { key: "silence", label: "q", value: 40, n: 40 },
      ],
    })!;
    // Counts work as well as ratios: only the proportions are read.
    expect(split.parts.map((p) => Math.round(p.share * 100))).toEqual([30, 10, 20, 40]);
    expect(split.silenceShare).toBeCloseTo(0.4);
    expect(split.mostlySilence).toBe(false);
  });

  it("flags silence alone above half, whatever the other parts are", () => {
    const split = resolutionSplit({
      breakdown: [
        { key: "confirmed", label: "c", value: 0.1, n: 1 },
        { key: "selfReported", label: "s", value: 0.1, n: 1 },
        { key: "silence", label: "q", value: 0.6, n: 1 },
      ],
    })!;
    expect(split.mostlySilence).toBe(true);
  });
});

describe("drill filters", () => {
  it("keeps the model's own report out of the confirmed drill-down", () => {
    expect(drillForResolution("confirmed").evidence).toEqual(["external_event", "conversation_id_event", "agent_action"]);
    expect(drillForResolution("selfReported").evidence).toEqual(["self_reported"]);
    expect(drillForResolution("silence").evidence).toEqual(["silence"]);
    expect(drillForResolution("agentClosed").evidence).toEqual(["agent_closed"]);
  });

  it("does not count ENDED_QUIETLY as abandoned", () => {
    expect(drillForMeasure("abandonmentRate")!.outcome).toEqual(["ABANDONED"]);
    expect(drillForMeasure("resolutionRate")!.outcome).toEqual(["RESOLVED", "ASSUMED_RESOLVED"]);
    expect(drillForMeasure("agentFailureRate")).toBeNull();
  });
});

describe("series chunking", () => {
  it("never drops a measure beyond the sixth", () => {
    const ids = Array.from({ length: 14 }, (_, i) => `m${i}`);
    const chunks = chunkMeasures(ids);
    expect(chunks.map((c) => c.length)).toEqual([6, 6, 2]);
    expect(chunks.flat()).toEqual(ids);
  });
});

describe("parseDecimal", () => {
  it.each([
    ["0.5", 0.5],
    ["0,5", 0.5],
    ["1,234.5", 1234.5],
    ["1.234,5", 1234.5],
    ["1,234,567", 1234567],
    ["-3", -3],
    [".5", 0.5],
    ["0.", 0],
  ])("reads %s as %d", (text, value) => {
    expect(parseDecimal(text)).toBe(value);
  });

  it("tells empty from not-a-number", () => {
    expect(parseDecimal("")).toBeUndefined();
    expect(parseDecimal("  ")).toBeUndefined();
    expect(parseDecimal("-")).toBeNaN();
    expect(parseDecimal("abc")).toBeNaN();
    expect(parseDecimal("1.2.3")).toBeNaN();
  });

  it("converts percentages for ratio fields in both directions", () => {
    expect(fromInput("70", "ratio")).toBe(0.7);
    expect(fromInput("0,5", "usd")).toBe(0.5);
    expect(toInput(0.7, "ratio")).toBe("70");
    expect(toInput(0.5, "usd")).toBe("0.5");
  });
});

describe("unmatchedViolations", () => {
  const draft = {
    preset: "support",
    critical: [],
    thresholds: { resolutionRate: {} },
    budgets: {},
    slo: {},
    overrides: [{ measure: "x", state: "IGNORED", reason: "r", until: "2026-12-01" }],
  };
  it("keeps violations that have a field out of the general list and never drops the rest", () => {
    const rest = unmatchedViolations(
      [
        { path: "thresholds.resolutionRate.red", message: "a" },
        { path: "thresholds.gone.red", message: "b" },
        { path: "overrides[0].reason", message: "c" },
        { path: "overrides[3].reason", message: "d" },
        { path: "slo.agentTurnSuccess", message: "e" },
        { path: "somethingNew", message: "f" },
      ],
      draft,
    );
    expect(rest.map((v) => v.message)).toEqual(["b", "d", "f"]);
  });
});
