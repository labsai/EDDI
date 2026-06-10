import { describe, it, expect, vi, beforeEach } from "vitest";
import { cascadeSaveResource, cascadeVersionUpdate, type CascadeContext } from "../cascade-save";
import type { ResourceTypeConfig } from "../resources";
import type { WorkflowConfiguration } from "../workflows";

// ── Mocks ──────────────────────────────────────────────────────────

vi.mock("../resources", async () => {
  const actual = await vi.importActual<typeof import("../resources")>("../resources");
  return {
    ...actual,
    updateResource: vi.fn(),
  };
});

vi.mock("../workflows", async () => {
  const actual = await vi.importActual<typeof import("../workflows")>("../workflows");
  return {
    ...actual,
    getWorkflow: vi.fn(),
    updateWorkflow: vi.fn(),
  };
});

vi.mock("../agents", async () => {
  const actual = await vi.importActual<typeof import("../agents")>("../agents");
  return {
    ...actual,
    getAgent: vi.fn(),
    updateAgent: vi.fn(),
  };
});

import { updateResource } from "../resources";
import { getWorkflow, updateWorkflow } from "../workflows";
import { getAgent, updateAgent } from "../agents";

// ── Fixtures ───────────────────────────────────────────────────────

const RT: ResourceTypeConfig = {
  slug: "rules",
  store: "rulestore",
  plural: "rulesets",
  extension: "ai.labs.rules",
  labelKey: "resources.types.rules",
  icon: "GitBranch",
};

const CONTEXT: CascadeContext = {
  workflowId: "wf1",
  workflowVersion: 1,
  agentId: "agent1",
  agentVersion: 1,
};

function makeWorkflow(resourceUri: string): WorkflowConfiguration {
  return {
    workflowSteps: [
      {
        type: "eddi://ai.labs.rules",
        extensions: {},
        config: { uri: resourceUri },
      },
      {
        type: "eddi://ai.labs.output",
        extensions: {},
        config: { uri: "eddi://ai.labs.output/outputstore/outputsets/out1?version=1" },
      },
    ],
  };
}

// ── Tests ──────────────────────────────────────────────────────────

beforeEach(() => {
  vi.clearAllMocks();
});

describe("cascadeSaveResource", () => {
  describe("without cascade context (resource-only save)", () => {
    it("saves the resource and returns the new version", async () => {
      vi.mocked(updateResource).mockResolvedValue({
        location: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=2",
      });

      const result = await cascadeSaveResource(RT, "res1", 1, { data: "test" });

      expect(updateResource).toHaveBeenCalledWith(RT, "res1", 1, { data: "test" });
      expect(result).toEqual({ newResourceVersion: 2 });
    });

    it("does NOT call getWorkflow or updateWorkflow", async () => {
      vi.mocked(updateResource).mockResolvedValue({
        location: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=2",
      });

      await cascadeSaveResource(RT, "res1", 1, {});

      expect(getWorkflow).not.toHaveBeenCalled();
      expect(updateWorkflow).not.toHaveBeenCalled();
      expect(getAgent).not.toHaveBeenCalled();
      expect(updateAgent).not.toHaveBeenCalled();
    });
  });

  describe("with cascade context (full cascade)", () => {
    it("saves resource → updates workflow → updates agent", async () => {
      vi.mocked(updateResource).mockResolvedValue({
        location: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=2",
      });
      vi.mocked(getWorkflow).mockResolvedValue(
        makeWorkflow("eddi://ai.labs.rules/rulestore/rulesets/res1?version=1")
      );
      vi.mocked(updateWorkflow).mockResolvedValue({
        location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
      });
      vi.mocked(getAgent).mockResolvedValue({
        workflows: [
          "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
        ],
      });
      vi.mocked(updateAgent).mockResolvedValue({
        location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=2",
      });

      const result = await cascadeSaveResource(RT, "res1", 1, { data: "test" }, CONTEXT);

      expect(result).toEqual({
        newResourceVersion: 2,
        newWorkflowVersion: 2,
        newAgentVersion: 2,
      });

      // Verify workflow was updated with new resource URI
      const updatedWorkflow = vi.mocked(updateWorkflow).mock.calls[0]![2] as WorkflowConfiguration;
      expect(updatedWorkflow.workflowSteps[0]!.config!.uri).toBe(
        "eddi://ai.labs.rules/rulestore/rulesets/res1?version=2"
      );
      // Other extensions should be untouched
      expect(updatedWorkflow.workflowSteps[1]!.config!.uri).toBe(
        "eddi://ai.labs.output/outputstore/outputsets/out1?version=1"
      );

      // Verify agent was updated with new workflow URI
      const updatedAgent = vi.mocked(updateAgent).mock.calls[0]![2];
      expect(updatedAgent.workflows).toEqual([
        "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
      ]);
    });

    it("replaces resource URI by ID pattern regardless of current version", async () => {
      vi.mocked(updateResource).mockResolvedValue({
        location: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=4",
      });
      vi.mocked(getWorkflow).mockResolvedValue(
        makeWorkflow("eddi://ai.labs.rules/rulestore/rulesets/res1?version=5")
      );
      vi.mocked(updateWorkflow).mockResolvedValue({
        location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
      });
      vi.mocked(getAgent).mockResolvedValue({ workflows: [
        "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
      ] });
      vi.mocked(updateAgent).mockResolvedValue({
        location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=2",
      });

      const result = await cascadeSaveResource(RT, "res1", 3, {}, CONTEXT);

      const updatedWorkflow = vi.mocked(updateWorkflow).mock.calls[0]![2] as WorkflowConfiguration;
      expect(updatedWorkflow.workflowSteps[0]!.config!.uri).toBe(
        "eddi://ai.labs.rules/rulestore/rulesets/res1?version=4"
      );
      expect(result.newResourceVersion).toBe(4);
    });
  });

  describe("skipResourceSave option", () => {
    it("skips updateResource when skipResourceSave is true", async () => {
      vi.mocked(getWorkflow).mockResolvedValue(
        makeWorkflow("eddi://ai.labs.rules/rulestore/rulesets/res1?version=1")
      );
      vi.mocked(updateWorkflow).mockResolvedValue({
        location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
      });
      vi.mocked(getAgent).mockResolvedValue({ workflows: [
        "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
      ] });
      vi.mocked(updateAgent).mockResolvedValue({
        location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=2",
      });

      const result = await cascadeSaveResource(
        RT, "res1", 2, {}, CONTEXT, { skipResourceSave: true }
      );

      expect(updateResource).not.toHaveBeenCalled();
      expect(result.newResourceVersion).toBe(2);
      expect(getWorkflow).toHaveBeenCalledWith("wf1", 1);
      expect(updateWorkflow).toHaveBeenCalled();
      expect(updateAgent).toHaveBeenCalled();
    });

    it("returns just the version without cascading when no context + skipResourceSave", async () => {
      const result = await cascadeSaveResource(
        RT, "res1", 5, {}, undefined, { skipResourceSave: true }
      );

      expect(updateResource).not.toHaveBeenCalled();
      expect(result).toEqual({ newResourceVersion: 5 });
    });
  });

  describe("edge cases", () => {
    it("does not replace URIs of other resource types with same ID", async () => {
      const workflow: WorkflowConfiguration = {
        workflowSteps: [
          {
            type: "eddi://ai.labs.rules",
            extensions: {},
            config: { uri: "eddi://ai.labs.rules/rulestore/rulesets/shared-id?version=1" },
          },
          {
            type: "eddi://ai.labs.output",
            extensions: {},
            config: { uri: "eddi://ai.labs.output/outputstore/outputsets/shared-id?version=1" },
          },
        ],
      };

      vi.mocked(updateResource).mockResolvedValue({
        location: "eddi://ai.labs.rules/rulestore/rulesets/shared-id?version=2",
      });
      vi.mocked(getWorkflow).mockResolvedValue(workflow);
      vi.mocked(updateWorkflow).mockResolvedValue({
        location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
      });
      vi.mocked(getAgent).mockResolvedValue({ workflows: [
        "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
      ] });
      vi.mocked(updateAgent).mockResolvedValue({
        location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=2",
      });

      await cascadeSaveResource(RT, "shared-id", 1, {}, CONTEXT);

      const updatedWorkflow = vi.mocked(updateWorkflow).mock.calls[0]![2] as WorkflowConfiguration;
      expect(updatedWorkflow.workflowSteps[0]!.config!.uri).toBe(
        "eddi://ai.labs.rules/rulestore/rulesets/shared-id?version=2"
      );
      expect(updatedWorkflow.workflowSteps[1]!.config!.uri).toBe(
        "eddi://ai.labs.output/outputstore/outputsets/shared-id?version=1"
      );
    });

    it("uses rt.extension for URI scheme (propertysetter → ai.labs.property)", async () => {
      const RT_PROP: ResourceTypeConfig = {
        slug: "propertysetter",
        store: "propertysetterstore",
        plural: "propertysetters",
        extension: "ai.labs.property",
        labelKey: "resources.types.propertysetter",
        icon: "Settings",
      };

      vi.mocked(updateResource).mockResolvedValue({
        location: "eddi://ai.labs.property/propertysetterstore/propertysetters/ps1?version=2",
      });
      vi.mocked(getWorkflow).mockResolvedValue({
        workflowSteps: [
          {
            type: "eddi://ai.labs.property",
            extensions: {},
            config: { uri: "eddi://ai.labs.property/propertysetterstore/propertysetters/ps1?version=1" },
          },
        ],
      });
      vi.mocked(updateWorkflow).mockResolvedValue({
        location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
      });
      vi.mocked(getAgent).mockResolvedValue({ workflows: [
        "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
      ] });
      vi.mocked(updateAgent).mockResolvedValue({
        location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=2",
      });

      await cascadeSaveResource(RT_PROP, "ps1", 1, {}, CONTEXT);

      const updatedWorkflow = vi.mocked(updateWorkflow).mock.calls[0]![2] as WorkflowConfiguration;
      // Must use ai.labs.property (not ai.labs.propertysetter)
      expect(updatedWorkflow.workflowSteps[0]!.config!.uri).toBe(
        "eddi://ai.labs.property/propertysetterstore/propertysetters/ps1?version=2"
      );
    });

    it("uses rt.extension for URI scheme (snippets → ai.labs.snippet)", async () => {
      const RT_SNIPPETS: ResourceTypeConfig = {
        slug: "snippets",
        store: "snippetstore",
        plural: "snippets",
        extension: "ai.labs.snippet",
        labelKey: "resources.types.snippets",
        icon: "Puzzle",
      };

      vi.mocked(updateResource).mockResolvedValue({
        location: "eddi://ai.labs.snippet/snippetstore/snippets/sn1?version=2",
      });
      vi.mocked(getWorkflow).mockResolvedValue({
        workflowSteps: [
          {
            type: "eddi://ai.labs.snippet",
            extensions: {},
            config: { uri: "eddi://ai.labs.snippet/snippetstore/snippets/sn1?version=1" },
          },
        ],
      });
      vi.mocked(updateWorkflow).mockResolvedValue({
        location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
      });
      vi.mocked(getAgent).mockResolvedValue({ workflows: [
        "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
      ] });
      vi.mocked(updateAgent).mockResolvedValue({
        location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=2",
      });

      await cascadeSaveResource(RT_SNIPPETS, "sn1", 1, {}, CONTEXT);

      const updatedWorkflow = vi.mocked(updateWorkflow).mock.calls[0]![2] as WorkflowConfiguration;
      // Must use ai.labs.snippet (not ai.labs.snippets)
      expect(updatedWorkflow.workflowSteps[0]!.config!.uri).toBe(
        "eddi://ai.labs.snippet/snippetstore/snippets/sn1?version=2"
      );
    });
  });

  describe("CascadeContext interface", () => {
    it("uses workflowVersion (not legacy packageVersion)", () => {
      // Type-level verification: this would be a compile error if the field didn't exist
      const ctx: CascadeContext = {
        workflowId: "wf1",
        workflowVersion: 1,
        agentId: "a1",
        agentVersion: 1,
      };
      expect(ctx.workflowVersion).toBe(1);
      // @ts-expect-error — packageVersion should not exist on CascadeContext
      expect(ctx.packageVersion).toBeUndefined();
    });
  });

  describe("error propagation", () => {
    it("propagates updateResource errors", async () => {
      vi.mocked(updateResource).mockRejectedValue(new Error("save failed"));

      await expect(
        cascadeSaveResource(RT, "res1", 1, { data: "test" })
      ).rejects.toThrow("save failed");
    });

    it("propagates getWorkflow errors during cascade", async () => {
      vi.mocked(updateResource).mockResolvedValue({
        location: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=2",
      });
      vi.mocked(getWorkflow).mockRejectedValue(new Error("workflow not found"));

      await expect(
        cascadeSaveResource(RT, "res1", 1, {}, CONTEXT)
      ).rejects.toThrow("workflow not found");
    });

    it("propagates updateWorkflow errors during cascade", async () => {
      vi.mocked(updateResource).mockResolvedValue({
        location: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=2",
      });
      vi.mocked(getWorkflow).mockResolvedValue(
        makeWorkflow("eddi://ai.labs.rules/rulestore/rulesets/res1?version=1")
      );
      vi.mocked(updateWorkflow).mockRejectedValue(new Error("wf save failed"));

      await expect(
        cascadeSaveResource(RT, "res1", 1, {}, CONTEXT)
      ).rejects.toThrow("wf save failed");
    });

    it("propagates updateAgent errors during cascade", async () => {
      vi.mocked(updateResource).mockResolvedValue({
        location: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=2",
      });
      vi.mocked(getWorkflow).mockResolvedValue(
        makeWorkflow("eddi://ai.labs.rules/rulestore/rulesets/res1?version=1")
      );
      vi.mocked(updateWorkflow).mockResolvedValue({
        location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
      });
      vi.mocked(getAgent).mockResolvedValue({ workflows: [
        "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
      ] });
      vi.mocked(updateAgent).mockRejectedValue(new Error("agent save failed"));

      await expect(
        cascadeSaveResource(RT, "res1", 1, {}, CONTEXT)
      ).rejects.toThrow("agent save failed");
    });
  });

  describe("workflow with no matching config URI", () => {
    it("leaves extensions untouched when no URI matches the resource", async () => {
      const workflow: WorkflowConfiguration = {
        workflowSteps: [
          {
            type: "eddi://ai.labs.output",
            extensions: {},
            config: { uri: "eddi://ai.labs.output/outputstore/outputsets/out1?version=1" },
          },
        ],
      };

      vi.mocked(updateResource).mockResolvedValue({
        location: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=2",
      });
      vi.mocked(getWorkflow).mockResolvedValue(workflow);
      vi.mocked(updateWorkflow).mockResolvedValue({
        location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
      });
      vi.mocked(getAgent).mockResolvedValue({ workflows: [
        "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
      ] });
      vi.mocked(updateAgent).mockResolvedValue({
        location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=2",
      });

      await cascadeSaveResource(RT, "res1", 1, {}, CONTEXT);

      const updatedWorkflow = vi.mocked(updateWorkflow).mock.calls[0]![2] as WorkflowConfiguration;
      // Output URI should remain unchanged since it's not a "rules" resource
      expect(updatedWorkflow.workflowSteps[0]!.config!.uri).toBe(
        "eddi://ai.labs.output/outputstore/outputsets/out1?version=1"
      );
    });

    it("handles extensions with no config object", async () => {
      const workflow: WorkflowConfiguration = {
        workflowSteps: [
          {
            type: "eddi://ai.labs.parser",
            extensions: {},
            // No config
          },
        ],
      };

      vi.mocked(updateResource).mockResolvedValue({
        location: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=2",
      });
      vi.mocked(getWorkflow).mockResolvedValue(workflow);
      vi.mocked(updateWorkflow).mockResolvedValue({
        location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
      });
      vi.mocked(getAgent).mockResolvedValue({ workflows: [
        "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
      ] });
      vi.mocked(updateAgent).mockResolvedValue({
        location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=2",
      });

      await cascadeSaveResource(RT, "res1", 1, {}, CONTEXT);

      const updatedWorkflow = vi.mocked(updateWorkflow).mock.calls[0]![2] as WorkflowConfiguration;
      // Extension without config should pass through unchanged
      expect(updatedWorkflow.workflowSteps[0]!.config).toBeUndefined();
    });
  });
});

describe("cascadeVersionUpdate", () => {
  it("updates workflow and agent references without saving resource", async () => {
    vi.mocked(getWorkflow).mockResolvedValue(
      makeWorkflow("eddi://ai.labs.rules/rulestore/rulesets/res1?version=1")
    );
    vi.mocked(updateWorkflow).mockResolvedValue({
      location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=3",
    });
    vi.mocked(getAgent).mockResolvedValue({ workflows: [
      "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
    ] });
    vi.mocked(updateAgent).mockResolvedValue({
      location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=3",
    });

    const result = await cascadeVersionUpdate(RT, "res1", 1, 2, CONTEXT);

    expect(updateResource).not.toHaveBeenCalled();
    expect(result).toEqual({
      newWorkflowVersion: 3,
      newAgentVersion: 3,
    });
  });

  it("replaces the resource URI in the workflow config", async () => {
    vi.mocked(getWorkflow).mockResolvedValue(
      makeWorkflow("eddi://ai.labs.rules/rulestore/rulesets/res1?version=1")
    );
    vi.mocked(updateWorkflow).mockResolvedValue({
      location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
    });
    vi.mocked(getAgent).mockResolvedValue({ workflows: [
      "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
    ] });
    vi.mocked(updateAgent).mockResolvedValue({
      location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=2",
    });

    await cascadeVersionUpdate(RT, "res1", 1, 5, CONTEXT);

    const updatedWorkflow = vi.mocked(updateWorkflow).mock.calls[0]![2] as WorkflowConfiguration;
    expect(updatedWorkflow.workflowSteps[0]!.config!.uri).toBe(
      "eddi://ai.labs.rules/rulestore/rulesets/res1?version=5"
    );
  });

  it("updates the agent's workflow URI reference", async () => {
    vi.mocked(getWorkflow).mockResolvedValue(
      makeWorkflow("eddi://ai.labs.rules/rulestore/rulesets/res1?version=1")
    );
    vi.mocked(updateWorkflow).mockResolvedValue({
      location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=4",
    });
    vi.mocked(getAgent).mockResolvedValue({ workflows: [
      "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
    ] });
    vi.mocked(updateAgent).mockResolvedValue({
      location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=4",
    });

    await cascadeVersionUpdate(RT, "res1", 1, 3, CONTEXT);

    const updatedAgent = vi.mocked(updateAgent).mock.calls[0]![2];
    expect(updatedAgent.workflows).toEqual([
      "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=4",
    ]);
  });

  it("propagates getWorkflow errors", async () => {
    vi.mocked(getWorkflow).mockRejectedValue(new Error("not found"));

    await expect(
      cascadeVersionUpdate(RT, "res1", 1, 2, CONTEXT)
    ).rejects.toThrow("not found");
  });

  it("propagates updateAgent errors", async () => {
    vi.mocked(getWorkflow).mockResolvedValue(
      makeWorkflow("eddi://ai.labs.rules/rulestore/rulesets/res1?version=1")
    );
    vi.mocked(updateWorkflow).mockResolvedValue({
      location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
    });
    vi.mocked(getAgent).mockResolvedValue({ workflows: [
      "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
    ] });
    vi.mocked(updateAgent).mockRejectedValue(new Error("agent update failed"));

    await expect(
      cascadeVersionUpdate(RT, "res1", 1, 2, CONTEXT)
    ).rejects.toThrow("agent update failed");
  });
});
