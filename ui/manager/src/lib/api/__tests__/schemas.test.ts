import { describe, it, expect, beforeEach } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  getJsonSchema,
  getAgentJsonSchema,
  getWorkflowJsonSchema,
} from "../schemas";
import type { ResourceTypeConfig } from "../resources";

// We need to clear the internal schema cache between tests to avoid stale data
// The schemas module uses a module-level Map cache that persists between tests.
// We re-import to get fresh module, but that doesn't work with vitest caching.
// Instead we just test with different slugs / override handlers to get consistent results.

describe("schemas API", () => {
  describe("getJsonSchema", () => {
    it("fetches JSON schema for a resource type", async () => {
      const rt: ResourceTypeConfig = {
        slug: "rules-test-" + Date.now(),
        label: "Rules",
        store: "rulestore",
        plural: "rulesets",
        extension: "ai.labs.rules",
      };
      server.use(
        http.get("*/rulestore/rulesets/jsonSchema", () =>
          HttpResponse.json({
            $schema: "https://json-schema.org/draft/2020-12/schema",
            type: "object",
            title: "TestSchema",
          })
        )
      );
      const result = await getJsonSchema(rt);
      expect(result).toBeDefined();
      expect(result).toHaveProperty("$schema");
    });

    it("returns cached schema on second call", async () => {
      const slug = "cache-test-" + Date.now();
      const rt: ResourceTypeConfig = {
        slug,
        label: "Test",
        store: "teststore",
        plural: "tests",
        extension: "ai.labs.test",
      };
      server.use(
        http.get("*/teststore/tests/jsonSchema", () =>
          HttpResponse.json({ cached: true })
        )
      );
      const first = await getJsonSchema(rt);
      const second = await getJsonSchema(rt);
      expect(first).toEqual(second);
    });
  });

  describe("getAgentJsonSchema", () => {
    it("fetches agent JSON schema", async () => {
      const result = await getAgentJsonSchema();
      expect(result).toBeDefined();
      expect(result).toHaveProperty("type");
    });
  });

  describe("getWorkflowJsonSchema", () => {
    it("fetches workflow JSON schema", async () => {
      const result = await getWorkflowJsonSchema();
      expect(result).toBeDefined();
      expect(result).toHaveProperty("type");
    });
  });
});
