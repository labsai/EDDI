import { describe, expect, it } from "vitest";
import { parseCostCeiling } from "../cost-ceiling";

describe("parseCostCeiling", () => {
  it("treats empty as unset, never 0", () => {
    expect(parseCostCeiling("")).toEqual({ ok: true, value: undefined });
    expect(parseCostCeiling("   ")).toEqual({ ok: true, value: undefined });
  });
  it("keeps 0 as a real ceiling", () => {
    expect(parseCostCeiling("0")).toEqual({ ok: true, value: 0 });
  });
  it("accepts decimals", () => {
    expect(parseCostCeiling("0.25")).toEqual({ ok: true, value: 0.25 });
  });
  it("rejects negatives and non-numbers", () => {
    expect(parseCostCeiling("-1")).toEqual({ ok: false });
    expect(parseCostCeiling("-")).toEqual({ ok: false });
    expect(parseCostCeiling("abc")).toEqual({ ok: false });
  });
});
