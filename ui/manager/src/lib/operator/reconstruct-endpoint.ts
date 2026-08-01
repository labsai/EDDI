import type { FetchedSpec } from "@/lib/api/operator";

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
  const index: Record<string, ReconstructedEndpoint> = {};
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
  return index[toolName] ?? null;
}
