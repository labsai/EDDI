import type { FetchedSpec } from "@/lib/api/operator";
import { parseEndpoint } from "./tool-scopes";

/** A method + path reconstructed for display, never sent anywhere. */
export interface ReconstructedEndpoint {
  method: string;
  path: string;
}

/**
 * Maps every operation's `operationId` to its method + path.
 *
 * Mirrors the backend's own naming exactly: `McpApiToolBuilder.buildApiCall`
 * names a generated tool `operation.getOperationId()` (falling back to a slug
 * only when the spec omits one). Reading `operationId` back out of the same
 * spec is therefore not a guess — it is the identical lookup the backend
 * performed when it built the tool, run again on the client.
 */
export function buildOperationIdIndex(spec: FetchedSpec): Record<string, ReconstructedEndpoint> {
  // Null-prototype: the lookup key is a tool name from the pause payload, not a
  // value this module controls. A plain object literal would resolve
  // `index["toString"]` to the inherited function — truthy, so the caller would
  // render `undefined undefined (reconstructed)` instead of showing nothing —
  // and would silently fail to store an operationId of `"__proto__"`.
  const index: Record<string, ReconstructedEndpoint> = Object.create(null);
  for (const [path, methods] of Object.entries(spec.paths ?? {})) {
    if (!methods || typeof methods !== "object") continue;
    for (const [method, operation] of Object.entries(methods)) {
      if (!operation || typeof operation !== "object") continue;
      const operationId = (operation as { operationId?: unknown }).operationId;
      if (typeof operationId === "string" && operationId.length > 0) {
        index[operationId] = { method: method.toUpperCase(), path };
      }
    }
  }
  return index;
}

/**
 * Reconstructs the endpoint a gated tool call actually targets, or `null` when
 * it cannot be determined.
 *
 * Deliberately returns `null` rather than a best-effort guess when the tool
 * name has no `operationId` match (the backend's slug fallback, or a non-HTTP
 * tool source such as `mcp`) — an approver must never be shown a fabricated
 * "this is what it calls" for a payload they are about to approve.
 */
export function reconstructEndpoint(
  toolName: string,
  index: Record<string, ReconstructedEndpoint>,
): ReconstructedEndpoint | null {
  // Own-property check rather than a bare lookup: `buildOperationIdIndex`
  // returns a null-prototype object, but this function does not get to assume
  // its caller used it — and against a plain object `index["toString"]` would
  // return the inherited function, which is truthy.
  if (!Object.prototype.hasOwnProperty.call(index, toolName)) return null;
  return index[toolName] ?? null;
}

/**
 * The inverse of {@link reconstructEndpoint}: given an allow-list entry (the
 * `"METHOD /path"` format `WRITE_ENDPOINTS` and `READ_ENDPOINTS` both use),
 * finds the generated tool name that calls it.
 *
 * Used by the write canary, which needs to recognize — among an arbitrary
 * toolTrace — specifically the ONE tool it deliberately provoked, to tell
 * "this call executed without pausing" (the gate is broken) apart from "the
 * model never attempted a write at all" (inconclusive, not a failure).
 *
 * Returns `null` on no match, same as `reconstructEndpoint` and for the same
 * reason: a caller acting on an endpoint it could not actually confirm the
 * name of would be acting on a guess.
 */
export function resolveToolNameForEndpoint(
  endpoint: string,
  index: Record<string, ReconstructedEndpoint>,
): string | null {
  const parsed = parseEndpoint(endpoint);
  if (!parsed) return null;
  for (const [operationId, entry] of Object.entries(index)) {
    if (entry.method === parsed.method && entry.path === parsed.path) return operationId;
  }
  return null;
}
