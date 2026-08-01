import { describe, it, expect, vi } from "vitest";

/**
 * Proves the `scope` argument actually reaches the branch.
 *
 * Separate file because the mock is hoisted over the whole module graph. It has
 * to exist at all because `read_only` and `read_write` resolve to the *same*
 * endpoint set today — so every assertion in `system-prompt.test.ts` still
 * passes against an implementation that ignores its `scope` argument and always
 * builds the read-only preamble. That implementation would be silently correct
 * until the commit that populates `WRITE_ENDPOINTS`, and then would hand the
 * agent write tools underneath a preamble forbidding their use.
 *
 * Stubbing `endpointsForScope` is what makes the two scopes distinguishable
 * before any write is granted.
 */
vi.mock("../tool-scopes", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../tool-scopes")>();
  return {
    ...actual,
    endpointsForScope: (scope: string) =>
      scope === "read_write"
        ? [...actual.READ_ENDPOINTS, "POST /administration/production/deploy/{agentId}"]
        : actual.READ_ENDPOINTS,
  };
});

const { safetyPreambleForScope, defaultOperatorPromptBody, buildOperatorSystemPrompt } =
  await import("../system-prompt");

describe("scope reaches the preamble branch", () => {
  it("builds the write preamble for a scope that resolves to a write", () => {
    expect(safetyPreambleForScope("read_write")).toContain("A rejection is final");
    expect(safetyPreambleForScope("read_write")).not.toContain("You are read-only");
  });

  it("still builds the read-only preamble for a scope that does not", () => {
    expect(safetyPreambleForScope("read_only")).toContain("You are read-only");
    expect(safetyPreambleForScope("read_only")).not.toContain("A rejection is final");
  });

  it("threads the same scope through the composed prompt", () => {
    expect(buildOperatorSystemPrompt("Body.", "read_write")).toContain("A rejection is final");
    expect(buildOperatorSystemPrompt("Body.", "read_only")).not.toContain("A rejection is final");
  });

  it("branches the default body on the same scope", () => {
    expect(defaultOperatorPromptBody("read_write")).toContain("When you change something");
    expect(defaultOperatorPromptBody("read_only")).not.toContain("When you change something");
  });
});
