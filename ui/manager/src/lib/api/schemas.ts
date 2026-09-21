import { api } from "../api-client";
import type { ResourceTypeConfig } from "./resources";

/** In-memory cache for JSON schemas (they don't change at runtime) */
const schemaCache = new Map<string, object>();

/**
 * Fetch the JSON Schema for a given resource type.
 * GET /{store}/{plural}/jsonSchema
 * Returns the raw JSON Schema object (Draft-04).
 */
export async function getJsonSchema(
  rt: ResourceTypeConfig
): Promise<object> {
  const cacheKey = rt.slug;
  const cached = schemaCache.get(cacheKey);
  if (cached) return cached;

  const schema = await api.get<object>(
    `/${rt.store}/${rt.plural}/jsonSchema`
  );
  schemaCache.set(cacheKey, schema);
  return schema;
}

/**
 * Fetch JSON Schema for agents.
 * GET /agentstore/agents/jsonSchema
 */
export async function getAgentJsonSchema(): Promise<object> {
  const cached = schemaCache.get("agent");
  if (cached) return cached;

  const schema = await api.get<object>("/agentstore/agents/jsonSchema");
  schemaCache.set("agent", schema);
  return schema;
}

/**
 * Fetch JSON Schema for packages.
 * GET /workflowstore/workflows/jsonSchema
 */
export async function getWorkflowJsonSchema(): Promise<object> {
  const cached = schemaCache.get("package");
  if (cached) return cached;

  const schema = await api.get<object>("/workflowstore/workflows/jsonSchema");
  schemaCache.set("package", schema);
  return schema;
}
