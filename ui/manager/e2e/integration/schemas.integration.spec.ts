import { test, expect } from "@playwright/test";
import { API_BASE, waitForBackend } from "./integration-helpers";

/**
 * Validate that all JSON Schema endpoints return valid schemas.
 * These schemas power Monaco editor autocomplete and validation in the Manager.
 */

const SCHEMA_ENDPOINTS = [
  { name: "Agents", path: "/agentstore/agents/jsonSchema" },
  { name: "Workflows", path: "/workflowstore/workflows/jsonSchema" },
  { name: "Rules", path: "/rulestore/rulesets/jsonSchema" },
  { name: "API Calls", path: "/apicallstore/apicalls/jsonSchema" },
  { name: "Output Sets", path: "/outputstore/outputsets/jsonSchema" },
  {
    name: "Dictionaries",
    path: "/dictionarystore/dictionaries/jsonSchema",
  },
  { name: "LLM", path: "/llmstore/llms/jsonSchema" },
  {
    name: "Property Setter",
    path: "/propertysetterstore/propertysetters/jsonSchema",
  },
];

test.describe("JSON Schema Endpoints — Real Backend", () => {
  test.describe.configure({ timeout: 120_000 });

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
