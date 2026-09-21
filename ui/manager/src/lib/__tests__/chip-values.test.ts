import { describe, it, expect } from "vitest";
import { commitPending, splitChipEntries } from "@/lib/chip-values";

/**
 * The arithmetic behind the flush that stops Save silently dropping typed text.
 */

describe("splitChipEntries", () => {
  it("treats the whole string as one entry when nothing splits it", () => {
    expect(splitChipEntries("https://api.example.com")).toEqual([
      "https://api.example.com",
    ]);
  });

  it("splits on the provided separator and trims each part", () => {
    expect(splitChipEntries(" read:jira  offline_access ", /[\s,]+/)).toEqual([
      "read:jira",
      "offline_access",
    ]);
  });

  it("deduplicates against itself", () => {
    // The bug this pins: `openid openid profile` produced two identical entries,
    // which React renders with duplicate keys and whose remove button deletes
    // both at once.
    expect(splitChipEntries("openid openid profile", /[\s,]+/)).toEqual([
      "openid",
      "profile",
    ]);
  });

  it("yields nothing for blank input", () => {
    expect(splitChipEntries("")).toEqual([]);
    expect(splitChipEntries("   ")).toEqual([]);
    expect(splitChipEntries("  ,  ", /[\s,]+/)).toEqual([]);
  });
});

describe("commitPending", () => {
  it("folds typed text into the list", () => {
    expect(commitPending(["a"], "b")).toEqual(["a", "b"]);
  });

  it("splits when told to", () => {
    expect(commitPending([], "read write", /[\s,]+/)).toEqual(["read", "write"]);
  });

  it("does not re-add a value the list already holds", () => {
    expect(commitPending(["a"], "a")).toEqual(["a"]);
  });

  it("returns the ORIGINAL array when nothing was added", () => {
    // Identity is the signal a caller uses to decide whether anything changed.
    const values = ["a"];
    expect(commitPending(values, "")).toBe(values);
    expect(commitPending(values, "a")).toBe(values);
  });

  it("does not mutate the input", () => {
    const values = ["a"];
    commitPending(values, "b");
    expect(values).toEqual(["a"]);
  });
});
