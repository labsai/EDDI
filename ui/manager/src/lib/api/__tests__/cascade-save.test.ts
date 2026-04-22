import { describe, it, expect, vi, beforeEach } from "vitest";
import { cascadeSaveResource, type CascadeContext } from "../cascade-save";
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
  labelKey: "resources.types.rules",
  icon: "GitBranch",
};

const CONTEXT: CascadeContext = {
  workflowId: "wf1",
  packageVersion: 1,
  agentId: "agent1",
  agentVersion: 1,
};

function makeWorkflow(resourceUri: string): WorkflowConfiguration {
  return {
    workflowSteps: [
      {
        type: "ai.labs.rules",
        extensions: {},
        config: { uri: resourceUri },
      },
      {
        type: "ai.labs.output",
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
            type: "ai.labs.rules",
            extensions: {},
            config: { uri: "eddi://ai.labs.rules/rulestore/rulesets/shared-id?version=1" },
          },
          {
            type: "ai.labs.output",
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
  });
});
