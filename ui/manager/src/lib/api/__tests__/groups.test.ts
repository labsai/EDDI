import { describe, it, expect, vi } from "vitest";
import {
  groupGroupsByName,
  parseGroupResourceUri,
  type GroupDescriptor,
} from "../groups";

// We need to mock the api module
vi.mock("../../api-client", () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    getAuthHeader: vi.fn().mockReturnValue({}),
    getBaseUrl: vi.fn().mockReturnValue("http://localhost:7070"),
  },
}));

describe("parseGroupResourceUri", () => {
  it("parses standard group URI", () => {
    const result = parseGroupResourceUri(
      "eddi://ai.labs.group/groupstore/groups/abc123?version=3"
    );
    expect(result.id).toBe("abc123");
    expect(result.version).toBe(3);
  });

  it("defaults version to 1 when missing", () => {
    const result = parseGroupResourceUri(
      "eddi://ai.labs.group/groupstore/groups/xyz"
    );
    expect(result.id).toBe("xyz");
    expect(result.version).toBe(1);
  });

  it("parses a plain path Location header", () => {
    const result = parseGroupResourceUri(
      "/groupstore/groups/grp42?version=2"
    );
    expect(result.id).toBe("grp42");
    expect(result.version).toBe(2);
  });

  it("parses an HTTP URL", () => {
    const result = parseGroupResourceUri(
      "http://localhost:7070/groupstore/groups/grp42?version=5"
    );
    expect(result.id).toBe("grp42");
    expect(result.version).toBe(5);
  });

  it("handles version=0 correctly", () => {
    const result = parseGroupResourceUri(
      "eddi://ai.labs.group/groupstore/groups/testgrp?version=0"
    );
    expect(result.id).toBe("testgrp");
    expect(result.version).toBe(0);
  });

  it("handles UUID resource IDs", () => {
    const result = parseGroupResourceUri(
      "eddi://ai.labs.group/groupstore/groups/550e8400-e29b-41d4-a716-446655440000?version=1"
    );
    expect(result.id).toBe("550e8400-e29b-41d4-a716-446655440000");
    expect(result.version).toBe(1);
  });

  it("handles corrupted backend data with version concatenated into path", () => {
    // Backend bug: syncDescriptor wrote "version" instead of "?version="
    const result = parseGroupResourceUri(
      "eddi://ai.labs.group/groupstore/groups/6a1f2a825e0172b6b7d9219fversion1"
    );
    expect(result.id).toBe("6a1f2a825e0172b6b7d9219f");
    expect(result.version).toBe(1);
  });

  it("handles corrupted backend data with higher version numbers", () => {
    const result = parseGroupResourceUri(
      "eddi://ai.labs.group/groupstore/groups/abc123version42"
    );
    expect(result.id).toBe("abc123");
    expect(result.version).toBe(42);
  });
});

describe("groupGroupsByName", () => {
  it("deduplicates by ID keeping highest version", () => {
    const descriptors: GroupDescriptor[] = [
      {
        resource: "eddi://ai.labs.group/groupstore/groups/g1?version=1",
        name: "Group A",
        description: "v1",
        createdOn: 1000,
        lastModifiedOn: 2000,
      },
      {
        resource: "eddi://ai.labs.group/groupstore/groups/g1?version=3",
        name: "Group A v3",
        description: "v3",
        createdOn: 1000,
        lastModifiedOn: 4000,
      },
      {
        resource: "eddi://ai.labs.group/groupstore/groups/g2?version=1",
        name: "Group B",
        description: "d B",
        createdOn: 500,
        lastModifiedOn: 3000,
      },
    ];

    const result = groupGroupsByName(descriptors);
    expect(result).toHaveLength(2);
    // g1 should have version 3
    const g1 = result.find((g) => g.id === "g1");
    expect(g1?.version).toBe(3);
    expect(g1?.name).toBe("Group A v3");
  });

  it("sorts by lastModifiedOn descending", () => {
    const descriptors: GroupDescriptor[] = [
      {
        resource: "eddi://ai.labs.group/groupstore/groups/old?version=1",
        name: "Old",
        description: "",
        createdOn: 100,
        lastModifiedOn: 100,
      },
      {
        resource: "eddi://ai.labs.group/groupstore/groups/new?version=1",
        name: "New",
        description: "",
        createdOn: 200,
        lastModifiedOn: 200,
      },
    ];

    const result = groupGroupsByName(descriptors);
    expect(result[0]!.id).toBe("new");
    expect(result[1]!.id).toBe("old");
  });

  it("returns empty array for empty input", () => {
    expect(groupGroupsByName([])).toEqual([]);
  });
});

describe("deleteGroupWithMembers", () => {
  it("resolves current version before deleting each member", async () => {
    const { api } = await import("../../api-client");
    const { deleteGroupWithMembers } = await import("../groups");

    // Mock currentversion endpoint → returns 5 for all agents
    vi.mocked(api.get).mockResolvedValue(5);
    // Mock deleteAgent → succeeds
    vi.mocked(api.delete).mockResolvedValue(undefined);

    await deleteGroupWithMembers("grp1", 1, {
      name: "Test Group",
      description: "Test",
      members: [
        { agentId: "agent-a", memberType: "AGENT", displayName: "A", speakingOrder: null, role: null },
        { agentId: "agent-b", memberType: "AGENT", displayName: "B", speakingOrder: null, role: null },
      ],
      moderatorAgentId: "agent-mod",
      style: "ROUND_TABLE",
      maxRounds: 3,
      phases: null,
      protocol: null,
    });

    // Should have called currentversion for each unique agent (agent-a, agent-b, agent-mod)
    const getCalls = vi.mocked(api.get).mock.calls;
    const currentVersionCalls = getCalls.filter(([path]) =>
      (path as string).includes("/currentversion")
    );
    expect(currentVersionCalls.length).toBe(3);

    // Delete calls should use version 5, not version 1
    const deleteCalls = vi.mocked(api.delete).mock.calls;
    // Group agents + the group itself
    expect(deleteCalls.length).toBeGreaterThanOrEqual(3);
    // Agent delete calls should include version=5
    const agentDeleteCalls = deleteCalls.filter(([path]) =>
      (path as string).includes("agentstore/agents/")
    );
    for (const [path] of agentDeleteCalls) {
      expect(path).toContain("version=5");
    }
  });
});
