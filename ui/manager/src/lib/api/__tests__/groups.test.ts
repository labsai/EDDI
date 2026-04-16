import { describe, it, expect, vi } from "vitest";
import {
  groupGroupsByName,
  parseGroupResourceUri,
  type GroupDescriptor,
} from "../groups";

// We need to mock the api module
vi.mock("../api-client", () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
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
