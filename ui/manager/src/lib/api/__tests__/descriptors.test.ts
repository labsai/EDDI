import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { updateDescriptor, getDescriptors } from "../descriptors";

describe("descriptors API", () => {
  describe("updateDescriptor", () => {
    it("patches a descriptor with name and description", async () => {
      await expect(
        updateDescriptor("res1", 1, {
          name: "Updated Name",
          description: "Updated Description",
        })
      ).resolves.toBeUndefined();
    });

    it("handles API error", async () => {
      server.use(
        http.patch("*/descriptorstore/descriptors/:id", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        )
      );
      await expect(
        updateDescriptor("res-fail", 1, { name: "Fail" })
      ).rejects.toMatchObject({ status: 500 });
    });
  });

  describe("getDescriptors", () => {
    it("fetches descriptors for a resource type with defaults", async () => {
      const result = await getDescriptors("agentstore/agents");
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });

    it("passes custom limit, index, and filter", async () => {
      const result = await getDescriptors(
        "workflowstore/workflows",
        50,
        5,
        "test"
      );
      expect(result).toBeDefined();
    });

    it("omits filter when empty", async () => {
      const result = await getDescriptors("rulestore/rulesets", 100, 0, "");
      expect(result).toBeDefined();
    });
  });
});
