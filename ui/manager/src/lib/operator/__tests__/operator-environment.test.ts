import { describe, it, expect } from "vitest";
import { operatorEnvironment } from "../operator-environment";

/**
 * One line, and the mutation run still found it unverified. It is worth a test
 * because of what it is for: `OperatorConfig.environment` is a string read back
 * from a backend global variable, and the failure this normalization prevents
 * is creating a conversation in one environment while addressing the stream
 * with another — the two then disagree about which deployment a turn belongs
 * to.
 */

describe("operatorEnvironment", () => {
  it.each(["production", "test"])("passes %s through unchanged", (environment) => {
    expect(operatorEnvironment({ environment })).toBe(environment);
  });

  it.each([
    ["an unknown value", "staging"],
    ["an empty string", ""],
    ["the wrong case", "Production"],
  ])("falls back to production for %s", (_label, environment) => {
    // Production, matching the backend's own @DefaultValue("production") —
    // not "test", which would quietly point a real deployment at the wrong one.
    expect(operatorEnvironment({ environment })).toBe("production");
  });
});
