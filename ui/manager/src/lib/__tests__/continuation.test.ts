import { describe, expect, it } from "vitest";
import { MAX_CONTINUATIONS, parseMaxContinuations } from "@/lib/continuation";

describe("parseMaxContinuations", () => {
  it("reads empty as off (field absent)", () => {
    expect(parseMaxContinuations("")).toEqual({ ok: true, value: undefined });
    expect(parseMaxContinuations("  ")).toEqual({ ok: true, value: undefined });
  });

  it("accepts whole numbers from 0 to the backend cap", () => {
    expect(parseMaxContinuations("0")).toEqual({ ok: true, value: 0 });
    expect(parseMaxContinuations(" 12 ")).toEqual({ ok: true, value: 12 });
    expect(parseMaxContinuations(String(MAX_CONTINUATIONS))).toEqual({ ok: true, value: MAX_CONTINUATIONS });
  });

  it("refuses what the backend would refuse", () => {
    for (const text of [String(MAX_CONTINUATIONS + 1), "-1", "1.5", "1e3", "abc"]) {
      expect(parseMaxContinuations(text)).toEqual({ ok: false });
    }
  });
});
