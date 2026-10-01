import { describe, it, expect } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { updateAgent, getDeploymentImpact } from "@/lib/api/agents";
import {
  extractAgentSwitch,
  extractAgentVersion,
  type SimpleConversationStep,
} from "@/lib/api/conversations";

/** Capture the URL of the next PUT to the agent store. */
function capturePut(): { url: () => URL } {
  let seen: URL | undefined;
  server.use(
    http.put("*/agentstore/agents/:id", ({ request, params }) => {
      seen = new URL(request.url);
      return new HttpResponse(null, {
        status: 200,
        headers: { Location: `eddi://ai.labs.agent/agentstore/agents/${params.id}?version=4` },
      });
    }),
  );
  return {
    url: () => {
      if (!seen) throw new Error("no PUT reached the agent store");
      return seen;
    },
  };
}

describe("updateAgent — compatible parameter", () => {
  it("appends compatible=true only when asked to", async () => {
    const put = capturePut();
    await updateAgent("agent1", 3, { workflows: [] }, { compatible: true });
    expect(put.url().searchParams.get("version")).toBe("3");
    expect(put.url().searchParams.get("compatible")).toBe("true");
  });

  it("sends no compatible parameter when false — the backend default is breaking", async () => {
    const put = capturePut();
    await updateAgent("agent1", 3, { workflows: [] }, { compatible: false });
    expect(put.url().searchParams.has("compatible")).toBe(false);
  });

  it("sends no compatible parameter when the option is absent", async () => {
    const put = capturePut();
    await updateAgent("agent1", 3, { workflows: [] });
    expect(put.url().searchParams.has("compatible")).toBe(false);
    expect(put.url().searchParams.get("version")).toBe("3");
  });
});

describe("getDeploymentImpact", () => {
  it("reads the preview for one environment and version", async () => {
    let seen: URL | undefined;
    server.use(
      http.get("*/administration/:env/deploymentimpact/:agentId", ({ request, params }) => {
        seen = new URL(request.url);
        return HttpResponse.json({
          agentId: params.agentId,
          version: 6,
          compatibilityGeneration: 3,
          deployedVersions: [
            { version: 5, compatibilityGeneration: 3, activeConversations: 12, outcome: "FOLLOW" },
          ],
        });
      }),
    );
    const impact = await getDeploymentImpact("test", "agent1", 6);
    expect(seen?.pathname).toMatch(/\/administration\/test\/deploymentimpact\/agent1$/);
    expect(seen?.searchParams.get("version")).toBe("6");
    expect(impact.deployedVersions[0]?.outcome).toBe("FOLLOW");
  });
});

describe("step version markers", () => {
  const step = (entries: { key: string; value: unknown }[]): SimpleConversationStep => ({
    conversationStep: entries,
  });

  it("reads agent:version and agent:switch", () => {
    const s = step([
      { key: "agent:version", value: 6 },
      { key: "agent:switch", value: { from: 5, to: 6 } },
    ]);
    expect(extractAgentVersion(s)).toBe(6);
    expect(extractAgentSwitch(s)).toEqual({ from: 5, to: 6 });
  });

  it("answers null for steps recorded before version following", () => {
    const s = step([{ key: "input:initial", value: "hi" }]);
    expect(extractAgentVersion(s)).toBeNull();
    expect(extractAgentSwitch(s)).toBeNull();
  });

  it("ignores a malformed switch marker", () => {
    expect(extractAgentSwitch(step([{ key: "agent:switch", value: { from: "5" } }]))).toBeNull();
    expect(extractAgentSwitch(step([{ key: "agent:switch", value: 5 }]))).toBeNull();
  });
});
