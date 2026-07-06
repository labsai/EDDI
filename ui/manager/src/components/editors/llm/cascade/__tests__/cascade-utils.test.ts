import { describe, it, expect } from "vitest";
import { parseNum, nextParamKey } from "../cascade-utils";

describe("parseNum", () => {
  it("returns undefined for blank or non-numeric input", () => {
    expect(parseNum("")).toBeUndefined();
    expect(parseNum("abc")).toBeUndefined();
  });
  it("parses integers and decimals, preserving zero", () => {
    expect(parseNum("0")).toBe(0);
    expect(parseNum("30000")).toBe(30000);
    expect(parseNum("0.15")).toBe(0.15);
  });
});

describe("nextParamKey", () => {
  it("returns param0 for an empty map", () => {
    expect(nextParamKey({})).toBe("param0");
  });
  it("skips existing paramN keys", () => {
    expect(nextParamKey({ param0: "a" })).toBe("param1");
    expect(nextParamKey({ param0: "a", param1: "b" })).toBe("param2");
  });
  it("finds the first FREE index (not length-based) so removals don't collide", () => {
    // param0 removed, param1 still present → next free is param0, not param2
    expect(nextParamKey({ param1: "b" })).toBe("param0");
  });
  it("ignores non-param keys", () => {
    expect(nextParamKey({ model: "x", apiKey: "y" })).toBe("param0");
  });
});
