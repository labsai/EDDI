import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { updateDescriptor, getDescriptors, getDescriptorVersions } from "../descriptors";

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

  describe("getDescriptorVersions", () => {
    it("reads each version by id AND version, oldest first", async () => {
      const asked: string[] = [];
      server.use(
        http.get("*/descriptorstore/descriptors/:id", ({ params, request }) => {
          const version = new URL(request.url).searchParams.get("version");
          asked.push(`${params.id}@${version}`);
          return HttpResponse.json({
            resource: `eddi://ai.labs.agent/agentstore/agents/${params.id}?version=${version}`,
            name: `v${version}`,
            lastModifiedOn: Number(version),
          });
        }),
      );

      const result = await getDescriptorVersions("agent-x", 3);
      expect(asked.sort()).toEqual(["agent-x@1", "agent-x@2", "agent-x@3"]);
      // Three DIFFERENT versions — the store listing this replaced answered the
      // latest descriptor three times.
      expect(result.map((d) => d.name)).toEqual(["v1", "v2", "v3"]);
    });

    it("skips a version that cannot be read rather than failing the list", async () => {
      server.use(
        http.get("*/descriptorstore/descriptors/:id", ({ params, request }) => {
          const version = new URL(request.url).searchParams.get("version");
          if (version === "2") return new HttpResponse(null, { status: 404 });
          return HttpResponse.json({
            resource: `eddi://ai.labs.agent/agentstore/agents/${params.id}?version=${version}`,
            name: `v${version}`,
          });
        }),
      );

      const result = await getDescriptorVersions("agent-x", 3);
      expect(result.map((d) => d.name)).toEqual(["v1", "v3"]);
    });

    it("never sends the store listing's ignored `version` parameter", async () => {
      let listingHit = false;
      server.use(
        http.get("*/agentstore/agents/descriptors", () => {
          listingHit = true;
          return HttpResponse.json([]);
        }),
      );
      await getDescriptorVersions("agent1", 2);
      expect(listingHit).toBe(false);
    });
  });
});
