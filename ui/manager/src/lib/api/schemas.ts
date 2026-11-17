import { api } from "@eddi/app-kit/lib/api-client";
import type { ResourceTypeConfig } from "@eddi/app-kit/lib/api/resources";

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
