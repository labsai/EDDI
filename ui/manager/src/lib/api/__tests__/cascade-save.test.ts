import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  cascadeSaveResource,
  cascadeVersionUpdate,
  cascadePartialResult,
  CascadeReferenceError,
  CascadeSaveError,
  nextCascadeContext,
  type CascadeContext,
} from "../cascade-save";
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
    getWorkflowCurrentVersion: vi.fn(),
    updateWorkflow: vi.fn(),
  };
});

vi.mock("../agents", async () => {
  const actual = await vi.importActual<typeof import("../agents")>("../agents");
  return {
    ...actual,
    getAgent: vi.fn(),
    getAgentCurrentVersion: vi.fn(),
    updateAgent: vi.fn(),
  };
});

import { updateResource } from "../resources";
import { getWorkflow, getWorkflowCurrentVersion, updateWorkflow } from "../workflows";
import { getAgent, getAgentCurrentVersion, updateAgent } from "../agents";

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
    /*
     * These used to assert that the cascade re-saved the workflow unchanged —
     * and then re-saved the agent, producing a new agent version WITHOUT the
     * edit that Save & Deploy went on to deploy. Now the reference is checked
     * before anything is written.
     */
    it("refuses before writing anything when no step references the resource", async () => {
      const workflow: WorkflowConfiguration = {
        workflowSteps: [
          {
            type: "eddi://ai.labs.output",
            extensions: {},
            config: { uri: "eddi://ai.labs.output/outputstore/outputsets/out1?version=1" },
          },
          // No config at all — must not trip the matcher either.
          { type: "eddi://ai.labs.parser", extensions: {} } as WorkflowConfiguration["workflowSteps"][number],
        ],
      };
      vi.mocked(getWorkflow).mockResolvedValue(workflow);
      vi.mocked(getAgent).mockResolvedValue({ workflows: [
        "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
      ] });

      await expect(cascadeSaveResource(RT, "res1", 1, {}, CONTEXT)).rejects.toBeInstanceOf(
        CascadeReferenceError,
      );
      expect(updateResource).not.toHaveBeenCalled();
      expect(updateWorkflow).not.toHaveBeenCalled();
      expect(updateAgent).not.toHaveBeenCalled();
    });

    it("does not treat an id that merely starts with the resource id as a reference", async () => {
      vi.mocked(getWorkflow).mockResolvedValue(
        makeWorkflow("eddi://ai.labs.rules/rulestore/rulesets/res1-other?version=1"),
      );
      vi.mocked(getAgent).mockResolvedValue({ workflows: [
        "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
      ] });

      await expect(cascadeSaveResource(RT, "res1", 1, {}, CONTEXT)).rejects.toBeInstanceOf(
        CascadeReferenceError,
      );
      expect(updateResource).not.toHaveBeenCalled();
    });

    it("passes steps with no config object through unchanged", async () => {
      const workflow = {
        workflowSteps: [
          {
            type: "eddi://ai.labs.parser",
            extensions: {},
            // No config
          },
          {
            type: "eddi://ai.labs.rules",
            extensions: {},
            config: { uri: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=1" },
          },
        ],
      } as WorkflowConfiguration;

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
      expect(updatedWorkflow.workflowSteps[0]!.config).toBeUndefined();
      expect(updatedWorkflow.workflowSteps[1]!.config!.uri).toBe(
        "eddi://ai.labs.rules/rulestore/rulesets/res1?version=2",
      );
    });
  });

  describe("agent workflow reference", () => {
    function resourceAndWorkflowSucceed() {
      vi.mocked(updateResource).mockResolvedValue({
        location: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=2",
      });
      vi.mocked(getWorkflow).mockResolvedValue(
        makeWorkflow("eddi://ai.labs.rules/rulestore/rulesets/res1?version=1"),
      );
      vi.mocked(updateWorkflow).mockResolvedValue({
        location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
      });
      vi.mocked(updateAgent).mockResolvedValue({
        location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=2",
      });
    }

    it("replaces a workflow reference spelled differently from the canonical URI", async () => {
      resourceAndWorkflowSucceed();
      // Same id and version, but a different spelling: an exact string match
      // replaced nothing here and saved the agent unchanged.
      vi.mocked(getAgent).mockResolvedValue({ workflows: [
        "eddi://ai.labs.package/packagestore/packages/wf1?version=1",
        "eddi://ai.labs.workflow/workflowstore/workflows/other?version=1",
      ] });

      const result = await cascadeSaveResource(RT, "res1", 1, {}, CONTEXT);

      const savedAgent = vi.mocked(updateAgent).mock.calls[0]![2];
      expect(savedAgent.workflows).toEqual([
        "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
        "eddi://ai.labs.workflow/workflowstore/workflows/other?version=1",
      ]);
      expect(result.newAgentVersion).toBe(2);
    });

    it("refuses before writing anything when the agent references another workflow version", async () => {
      resourceAndWorkflowSucceed();
      vi.mocked(getAgent).mockResolvedValue({ workflows: [
        "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=3",
      ] });

      await expect(cascadeSaveResource(RT, "res1", 1, {}, CONTEXT)).rejects.toThrow(/version 3/);
      expect(updateResource).not.toHaveBeenCalled();
      expect(updateWorkflow).not.toHaveBeenCalled();
      expect(updateAgent).not.toHaveBeenCalled();
    });

    it("refuses when the agent does not reference the workflow at all", async () => {
      resourceAndWorkflowSucceed();
      vi.mocked(getAgent).mockResolvedValue({ workflows: [] });

      await expect(cascadeSaveResource(RT, "res1", 1, {}, CONTEXT)).rejects.toBeInstanceOf(
        CascadeReferenceError,
      );
      expect(updateResource).not.toHaveBeenCalled();
    });

    it("refuses in cascadeVersionUpdate too, before the workflow is written", async () => {
      resourceAndWorkflowSucceed();
      vi.mocked(getAgent).mockResolvedValue({ workflows: [] });

      await expect(cascadeVersionUpdate(RT, "res1", 1, 2, CONTEXT)).rejects.toBeInstanceOf(
        CascadeReferenceError,
      );
      expect(updateWorkflow).not.toHaveBeenCalled();
    });
  });

  describe("a hop failing after the resource was written", () => {
    beforeEach(() => {
      vi.mocked(updateResource).mockResolvedValue({
        location: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=2",
      });
      vi.mocked(getWorkflow).mockResolvedValue(
        makeWorkflow("eddi://ai.labs.rules/rulestore/rulesets/res1?version=1"),
      );
      vi.mocked(getAgent).mockResolvedValue({ workflows: [
        "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
      ] });
    });

    it("reports the resource version that now exists when the workflow hop fails", async () => {
      const cause = new Error("wf conflict");
      vi.mocked(updateWorkflow).mockRejectedValue(cause);

      const err = await cascadeSaveResource(RT, "res1", 1, {}, CONTEXT).catch((e: unknown) => e);

      expect(err).toBeInstanceOf(CascadeSaveError);
      expect((err as CascadeSaveError).message).toBe("wf conflict");
      expect((err as CascadeSaveError).cause).toBe(cause);
      expect(cascadePartialResult(err)).toEqual({ newResourceVersion: 2, retryContext: CONTEXT });
    });

    it("reports the resource and workflow versions when the agent hop fails", async () => {
      vi.mocked(updateWorkflow).mockResolvedValue({
        location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=5",
      });
      vi.mocked(updateAgent).mockRejectedValue(new Error("agent conflict"));

      const err = await cascadeSaveResource(RT, "res1", 1, {}, CONTEXT).catch((e: unknown) => e);

      expect(cascadePartialResult(err)).toEqual({
        newResourceVersion: 2,
        newWorkflowVersion: 5,
        retryContext: { ...CONTEXT, workflowVersion: 5, agentWorkflowVersion: 1 },
      });
    });

    it("a retry with the reported context repoints the agent from the version it still references", async () => {
      vi.mocked(updateWorkflow).mockResolvedValueOnce({
        location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=5",
      });
      vi.mocked(updateAgent).mockRejectedValueOnce(new Error("agent conflict"));
      const err = await cascadeSaveResource(RT, "res1", 1, {}, CONTEXT).catch((e: unknown) => e);
      const partial = cascadePartialResult(err)!;

      // The retry: the resource at its new version, the workflow at v5, the
      // agent still at v1 and still referencing workflow v1.
      vi.mocked(updateResource).mockResolvedValueOnce({
        location: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=3",
      });
      vi.mocked(getWorkflow).mockResolvedValueOnce(
        makeWorkflow("eddi://ai.labs.rules/rulestore/rulesets/res1?version=2"),
      );
      vi.mocked(updateWorkflow).mockResolvedValueOnce({
        location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=6",
      });
      vi.mocked(updateAgent).mockResolvedValueOnce({
        location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=2",
      });

      const result = await cascadeSaveResource(
        RT, "res1", partial.newResourceVersion!, {}, partial.retryContext,
      );

      expect(updateResource).toHaveBeenLastCalledWith(RT, "res1", 2, {});
      expect(updateWorkflow).toHaveBeenLastCalledWith("wf1", 5, expect.anything());
      expect(vi.mocked(updateAgent).mock.lastCall![1]).toBe(1);
      expect(vi.mocked(updateAgent).mock.lastCall![2].workflows).toEqual([
        "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=6",
      ]);
      expect(result).toEqual({ newResourceVersion: 3, newWorkflowVersion: 6, newAgentVersion: 2 });
      expect(nextCascadeContext(partial.retryContext!, result)).toEqual({
        workflowId: "wf1",
        workflowVersion: 6,
        agentId: "agent1",
        agentVersion: 2,
      });
    });

    it("reports only the workflow version from cascadeVersionUpdate", async () => {
      vi.mocked(updateWorkflow).mockResolvedValue({
        location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=5",
      });
      vi.mocked(updateAgent).mockRejectedValue(new Error("agent conflict"));

      const err = await cascadeVersionUpdate(RT, "res1", 1, 2, CONTEXT).catch((e: unknown) => e);

      expect(cascadePartialResult(err)).toEqual({
        newWorkflowVersion: 5,
        retryContext: { ...CONTEXT, workflowVersion: 5, agentWorkflowVersion: 1 },
      });
    });

    it("leaves a failure with nothing written unwrapped", async () => {
      vi.mocked(updateResource).mockRejectedValue(new Error("resource conflict"));

      const err = await cascadeSaveResource(RT, "res1", 1, {}, CONTEXT).catch((e: unknown) => e);

      expect(err).not.toBeInstanceOf(CascadeSaveError);
      expect(cascadePartialResult(err)).toBeNull();
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

/**
 * A cascade writes the version it just created into the PARENT document: the
 * resource version into the workflow, the workflow version into the agent.
 *
 * The version came from `parseResourceUri`, which ends with
 * `parseInt(url.searchParams.get("version") || "1", 10)` and so cannot tell
 * "version 1" from "no version at all". A save whose response carried no
 * `Location` header therefore resolved to **1** and the cascade wrote
 * `?version=1` into the parent — silent data corruption, reported to the user
 * as a successful save, leaving their agent pointing at the first revision.
 *
 * Failing loudly is the right answer: the save visibly did not work and can be
 * retried, rather than being discovered later as an agent running old config.
 */
describe("a save that does not report its new version", () => {
  /** What `api-client` produces when the response carries no Location header. */
  const NO_LOCATION = {} as { location: string };

  function workflowAndAgentSucceed() {
    vi.mocked(getWorkflow).mockResolvedValue(
      makeWorkflow("eddi://ai.labs.rules/rulestore/rulesets/res1?version=1"),
    );
    vi.mocked(updateWorkflow).mockResolvedValue({
      location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
    });
    vi.mocked(getAgent).mockResolvedValue({
      name: "agent",
      workflows: ["eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1"],
    } as never);
    vi.mocked(updateAgent).mockResolvedValue({
      location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=2",
    });
  }

  it("fails the resource hop instead of writing ?version=1 into the workflow", async () => {
    vi.mocked(updateResource).mockResolvedValue(NO_LOCATION);
    workflowAndAgentSucceed();

    await expect(cascadeSaveResource(RT, "res1", 1, {}, CONTEXT)).rejects.toThrow(/new version/i);

    // The parent must be left alone entirely — a partial cascade is the bug.
    expect(updateWorkflow).not.toHaveBeenCalled();
    expect(updateAgent).not.toHaveBeenCalled();
  });

  it("fails the workflow hop instead of writing ?version=1 into the agent", async () => {
    workflowAndAgentSucceed();
    vi.mocked(updateResource).mockResolvedValue({
      location: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=2",
    });
    vi.mocked(updateWorkflow).mockResolvedValue(NO_LOCATION);

    await expect(cascadeSaveResource(RT, "res1", 1, {}, CONTEXT)).rejects.toThrow(/new version/i);

    expect(updateAgent).not.toHaveBeenCalled();
  });

  it("fails the agent hop rather than reporting version 1", async () => {
    workflowAndAgentSucceed();
    vi.mocked(updateResource).mockResolvedValue({
      location: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=2",
    });
    vi.mocked(updateAgent).mockResolvedValue(NO_LOCATION);

    await expect(cascadeSaveResource(RT, "res1", 1, {}, CONTEXT)).rejects.toThrow(/new version/i);
  });

  it("fails cascadeVersionUpdate the same way", async () => {
    workflowAndAgentSucceed();
    vi.mocked(updateWorkflow).mockResolvedValue(NO_LOCATION);

    await expect(cascadeVersionUpdate(RT, "res1", 1, 2, CONTEXT)).rejects.toThrow(/new version/i);

    expect(updateAgent).not.toHaveBeenCalled();
  });

  it("a Location with no version parameter is treated as no version", async () => {
    // Not the same as version=1: this is a Location we cannot read.
    vi.mocked(updateResource).mockResolvedValue({
      location: "eddi://ai.labs.rules/rulestore/rulesets/res1",
    });
    workflowAndAgentSucceed();

    await expect(cascadeSaveResource(RT, "res1", 1, {}, CONTEXT)).rejects.toThrow(/new version/i);
  });

  /**
   * The parser matched a numeric PREFIX, so `version=2.5` and `version=2abc`
   * both produced 2 — writing a version into the parent that the server never
   * reported, which is the exact failure the strict parse exists to prevent.
   */
  it.each(["version=2.5", "version=2abc", "version=", "version=abc", "version=-1"])(
    "rejects a malformed %s rather than reading a prefix",
    async (query) => {
      vi.mocked(updateResource).mockResolvedValue({
        location: `eddi://ai.labs.rules/rulestore/rulesets/res1?${query}`,
      });
      workflowAndAgentSucceed();

      await expect(cascadeSaveResource(RT, "res1", 1, {}, CONTEXT)).rejects.toThrow(/new version/i);
    },
  );

  /**
   * A Location header may be a relative path with no origin. `new URL(location)`
   * alone throws on those, which would turn every relative Location into a
   * failed save — so the parser normalises with a dummy base, as
   * `parseResourceUri` does.
   */
  it("reads a relative Location header", async () => {
    vi.mocked(updateResource).mockResolvedValue({
      location: "/rulestore/rulesets/res1?version=4",
    });
    workflowAndAgentSucceed();

    const result = await cascadeSaveResource(RT, "res1", 1, {}, CONTEXT);

    expect(result.newResourceVersion).toBe(4);
  });

  it("still reads an explicit version=1 as version 1", async () => {
    // The whole point is telling "1" apart from "absent" — 1 is a real version.
    vi.mocked(updateResource).mockResolvedValue({
      location: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=1",
    });
    workflowAndAgentSucceed();

    const result = await cascadeSaveResource(RT, "res1", 1, {}, CONTEXT);

    expect(result.newResourceVersion).toBe(1);
  });
});

describe("a parent superseded elsewhere", () => {
  beforeEach(() => {
    vi.mocked(getWorkflow).mockResolvedValue(
      makeWorkflow("eddi://ai.labs.rules/rulestore/rulesets/res1?version=1"),
    );
    vi.mocked(getAgent).mockResolvedValue({ workflows: [
      "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
    ] });
    vi.mocked(getWorkflowCurrentVersion).mockResolvedValue(1);
    vi.mocked(getAgentCurrentVersion).mockResolvedValue(1);
  });

  it.each([
    ["agent", () => vi.mocked(getAgentCurrentVersion).mockResolvedValue(4), "agentChanged"],
    ["workflow", () => vi.mocked(getWorkflowCurrentVersion).mockResolvedValue(3), "workflowChanged"],
  ])("refuses before writing when the %s moved on", async (_what, arrange, code) => {
    arrange();

    const err = await cascadeSaveResource(RT, "res1", 1, {}, CONTEXT).catch((e: unknown) => e);

    expect(err).toBeInstanceOf(CascadeReferenceError);
    expect((err as CascadeReferenceError).code).toBe(code);
    expect(updateResource).not.toHaveBeenCalled();
    expect(updateWorkflow).not.toHaveBeenCalled();
    expect(updateAgent).not.toHaveBeenCalled();
  });

  it("a retry after an agent-hop 409 from a stale agent writes nothing more", async () => {
    // First attempt: the agent moved on between the check and the PUT.
    vi.mocked(updateResource).mockResolvedValueOnce({
      location: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=2",
    });
    vi.mocked(updateWorkflow).mockResolvedValueOnce({
      location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
    });
    vi.mocked(updateAgent).mockRejectedValueOnce(Object.assign(new Error("Conflict"), { status: 409 }));
    const err = await cascadeSaveResource(RT, "res1", 1, {}, CONTEXT).catch((e: unknown) => e);
    const partial = cascadePartialResult(err)!;

    // The retry, from the context the failure handed out: the agent is now v2.
    vi.mocked(getWorkflowCurrentVersion).mockResolvedValue(2);
    vi.mocked(getAgentCurrentVersion).mockResolvedValue(2);
    vi.mocked(updateResource).mockClear();
    vi.mocked(updateWorkflow).mockClear();

    await expect(
      cascadeSaveResource(RT, "res1", partial.newResourceVersion!, {}, partial.retryContext),
    ).rejects.toMatchObject({ code: "agentChanged" });
    expect(updateResource).not.toHaveBeenCalled();
    expect(updateWorkflow).not.toHaveBeenCalled();
  });

  it("an unreadable current version does not block the save", async () => {
    vi.mocked(getAgentCurrentVersion).mockRejectedValue(new Error("404"));
    vi.mocked(getWorkflowCurrentVersion).mockResolvedValue(undefined as unknown as number);
    vi.mocked(updateResource).mockResolvedValue({
      location: "eddi://ai.labs.rules/rulestore/rulesets/res1?version=2",
    });
    vi.mocked(updateWorkflow).mockResolvedValue({
      location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
    });
    vi.mocked(updateAgent).mockResolvedValue({
      location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=2",
    });

    await expect(cascadeSaveResource(RT, "res1", 1, {}, CONTEXT)).resolves.toMatchObject({
      newAgentVersion: 2,
    });
  });
});
