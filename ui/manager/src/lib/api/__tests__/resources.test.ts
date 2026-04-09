import { describe, it, expect } from "vitest";
import {
  RESOURCE_TYPES,
  getResourceType,
  getResourceDescriptors,
  getResource,
  createResource,
  updateResource,
  deleteResource,
  duplicateResource,
  getResourceVersions,
} from "@/lib/api/resources";

const behavior = RESOURCE_TYPES.find((rt) => rt.slug === "rules")!;

describe("getResourceType", () => {
  it("returns config for known slug", () => {
    const rt = getResourceType("rules");
    expect(rt).toBeDefined();
    expect(rt!.store).toBe("rulestore");
    expect(rt!.plural).toBe("rulesets");
  });

  it("returns undefined for unknown slug", () => {
    expect(getResourceType("nonexistent")).toBeUndefined();
  });

  it("returns all 9 resource types", () => {
    expect(RESOURCE_TYPES).toHaveLength(9);
    const slugs = RESOURCE_TYPES.map((rt) => rt.slug);
    expect(slugs).toContain("rules");
    expect(slugs).toContain("apicalls");
    expect(slugs).toContain("output");
    expect(slugs).toContain("dictionary");
    expect(slugs).toContain("llm");
    expect(slugs).toContain("propertysetter");
    expect(slugs).toContain("mcpcalls");
    expect(slugs).toContain("rag");
    expect(slugs).toContain("snippets");
  });
});

describe("getResourceDescriptors", () => {
  it("returns descriptor array from MSW", async () => {
    const result = await getResourceDescriptors(behavior);
    expect(Array.isArray(result)).toBe(true);
  });
});

describe("getResource", () => {
  it("returns config object by id and version", async () => {
    const result = await getResource(behavior, "res1", 1);
    expect(result).toBeDefined();
    expect(typeof result).toBe("object");
  });
});

describe("createResource", () => {
  it("returns location header on create", async () => {
    const result = await createResource(behavior, { behaviorGroups: [] });
    expect(result).toBeDefined();
    expect(result.location).toBeDefined();
  });
});

describe("updateResource", () => {
  it("returns location header on update", async () => {
    const result = await updateResource(behavior, "res1", 1, {
      behaviorGroups: [],
    });
    expect(result).toBeDefined();
    expect(result.location).toBeDefined();
  });
});

describe("deleteResource", () => {
  it("resolves without error on delete", async () => {
    await expect(deleteResource(behavior, "res1", 1)).resolves.toBeUndefined();
  });
});

describe("duplicateResource", () => {
  it("returns location header on duplicate", async () => {
    const result = await duplicateResource(behavior, "res1", 1);
    expect(result).toBeDefined();
    expect(result.location).toBeDefined();
  });
});

describe("getResourceVersions", () => {
  it("returns array of version descriptors", async () => {
    const result = await getResourceVersions(behavior, "res1");
    expect(Array.isArray(result)).toBe(true);
  });
});
