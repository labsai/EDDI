import { describe, it, expect } from "vitest";
import { parseResourceUri, type AgentDescriptor } from "@/lib/api/agents";
import { groupAgentsByName } from "@/hooks/use-agents";

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
