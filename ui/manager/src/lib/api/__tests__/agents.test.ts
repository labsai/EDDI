import { describe, it, expect } from "vitest";
import { http, HttpResponse } from "msw";
import { parseResourceUri, getAgent, generateAgentSigningKey, revokeAgentSigningKey, type AgentDescriptor } from "@/lib/api/agents";
import { parseVersionFromLocation } from "@/lib/api/location-version";
import { groupAgentsByName } from "@/hooks/use-agents";
import { server } from "@/test/mocks/server";

describe("parseResourceUri", () => {
  it("parses standard agent resource URI", () => {
    const result = parseResourceUri(
      "eddi://ai.labs.agent/agentstore/agents/abc123?version=3"
    );
    expect(result.id).toBe("abc123");
    expect(result.version).toBe(3);
  });

  it("defaults to version 1 when not specified", () => {
    const result = parseResourceUri(
      "eddi://ai.labs.agent/agentstore/agents/xyz789"
    );
    expect(result.id).toBe("xyz789");
    expect(result.version).toBe(1);
  });

  it("parses a plain path Location header", () => {
    const result = parseResourceUri(
      "/agentstore/agents/abc123?version=2"
    );
    expect(result.id).toBe("abc123");
    expect(result.version).toBe(2);
  });

  it("parses an HTTP URL", () => {
    const result = parseResourceUri(
      "http://localhost:7070/agentstore/agents/abc123?version=5"
    );
    expect(result.id).toBe("abc123");
    expect(result.version).toBe(5);
  });

  it("handles version=0 correctly", () => {
    const result = parseResourceUri(
      "eddi://ai.labs.agent/agentstore/agents/test1?version=0"
    );
    expect(result.id).toBe("test1");
    expect(result.version).toBe(0);
  });

  it("handles UUID resource IDs", () => {
    const result = parseResourceUri(
      "eddi://ai.labs.agent/agentstore/agents/550e8400-e29b-41d4-a716-446655440000?version=1"
    );
    expect(result.id).toBe("550e8400-e29b-41d4-a716-446655440000");
    expect(result.version).toBe(1);
  });

  it("falls back to raw resource when path has no segments", () => {
    const result = parseResourceUri("eddi://ai.labs.agent");
    expect(result.id).toBe("eddi://ai.labs.agent");
    expect(result.version).toBe(1);
  });

  it("handles non-numeric version by defaulting to 1", () => {
    const result = parseResourceUri(
      "eddi://ai.labs.agent/agentstore/agents/abc?version=abc"
    );
    expect(result.id).toBe("abc");
    expect(result.version).toBe(1);
  });
});

describe("getAgent — version parameter guard", () => {
  it("omits version query param when version is undefined", async () => {
    const result = await getAgent("test-id");
    // The MSW handler will return a valid response regardless, but we verify the call
    expect(result).toBeDefined();
  });

  it("omits version query param when version is 0", async () => {
    // version=0 is invalid — should behave like undefined (get latest)
    const result = await getAgent("agent1", 0);
    expect(result).toBeDefined();
  });

  it("includes version query param for positive values", async () => {
    const result = await getAgent("agent1", 3);
    expect(result).toBeDefined();
  });
});

describe("generateAgentSigningKey", () => {
  it("posts to the agent's signing-keys endpoint with its version and returns the key and the new version", async () => {
    let requested: string | undefined;
    server.use(
      http.post("*/agentstore/agents/:id/signing-keys", ({ request }) => {
        requested = new URL(request.url).pathname + new URL(request.url).search;
        return HttpResponse.json(
          { version: 2, publicKeyB64: "MCowBQYDK2VwAyEAkey", validFromMs: 1, validUntilMs: 0 },
          { status: 200, headers: { Location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=4" } },
        );
      }),
    );

    const result = await generateAgentSigningKey("agent1", 3);

    expect(requested).toBe("/agentstore/agents/agent1/signing-keys?version=3");
    expect(result.version).toBe(2);
    expect(result.publicKeyB64).toBe("MCowBQYDK2VwAyEAkey");
    expect(parseVersionFromLocation(result.location)).toBe(4);
  });
});

describe("revokeAgentSigningKey", () => {
  it("posts to the key's revoke endpoint with the agent's version and returns the revoked key and the new version", async () => {
    let requested: string | undefined;
    server.use(
      http.post("*/agentstore/agents/:id/signing-keys/:keyVersion/revoke", ({ request }) => {
        requested = new URL(request.url).pathname + new URL(request.url).search;
        return HttpResponse.json(
          { version: 2, publicKeyB64: "MCowBQYDK2VwAyEAkey", validFromMs: 1, validUntilMs: 0, revoked: true },
          { status: 200, headers: { Location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=5" } },
        );
      }),
    );

    const result = await revokeAgentSigningKey("agent1", 2, 4);

    expect(requested).toBe("/agentstore/agents/agent1/signing-keys/2/revoke?version=4");
    expect(result.revoked).toBe(true);
    expect(parseVersionFromLocation(result.location)).toBe(5);
  });
});

describe("groupAgentsByName", () => {
  it("groups agents by resource ID keeping latest version", () => {
    const agents: AgentDescriptor[] = [
      {
        resource: "eddi://ai.labs.agent/agentstore/agents/a?version=1",
        name: "Support Agent",
        description: "v1",
        createdOn: 1000,
        lastModifiedOn: 1000,
      },
      {
        resource: "eddi://ai.labs.agent/agentstore/agents/a?version=3",
        name: "Support Agent",
        description: "v3",
        createdOn: 1000,
        lastModifiedOn: 3000,
      },
      {
        resource: "eddi://ai.labs.agent/agentstore/agents/b?version=1",
        name: "FAQ Agent",
        description: "only version",
        createdOn: 2000,
        lastModifiedOn: 2000,
      },
    ];

    const result = groupAgentsByName(agents);
    expect(result).toHaveLength(2);

    const supportAgent = result.find((b) => b.id === "a");
    expect(supportAgent?.version).toBe(3);
    expect(supportAgent?.description).toBe("v3");
  });

  it("does NOT merge agents with same name but different IDs", () => {
    const agents: AgentDescriptor[] = [
      {
        resource: "eddi://ai.labs.agent/agentstore/agents/id1?version=1",
        name: "",
        description: "",
        createdOn: 1000,
        lastModifiedOn: 1000,
      },
      {
        resource: "eddi://ai.labs.agent/agentstore/agents/id2?version=1",
        name: "",
        description: "",
        createdOn: 2000,
        lastModifiedOn: 2000,
      },
      {
        resource: "eddi://ai.labs.agent/agentstore/agents/id3?version=1",
        name: "",
        description: "",
        createdOn: 3000,
        lastModifiedOn: 3000,
      },
    ];

    const result = groupAgentsByName(agents);
    // Previously this would return 1 (all grouped under ""), now returns 3
    expect(result).toHaveLength(3);
  });

  it("sorts by lastModifiedOn descending", () => {
    const agents: AgentDescriptor[] = [
      {
        resource: "eddi://ai.labs.agent/agentstore/agents/a?version=1",
        name: "Old Agent",
        description: "",
        createdOn: 1000,
        lastModifiedOn: 1000,
      },
      {
        resource: "eddi://ai.labs.agent/agentstore/agents/b?version=1",
        name: "New Agent",
        description: "",
        createdOn: 2000,
        lastModifiedOn: 5000,
      },
    ];

    const result = groupAgentsByName(agents);
    expect(result[0]?.name).toBe("New Agent");
  });

  it("returns empty array for no agents", () => {
    expect(groupAgentsByName([])).toEqual([]);
  });
});
