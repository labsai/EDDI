import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { cascadeSaveResource, type CascadeContext } from "../cascade-save";
import { RESOURCE_TYPES } from "../resources";

const llmType = RESOURCE_TYPES.find((rt) => rt.slug === "llm")!;
const rulesType = RESOURCE_TYPES.find((rt) => rt.slug === "rules")!;

// ── Helpers ──────────────────────────────────────────────────────────

/** Builds a realistic workflow config that references a resource at a given version */
function buildWorkflow(resourceStore: string, resourcePlural: string, resourceId: string, version: number) {
  return {
    workflowSteps: [
      {
        type: "ai.labs.parser",
        extensions: {},
        config: { uri: "eddi://ai.labs.parser/parserstore/parsers/parser1?version=1" },
      },
      {
        type: "ai.labs.llm",
        extensions: {},
        config: { uri: `eddi://ai.labs.${resourceStore === "llmstore" ? "llm" : "rules"}/${resourceStore}/${resourcePlural}/${resourceId}?version=${version}` },
      },
    ],
  };
}

/** Builds a realistic agent config referencing a workflow */
function buildAgent(workflowId: string, workflowVersion: number) {
  return {
    workflows: [`eddi://ai.labs.workflow/workflowstore/workflows/${workflowId}?version=${workflowVersion}`],
    channels: [],
  };
}

// ── Captured requests (for asserting the exact versions sent to backend) ──

interface CapturedRequest {
  method: string;
  url: string;
  body?: unknown;
}

let capturedRequests: CapturedRequest[] = [];

function installCaptureHandlers(opts: {
  resourceStore: string;
  resourcePlural: string;
  resourceId: string;
  workflowId: string;
  agentId: string;
  initialResourceVersion: number;
  initialWorkflowVersion: number;
  initialAgentVersion: number;
}) {
  const { resourceStore, resourcePlural, resourceId, workflowId, agentId } = opts;
  let { initialResourceVersion, initialWorkflowVersion, initialAgentVersion } = opts;

  server.use(
    // PUT resource → returns new version (current + 1)
    http.put(`*/${resourceStore}/${resourcePlural}/${resourceId}`, async ({ request }) => {
      const url = new URL(request.url);
      const reqVersion = parseInt(url.searchParams.get("version") ?? "1", 10);
      const body = await request.json();
      capturedRequests.push({ method: "PUT", url: url.pathname + url.search, body });

      // Simulate backend: reject if version doesn't match current
      if (reqVersion !== initialResourceVersion) {
        return HttpResponse.json(
          { message: `Resource not found. (id=${resourceId}, version=${reqVersion})` },
          { status: 404 }
        );
      }

      const newVersion = reqVersion + 1;
      initialResourceVersion = newVersion;
      return new HttpResponse(null, {
        status: 200,
        headers: {
          Location: `eddi://ai.labs.llm/${resourceStore}/${resourcePlural}/${resourceId}?version=${newVersion}`,
        },
      });
    }),

    // GET workflow
    http.get(`*/workflowstore/workflows/${workflowId}`, ({ request }) => {
      const url = new URL(request.url);
      const reqVersion = parseInt(url.searchParams.get("version") ?? "1", 10);
      capturedRequests.push({ method: "GET", url: url.pathname + url.search });
      // Return workflow referencing current resource version
      return HttpResponse.json(
        buildWorkflow(resourceStore, resourcePlural, resourceId, reqVersion === initialWorkflowVersion ? initialResourceVersion - 1 : initialResourceVersion)
      );
    }),

    // PUT workflow → returns new version
    http.put(`*/workflowstore/workflows/${workflowId}`, async ({ request }) => {
      const url = new URL(request.url);
      const reqVersion = parseInt(url.searchParams.get("version") ?? "1", 10);
      const body = await request.json();
      capturedRequests.push({ method: "PUT-WF", url: url.pathname + url.search, body });

      if (reqVersion !== initialWorkflowVersion) {
        return HttpResponse.json(
          { message: `Resource not found. (id=${workflowId}, version=${reqVersion})` },
          { status: 404 }
        );
      }

      const newVersion = reqVersion + 1;
      initialWorkflowVersion = newVersion;
      return new HttpResponse(null, {
        status: 200,
        headers: {
          Location: `eddi://ai.labs.workflow/workflowstore/workflows/${workflowId}?version=${newVersion}`,
        },
      });
    }),

    // GET agent
    http.get(`*/agentstore/agents/${agentId}`, ({ request }) => {
      const url = new URL(request.url);
      capturedRequests.push({ method: "GET-AGENT", url: url.pathname + url.search });
      return HttpResponse.json(buildAgent(workflowId, initialWorkflowVersion));
    }),

    // PUT agent → returns new version
    http.put(`*/agentstore/agents/${agentId}`, async ({ request }) => {
      const url = new URL(request.url);
      const reqVersion = parseInt(url.searchParams.get("version") ?? "1", 10);
      const body = await request.json();
      capturedRequests.push({ method: "PUT-AGENT", url: url.pathname + url.search, body });

      if (reqVersion !== initialAgentVersion) {
        return HttpResponse.json(
          { message: `Resource not found. (id=${agentId}, version=${reqVersion})` },
          { status: 404 }
        );
      }

      const newVersion = reqVersion + 1;
      initialAgentVersion = newVersion;
      return new HttpResponse(null, {
        status: 200,
        headers: {
          Location: `eddi://ai.labs.agent/agentstore/agents/${agentId}?version=${newVersion}`,
        },
      });
    }),
  );
}

// ── Tests ────────────────────────────────────────────────────────────

beforeEach(() => {
  capturedRequests = [];
});

describe("cascadeSaveResource", () => {
  describe("without cascade context (Path B)", () => {
    it("saves only the resource and returns new version", async () => {
      server.use(
        http.put("*/llmstore/llms/llm1", ({ request }) => {
          const url = new URL(request.url);
          capturedRequests.push({ method: "PUT", url: url.pathname + url.search });
          return new HttpResponse(null, {
            status: 200,
            headers: {
              Location: "eddi://ai.labs.llm/llmstore/llms/llm1?version=2",
            },
          });
        }),
      );

      const result = await cascadeSaveResource(llmType, "llm1", 1, { tasks: [] });

      expect(result.newResourceVersion).toBe(2);
      expect(result.newWorkflowVersion).toBeUndefined();
      expect(result.newAgentVersion).toBeUndefined();
      expect(capturedRequests).toHaveLength(1);
      expect(capturedRequests[0]!.method).toBe("PUT");
    });
  });

  describe("with cascade context (Path A)", () => {
    const RESOURCE_ID = "llm-test-1";
    const WORKFLOW_ID = "wf-test-1";
    const AGENT_ID = "agent-test-1";

    it("cascades through resource → workflow → agent", async () => {
      installCaptureHandlers({
        resourceStore: "llmstore",
        resourcePlural: "llms",
        resourceId: RESOURCE_ID,
        workflowId: WORKFLOW_ID,
        agentId: AGENT_ID,
        initialResourceVersion: 1,
        initialWorkflowVersion: 1,
        initialAgentVersion: 1,
      });

      const context: CascadeContext = {
        workflowId: WORKFLOW_ID,
        workflowVersion: 1,
        agentId: AGENT_ID,
        agentVersion: 1,
      };

      const result = await cascadeSaveResource(
        llmType, RESOURCE_ID, 1, { tasks: [] }, context
      );

      expect(result.newResourceVersion).toBe(2);
      expect(result.newWorkflowVersion).toBe(2);
      expect(result.newAgentVersion).toBe(2);

      // Verify the exact sequence of requests
      expect(capturedRequests).toHaveLength(5); // PUT res, GET wf, PUT wf, GET agent, PUT agent
      expect(capturedRequests[0]!.method).toBe("PUT");     // resource save
      expect(capturedRequests[1]!.method).toBe("GET");      // get workflow
      expect(capturedRequests[2]!.method).toBe("PUT-WF");   // update workflow
      expect(capturedRequests[3]!.method).toBe("GET-AGENT"); // get agent
      expect(capturedRequests[4]!.method).toBe("PUT-AGENT"); // update agent
    });

    it("returns correct versions for all three levels", async () => {
      installCaptureHandlers({
        resourceStore: "llmstore",
        resourcePlural: "llms",
        resourceId: RESOURCE_ID,
        workflowId: WORKFLOW_ID,
        agentId: AGENT_ID,
        initialResourceVersion: 3,
        initialWorkflowVersion: 5,
        initialAgentVersion: 7,
      });

      const context: CascadeContext = {
        workflowId: WORKFLOW_ID,
        workflowVersion: 5,
        agentId: AGENT_ID,
        agentVersion: 7,
      };

      const result = await cascadeSaveResource(
        llmType, RESOURCE_ID, 3, { tasks: [] }, context
      );

      // Each version increments by 1
      expect(result.newResourceVersion).toBe(4);
      expect(result.newWorkflowVersion).toBe(6);
      expect(result.newAgentVersion).toBe(8);
    });

    /**
     * REGRESSION TEST: This is the exact scenario that caused the original bug.
     *
     * When a user saves twice in a row, the second save must use the
     * NEW versions returned from the first save. If the caller still
     * passes the original versions, the backend rejects the request
     * because those versions are now in history (not current).
     */
    it("second cascade save fails if context versions are stale (regression)", async () => {
      installCaptureHandlers({
        resourceStore: "llmstore",
        resourcePlural: "llms",
        resourceId: RESOURCE_ID,
        workflowId: WORKFLOW_ID,
        agentId: AGENT_ID,
        initialResourceVersion: 1,
        initialWorkflowVersion: 1,
        initialAgentVersion: 1,
      });

      // First save — succeeds
      const context: CascadeContext = {
        workflowId: WORKFLOW_ID,
        workflowVersion: 1,
        agentId: AGENT_ID,
        agentVersion: 1,
      };

      const result1 = await cascadeSaveResource(
        llmType, RESOURCE_ID, 1, { tasks: [] }, context
      );
      expect(result1.newResourceVersion).toBe(2);
      expect(result1.newWorkflowVersion).toBe(2);
      expect(result1.newAgentVersion).toBe(2);

      // Second save with STALE context (BUG: using original versions)
      // This simulates what happened before the fix:
      // - cascadeContext still has workflowVersion=1, agentVersion=1
      // - but the backend now expects version=2
      const staleContext: CascadeContext = {
        workflowId: WORKFLOW_ID,
        workflowVersion: 1, // ← STALE! should be 2
        agentId: AGENT_ID,
        agentVersion: 1,    // ← STALE! should be 2
      };

      await expect(
        cascadeSaveResource(llmType, RESOURCE_ID, 2, { tasks: [] }, staleContext)
      ).rejects.toMatchObject({
        status: 404,
      });
    });

    /**
     * REGRESSION TEST: Verifying the fix works — using updated versions
     * from the first save's result in the second save succeeds.
     */
    it("second cascade save succeeds with updated context versions (fix verification)", async () => {
      installCaptureHandlers({
        resourceStore: "llmstore",
        resourcePlural: "llms",
        resourceId: RESOURCE_ID,
        workflowId: WORKFLOW_ID,
        agentId: AGENT_ID,
        initialResourceVersion: 1,
        initialWorkflowVersion: 1,
        initialAgentVersion: 1,
      });

      // First save
      const context: CascadeContext = {
        workflowId: WORKFLOW_ID,
        workflowVersion: 1,
        agentId: AGENT_ID,
        agentVersion: 1,
      };

      const result1 = await cascadeSaveResource(
        llmType, RESOURCE_ID, 1, { tasks: [] }, context
      );

      // Second save with UPDATED context (the fix)
      const updatedContext: CascadeContext = {
        workflowId: WORKFLOW_ID,
        workflowVersion: result1.newWorkflowVersion!,
        agentId: AGENT_ID,
        agentVersion: result1.newAgentVersion!,
      };

      const result2 = await cascadeSaveResource(
        llmType, RESOURCE_ID, result1.newResourceVersion, { tasks: [] }, updatedContext
      );

      expect(result2.newResourceVersion).toBe(3);
      expect(result2.newWorkflowVersion).toBe(3);
      expect(result2.newAgentVersion).toBe(3);
    });

    it("updates the workflow extension URI to point to the new resource version", async () => {
      installCaptureHandlers({
        resourceStore: "rulestore",
        resourcePlural: "rulesets",
        resourceId: "rules1",
        workflowId: WORKFLOW_ID,
        agentId: AGENT_ID,
        initialResourceVersion: 1,
        initialWorkflowVersion: 1,
        initialAgentVersion: 1,
      });

      const context: CascadeContext = {
        workflowId: WORKFLOW_ID,
        workflowVersion: 1,
        agentId: AGENT_ID,
        agentVersion: 1,
      };

      await cascadeSaveResource(
        rulesType, "rules1", 1, { behaviorGroups: [] }, context
      );

      // The PUT-WF request should contain the updated extension URI
      const wfPut = capturedRequests.find((r) => r.method === "PUT-WF");
      expect(wfPut).toBeDefined();
      const wfBody = wfPut!.body as { workflowSteps: Array<{ config: { uri: string } }> };
      // The LLM step should now reference version=2
      const updatedStep = wfBody.workflowSteps.find(
        (s) => s.config?.uri?.includes("rulestore/rulesets/rules1")
      );
      expect(updatedStep?.config.uri).toContain("version=2");
    });

    it("updates the agent workflow URI to point to the new workflow version", async () => {
      installCaptureHandlers({
        resourceStore: "llmstore",
        resourcePlural: "llms",
        resourceId: RESOURCE_ID,
        workflowId: WORKFLOW_ID,
        agentId: AGENT_ID,
        initialResourceVersion: 1,
        initialWorkflowVersion: 1,
        initialAgentVersion: 1,
      });

      const context: CascadeContext = {
        workflowId: WORKFLOW_ID,
        workflowVersion: 1,
        agentId: AGENT_ID,
        agentVersion: 1,
      };

      await cascadeSaveResource(
        llmType, RESOURCE_ID, 1, { tasks: [] }, context
      );

      // The PUT-AGENT request should contain the updated workflow URI
      const agentPut = capturedRequests.find((r) => r.method === "PUT-AGENT");
      expect(agentPut).toBeDefined();
      const agentBody = agentPut!.body as { workflows: string[] };
      expect(agentBody.workflows[0]).toContain(`${WORKFLOW_ID}?version=2`);
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
});
