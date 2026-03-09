import { test, expect } from "@playwright/test";
import { API_BASE, waitForBackend } from "./integration-helpers";

/**
 * Validate that all JSON Schema endpoints return valid schemas.
 * These schemas power Monaco editor autocomplete and validation in the Manager.
 */

const SCHEMA_ENDPOINTS = [
  { name: "Bots", path: "/botstore/bots/jsonSchema" },
  { name: "Packages", path: "/packagestore/packages/jsonSchema" },
  { name: "Behavior Rules", path: "/behaviorstore/behaviorsets/jsonSchema" },
  { name: "HTTP Calls", path: "/httpcallsstore/httpcalls/jsonSchema" },
  { name: "Output Sets", path: "/outputstore/outputsets/jsonSchema" },
  {
    name: "Regular Dictionaries",
    path: "/regulardictionarystore/regulardictionaries/jsonSchema",
  },
  { name: "LangChain", path: "/langchainstore/langchains/jsonSchema" },
  {
    name: "Property Setter",
    path: "/propertysetterstore/propertysetters/jsonSchema",
  },
];

test.describe("JSON Schema Endpoints — Real Backend", () => {
  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);
  });

  for (const endpoint of SCHEMA_ENDPOINTS) {
    test(`${endpoint.name}: GET ${endpoint.path} returns valid schema`, async ({
      request,
    }) => {
      const res = await request.get(`${API_BASE}${endpoint.path}`);
      expect(res.ok()).toBeTruthy();

      const schema = await res.json();

      // Every JSON Schema should have at least a "type" field
      expect(schema).toHaveProperty("type");

      // Most EDDI schemas are object type
      expect(schema.type).toBe("object");

      // Should have a $schema or properties field (Draft-04)
      const hasSchema = "$schema" in schema;
      const hasProperties = "properties" in schema;
      expect(hasSchema || hasProperties).toBeTruthy();

      // Log useful info about schema completeness
      if (hasProperties) {
        const propCount = Object.keys(schema.properties).length;
        console.log(
          `[SCHEMA] ${endpoint.name}: ${propCount} properties defined`
        );
      }
    });
  }
});
