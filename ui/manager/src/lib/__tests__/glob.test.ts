import { describe, it, expect } from "vitest";
import { globMatches } from "../glob";

describe("globMatches", () => {
  it("treats * as any run of characters, everything else as literal", () => {
    expect(globMatches("http.post:/*", "http.post:/agentstore/agents")).toBe(true);
    expect(globMatches("*:*", "http.post:/x")).toBe(true);
    expect(globMatches("h*", "http.post:/x")).toBe(true);
    expect(globMatches("http.get:/*", "http.post:/x")).toBe(false);
    expect(globMatches("a.c", "abc")).toBe(false); // a dot is a dot
    expect(globMatches("a(b)", "a(b)")).toBe(true);
    expect(globMatches("", "")).toBe(true);
    expect(globMatches("*", "")).toBe(true);
    expect(globMatches("", "x")).toBe(false);
  });

  it("lets * match line terminators", () => {
    expect(globMatches("a*b", "a\nb")).toBe(true);
  });

  it("backtracks correctly over several wildcards", () => {
    expect(globMatches("*a*b*c", "xxaxxbxxc")).toBe(true);
    expect(globMatches("*a*b*c", "xxaxxcxxb")).toBe(false);
    expect(globMatches("a*a*a", "aaa")).toBe(true);
  });

  it("does not go exponential on a run of wildcards that cannot match", () => {
    // The regex built from this pattern took ~29 s on a short address.
    const started = performance.now();
    expect(globMatches("*".repeat(40) + "x*", "http.post:/agentstore/agents/some/long/path/with/no/match")).toBe(false);
    expect(performance.now() - started).toBeLessThan(500);
  });
});
