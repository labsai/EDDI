import { describe, it, expect, vi, beforeEach } from "vitest";
import { findResourceUsage } from "../resource-usage";

// ── Mocks ──────────────────────────────────────────────────────────

vi.mock("../workflows", () => ({
  getWorkflowDescriptors: vi.fn(),
  getWorkflow: vi.fn(),
}));

vi.mock("../agents", async () => {
  const actual = await vi.importActual<typeof import("../agents")>("../agents");
  return {
    ...actual,
    getAgentDescriptors: vi.fn(),
    getAgent: vi.fn(),
  };
});

import { getWorkflowDescriptors, getWorkflow } from "../workflows";
import { getAgentDescriptors, getAgent } from "../agents";

// ── Tests ──────────────────────────────────────────────────────────

beforeEach(() => {
  vi.clearAllMocks();
});

describe("findResourceUsage", () => {
  it("returns empty array when no workflows exist", async () => {
    vi.mocked(getWorkflowDescriptors).mockResolvedValue([]);
    vi.mocked(getAgentDescriptors).mockResolvedValue([]);

    const result = await findResourceUsage("res1", "rulestore", "rulesets");
    expect(result).toEqual([]);
  });

  it("returns empty array when no workflows reference the resource", async () => {
    vi.mocked(getWorkflowDescriptors).mockResolvedValue([
      { resource: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1", name: "WF1", description: "", createdOn: 1, lastModifiedOn: 1 },
    ]);
    vi.mocked(getAgentDescriptors).mockResolvedValue([]);
    vi.mocked(getWorkflow).mockResolvedValue({
      workflowSteps: [
        { type: "eddi://ai.labs.output", extensions: {}, config: { uri: "eddi://ai.labs.output/outputstore/outputsets/out1?version=1" } },
      ],
    });

    const result = await findResourceUsage("res1", "rulestore", "rulesets");
    expect(result).toEqual([]);
  });

  it("finds a usage when workflow references the resource and agent references the workflow", async () => {
    vi.mocked(getWorkflowDescriptors).mockResolvedValue([
      { resource: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1", name: "My Workflow", description: "", createdOn: 1, lastModifiedOn: 1 },
    ]);
    vi.mocked(getAgentDescriptors).mockResolvedValue([
      { resource: "eddi://ai.labs.agent/agentstore/agents/a1?version=2", name: "My Agent", description: "", createdOn: 1, lastModifiedOn: 1 },
    ]);
    vi.mocked(getWorkflow).mockResolvedValue({
      workflowSteps: [
        { type: "eddi://ai.labs.rules", extensions: {}, config: { uri: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=3" } },
      ],
    });
    vi.mocked(getAgent).mockResolvedValue({
      workflows: ["eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1"],
    });

    const result = await findResourceUsage("res1", "rulestore", "rulesets");

    expect(result).toHaveLength(1);
    expect(result[0]).toEqual({
      workflowId: "wf1",
      workflowVersion: 1,
      workflowName: "My Workflow",
      agentId: "a1",
      agentVersion: 2,
      agentName: "My Agent",
    });
  });

  it("returns empty when workflow references resource but no agent references the workflow", async () => {
    vi.mocked(getWorkflowDescriptors).mockResolvedValue([
      { resource: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1", name: "WF1", description: "", createdOn: 1, lastModifiedOn: 1 },
    ]);
    vi.mocked(getAgentDescriptors).mockResolvedValue([
      { resource: "eddi://ai.labs.agent/agentstore/agents/a1?version=1", name: "Agent1", description: "", createdOn: 1, lastModifiedOn: 1 },
    ]);
    vi.mocked(getWorkflow).mockResolvedValue({
      workflowSteps: [
        { type: "eddi://ai.labs.rules", extensions: {}, config: { uri: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=1" } },
      ],
    });
    vi.mocked(getAgent).mockResolvedValue({
      workflows: ["eddi://ai.labs.workflow/workflowstore/workflows/wf-other?version=1"],
    });

    const result = await findResourceUsage("res1", "rulestore", "rulesets");
    expect(result).toEqual([]);
  });

  it("finds multiple usages across different workflows and agents", async () => {
    vi.mocked(getWorkflowDescriptors).mockResolvedValue([
      { resource: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1", name: "WF1", description: "", createdOn: 1, lastModifiedOn: 1 },
      { resource: "eddi://ai.labs.workflow/workflowstore/workflows/wf2?version=3", name: "WF2", description: "", createdOn: 1, lastModifiedOn: 1 },
    ]);
    vi.mocked(getAgentDescriptors).mockResolvedValue([
      { resource: "eddi://ai.labs.agent/agentstore/agents/a1?version=1", name: "Agent1", description: "", createdOn: 1, lastModifiedOn: 1 },
      { resource: "eddi://ai.labs.agent/agentstore/agents/a2?version=2", name: "Agent2", description: "", createdOn: 1, lastModifiedOn: 1 },
    ]);

    vi.mocked(getWorkflow).mockResolvedValue({
      workflowSteps: [
        { type: "eddi://ai.labs.rules", extensions: {}, config: { uri: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=1" } },
      ],
    });

    vi.mocked(getAgent).mockImplementation(async (id) => {
      if (id === "a1") return { workflows: ["eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1"] };
      if (id === "a2") return { workflows: ["eddi://ai.labs.workflow/workflowstore/workflows/wf2?version=3"] };
      return { workflows: [] };
    });

    const result = await findResourceUsage("res1", "rulestore", "rulesets");
    expect(result).toHaveLength(2);
    expect(result.map((u) => u.agentId)).toEqual(["a1", "a2"]);
  });

  it("fetches workflows and agents in parallel via Promise.all", async () => {
    vi.mocked(getWorkflowDescriptors).mockResolvedValue([]);
    vi.mocked(getAgentDescriptors).mockResolvedValue([]);

    await findResourceUsage("res1", "rulestore", "rulesets");

    expect(getWorkflowDescriptors).toHaveBeenCalledTimes(1);
    expect(getAgentDescriptors).toHaveBeenCalledTimes(1);
  });

  it("caches agent configs across workflow iterations", async () => {
    vi.mocked(getWorkflowDescriptors).mockResolvedValue([
      { resource: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1", name: "WF1", description: "", createdOn: 1, lastModifiedOn: 1 },
      { resource: "eddi://ai.labs.workflow/workflowstore/workflows/wf2?version=1", name: "WF2", description: "", createdOn: 1, lastModifiedOn: 1 },
    ]);
    vi.mocked(getAgentDescriptors).mockResolvedValue([
      { resource: "eddi://ai.labs.agent/agentstore/agents/a1?version=1", name: "Agent1", description: "", createdOn: 1, lastModifiedOn: 1 },
    ]);
    vi.mocked(getWorkflow).mockResolvedValue({
      workflowSteps: [
        { type: "eddi://ai.labs.rules", extensions: {}, config: { uri: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=1" } },
      ],
    });
    vi.mocked(getAgent).mockResolvedValue({
      workflows: [
        "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
        "eddi://ai.labs.workflow/workflowstore/workflows/wf2?version=1",
      ],
    });

    await findResourceUsage("res1", "rulestore", "rulesets");

    // Agent should be fetched only ONCE despite appearing in two workflow iterations
    expect(getAgent).toHaveBeenCalledTimes(1);
  });

  it("skips workflows that fail to load", async () => {
    vi.mocked(getWorkflowDescriptors).mockResolvedValue([
      { resource: "eddi://ai.labs.workflow/workflowstore/workflows/bad?version=1", name: "Bad", description: "", createdOn: 1, lastModifiedOn: 1 },
      { resource: "eddi://ai.labs.workflow/workflowstore/workflows/good?version=1", name: "Good", description: "", createdOn: 1, lastModifiedOn: 1 },
    ]);
    vi.mocked(getAgentDescriptors).mockResolvedValue([
      { resource: "eddi://ai.labs.agent/agentstore/agents/a1?version=1", name: "Agent1", description: "", createdOn: 1, lastModifiedOn: 1 },
    ]);
    vi.mocked(getWorkflow).mockImplementation(async (id) => {
      if (id === "bad") throw new Error("Not found");
      return {
        workflowSteps: [
          { type: "eddi://ai.labs.rules", extensions: {}, config: { uri: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=1" } },
        ],
      };
    });
    vi.mocked(getAgent).mockResolvedValue({
      workflows: ["eddi://ai.labs.workflow/workflowstore/workflows/good?version=1"],
    });

    const result = await findResourceUsage("res1", "rulestore", "rulesets");

    expect(result).toHaveLength(1);
    expect(result[0]!.workflowId).toBe("good");
  });

  it("uses resource name fallback when descriptor name is empty", async () => {
    vi.mocked(getWorkflowDescriptors).mockResolvedValue([
      { resource: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1", name: "", description: "", createdOn: 1, lastModifiedOn: 1 },
    ]);
    vi.mocked(getAgentDescriptors).mockResolvedValue([
      { resource: "eddi://ai.labs.agent/agentstore/agents/a1?version=1", name: "", description: "", createdOn: 1, lastModifiedOn: 1 },
    ]);
    vi.mocked(getWorkflow).mockResolvedValue({
      workflowSteps: [
        { type: "eddi://ai.labs.rules", extensions: {}, config: { uri: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=1" } },
      ],
    });
    vi.mocked(getAgent).mockResolvedValue({
      workflows: ["eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1"],
    });

    const result = await findResourceUsage("res1", "rulestore", "rulesets");

    expect(result[0]!.workflowName).toBe("wf1");
    expect(result[0]!.agentName).toBe("a1");
  });
});
