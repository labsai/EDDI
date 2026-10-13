import { describe, it, expect } from "vitest";
import { applyChangedFields, changedAgentFields, isAgentDraftDirty } from "@/lib/agent-draft";
import type { Agent } from "@/lib/api/agents";

describe("agent draft", () => {
  const stored: Agent = {
    description: "stored",
    capabilities: [{ skill: "a", confidence: "high" }],
    hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY" },
  };

  it("is clean with no draft, or with a draft equal to what is stored", () => {
    expect(isAgentDraftDirty(null, stored)).toBe(false);
    expect(isAgentDraftDirty({ ...stored }, stored)).toBe(false);
  });

  it("ignores key order — a block removed and put back is not a change", () => {
    const { hitlConfig, ...rest } = stored;
    const reordered = { ...rest, hitlConfig } as Agent;
    expect(isAgentDraftDirty(reordered, stored)).toBe(false);
  });

  it("names the top-level fields a draft changes", () => {
    const draft: Agent = { ...stored, description: "edited" };
    delete draft.hitlConfig;
    expect([...changedAgentFields(draft, stored)].sort()).toEqual(["description", "hitlConfig"]);
    expect(isAgentDraftDirty(draft, stored)).toBe(true);
  });

  it("applies an edit built from an older render without undoing the edit before it", () => {
    const rendered = stored;
    const latest: Agent = { ...stored, description: "edited first" };
    const next: Agent = { ...rendered, capabilities: [] };

    expect(applyChangedFields(latest, rendered, next)).toEqual({
      ...stored,
      description: "edited first",
      capabilities: [],
    });
  });

  it("carries a draft's edits onto a newer stored version (conflict rebase)", () => {
    const newer: Agent = { ...stored, description: "edited elsewhere" };
    const draft: Agent = { ...stored, hitlConfig: { timeoutPolicy: "ABORT" } };

    expect(applyChangedFields(newer, stored, draft)).toEqual({
      ...stored,
      description: "edited elsewhere",
      hitlConfig: { timeoutPolicy: "ABORT" },
    });
  });
});
