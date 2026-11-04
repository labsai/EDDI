import { describe, it, expect } from "vitest";
import { entryProblems, isStale, mergeReference, parseCatalogImport } from "../model-prices";
import type { ModelPriceEntry } from "@/lib/api/model-prices";

const e = (provider: string, model: string, inputPricePer1M: number | null = 1, outputPricePer1M: number | null = 2): ModelPriceEntry => ({
  provider,
  model,
  inputPricePer1M,
  outputPricePer1M,
});

describe("entryProblems (mirror of the server validator)", () => {
  it("accepts exact names and leading/trailing wildcards", () => {
    expect(entryProblems(e("openai", "gpt-4o"))).toEqual([]);
    expect(entryProblems(e("bedrock", "*anthropic.claude*"))).toEqual([]);
    expect(entryProblems(e("*", "*"))).toEqual([]);
  });

  it("refuses a mid-string wildcard, a blank provider and a missing price", () => {
    expect(entryProblems(e("openai", "gpt*4o")).map((p) => p.field)).toContain("model");
    expect(entryProblems(e("", "x")).map((p) => p.field)).toContain("provider");
    expect(entryProblems(e("openai", "x", null, null)).map((p) => p.field)).toContain("prices");
  });

  it("treats an explicit 0 as a price, and refuses negatives and huge values", () => {
    expect(entryProblems(e("ollama", "*", 0, 0))).toEqual([]);
    expect(entryProblems(e("openai", "x", -1)).map((p) => p.field)).toContain("price");
    expect(entryProblems(e("openai", "x", 10_001)).map((p) => p.field)).toContain("price");
  });

  it("flags duplicates case-insensitively, but not the entry against itself", () => {
    const a = e("openai", "gpt-4o");
    const b = e("openai", "GPT-4o");
    expect(entryProblems(a, [a, b]).map((p) => p.field)).toContain("duplicate");
    expect(entryProblems(a, [a])).toEqual([]);
  });

  it("bounds the long-context threshold", () => {
    expect(entryProblems({ ...e("openai", "x"), longContext: { thresholdTokens: 999, inputPricePer1M: 2 } }).map((p) => p.field)).toContain(
      "threshold",
    );
    expect(entryProblems({ ...e("openai", "x"), longContext: { thresholdTokens: 200_000, inputPricePer1M: 2 } })).toEqual([]);
  });
});

describe("isStale", () => {
  const now = new Date("2026-11-02T00:00:00Z");
  it("is stale past the threshold only", () => {
    expect(isStale("2026-06-01", 120, now)).toBe(true);
    expect(isStale("2026-10-01", 120, now)).toBe(false);
    expect(isStale(null, 120, now)).toBe(false);
  });
});

describe("mergeReference", () => {
  it("adds new entries, replaces changed ones and keeps the admin's own", () => {
    const own = e("mistral", "mistral-large", 2, 6);
    const old = { ...e("openai", "gpt-4o", 5, 15), asOf: "2025-01-01" };
    const merge = mergeReference([own, old], [{ ...e("openai", "gpt-4o", 2.5, 10), asOf: "2026-11-02" }, e("anthropic", "claude*")]);
    expect(merge.added.map((x) => x.model)).toEqual(["claude*"]);
    expect(merge.changed.map((x) => x.model)).toEqual(["gpt-4o"]);
    expect(merge.entries).toHaveLength(3);
    expect(merge.entries[0]).toBe(own);
    expect(merge.entries[1]?.inputPricePer1M).toBe(2.5);
  });

  it("reports unchanged entries", () => {
    const same = { ...e("openai", "gpt-4o"), asOf: "2026-11-02" };
    expect(mergeReference([same], [{ ...same }]).unchanged).toBe(1);
  });
});

describe("mergeReference sameness", () => {
  it("does not read null-versus-absent or key order as a change", () => {
    const edited = { provider: "openai", model: "gpt-4o", outputPricePer1M: 10, inputPricePer1M: 2.5, cacheReadPricePer1M: null, longContext: null, asOf: null };
    const ref = { provider: "openai", model: "gpt-4o", inputPricePer1M: 2.5, outputPricePer1M: 10, asOf: undefined };
    const merged = mergeReference([edited], [ref]);
    expect(merged.unchanged).toBe(1);
    expect(merged.changed).toHaveLength(0);
  });
});

describe("parseCatalogImport", () => {
  it("reads an exported catalog and rejects anything else", () => {
    expect(parseCatalogImport('{"unpricedPolicy":"REFUSE","entries":[]}')).toEqual({ unpricedPolicy: "REFUSE", entries: [] });
    expect(() => parseCatalogImport('{"foo":1}')).toThrow();
    expect(() => parseCatalogImport("not json")).toThrow();
  });

  it("rejects entries without a provider and model", () => {
    expect(() => parseCatalogImport('{"entries":[null]}')).toThrow();
    expect(() => parseCatalogImport('{"entries":[{"model":"m"}]}')).toThrow();
    expect(() => parseCatalogImport('{"entries":[{"provider":"p","model":"m","asOf":5}]}')).toThrow();
  });
});
