import { api } from "../api-client";

// ─── Types ───

/**
 * A user's global properties: key → RAW value.
 *
 * `GET /propertiesstore/properties/{userId}` returns the `Properties` map the
 * backend reads straight off the user's `global` user-memory entries
 * (`MongoUserMemoryStore.readProperties`) — plain strings, numbers, booleans,
 * lists and objects, not the `Property` wrapper (`valueString`, `valueInt`, …)
 * used inside conversation memory. Typing this as `Property` objects made every
 * row render as type "null" with value "—".
 */
export type Properties = Record<string, unknown>;

// ─── API Functions ───

const BASE = "/propertiesstore/properties";

/**
 * Read a user's global properties. A user with none gets `{}`: the backend
 * answers 204 No Content there, and TanStack Query v5 fails a query whose
 * function resolves `undefined`, so an empty user rendered as an error.
 */
export async function readProperties(
  userId: string,
): Promise<Properties> {
  const properties = await api.get<Properties | undefined>(`${BASE}/${encodeURIComponent(userId)}`);
  return properties ?? {};
}

export async function mergeProperties(
  userId: string,
  properties: Properties,
): Promise<void> {
  return api.post(`${BASE}/${encodeURIComponent(userId)}`, properties);
}

/**
 * Delete ALL of a user's global properties — which are every `global`-visibility
 * user-memory entry the user has, including ones agents wrote, not just the
 * rows this page happens to show.
 */
export async function deleteProperties(userId: string): Promise<void> {
  return api.delete(`${BASE}/${encodeURIComponent(userId)}`);
}
