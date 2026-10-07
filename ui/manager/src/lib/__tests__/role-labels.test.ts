import { describe, it, expect } from "vitest";
import { humanizeRole, summarizeRoles } from "@/lib/role-labels";
import { buildIsoDuration, parseDurationParts } from "@/lib/iso-duration-parts";

describe("humanizeRole", () => {
  it("turns enum and camelCase identifiers into words", () => {
    expect(humanizeRole("DEVIL_ADVOCATE")).toBe("Devil advocate");
    expect(humanizeRole("humanDirector")).toBe("Human director");
  });

  it("leaves readable roles and short acronyms alone", () => {
    expect(humanizeRole("Marketing")).toBe("Marketing");
    expect(humanizeRole("Party A")).toBe("Party A");
    expect(humanizeRole("PRO")).toBe("PRO");
    expect(humanizeRole("QA")).toBe("QA");
  });
});

describe("summarizeRoles", () => {
  it("collapses repeats into a count and drops blanks, keeping first-seen order", () => {
    expect(
      summarizeRoles(["Forecasting", "Forecasting", null, "", "DEVIL_ADVOCATE", "Forecasting"]),
    ).toEqual([
      { label: "Forecasting", count: 3 },
      { label: "Devil advocate", count: 1 },
    ]);
  });
});

describe("ISO duration parts", () => {
  it("round-trips the simple forms the wizard writes", () => {
    expect(parseDurationParts("PT30M")).toEqual({ amount: 30, unit: "minutes" });
    expect(parseDurationParts("PT24H")).toEqual({ amount: 24, unit: "hours" });
    expect(parseDurationParts("P2D")).toEqual({ amount: 2, unit: "days" });
    expect(buildIsoDuration(30, "minutes")).toBe("PT30M");
    expect(buildIsoDuration(24, "hours")).toBe("PT24H");
    expect(buildIsoDuration(2, "days")).toBe("P2D");
  });

  it("does not claim forms it cannot represent", () => {
    expect(parseDurationParts("PT1H30M")).toBeNull();
    expect(parseDurationParts("abc")).toBeNull();
  });
});
