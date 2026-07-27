/**
 * Tool scopes for the Platform Operator.
 *
 * The operator is an EDDI API Agent whose tools are generated from EDDI's own
 * OpenAPI spec. `setup-api` accepts an `endpoints` filter — a comma-separated
 * list of `"METHOD /path"` entries matched against the spec's path templates
 * (verbatim, including `{param}` placeholders). Anything not listed produces no
 * tool at all, so the filter is the actual capability boundary, not a hint.
 *
 * This is an allow-list on purpose. A deny-list ("bind everything, subtract the
 * dangerous ones") fails open: any endpoint added to the backend later would be
 * silently granted. Substring matching on words like "delete" fails the same way.
 */

/** A capability scope the operator can be provisioned with. */
export type OperatorScope = "read_only" | "read_write";

/**
 * Read endpoints the operator is allowed to call.
 *
 * These are OpenAPI path templates copied verbatim from EDDI's spec. Every entry
 * is asserted to exist in the fetched spec by `tool-scopes.test.ts`, so an
 * invented or renamed path fails CI rather than silently producing zero tools.
 *
 * The set is chosen so the operator can actually answer the questions we suggest
 * to users: descriptors alone cannot diagnose a failing deployment, so by-id
 * reads and deployment status are included.
 */
export const READ_ENDPOINTS: readonly string[] = [
  // Agents
  "GET /agentstore/agents/descriptors",
  "GET /agentstore/agents/{id}",
  // Workflows and groups
  "GET /workflowstore/workflows/descriptors",
  "GET /groupstore/groups/descriptors",
  // Conversations
  "GET /conversationstore/conversations",
  "GET /conversationstore/conversations/{conversationId}",
  // Operations
  "GET /administration/{environment}/deploymentstatus/{agentId}",
  "GET /administration/coordinator/status",
  "GET /administration/logs",
  "GET /administration/quotas",
  // Audit
  "GET /auditstore/agent/{agentId}",
] as const;

/**
 * Write endpoints — deliberately empty until the human-in-the-loop approval gate
 * ships.
 *
 * Do not populate this by "just adding the safe ones". Creating or updating an
 * agent, editing an LLM config, and creating a schedule are all
 * approval-required: each can install attacker-controlled egress or persistence
 * in a platform the operator reads untrusted content from.
 */
export const WRITE_ENDPOINTS: readonly string[] = [] as const;

/**
 * Whether the `read_write` scope can be offered.
 *
 * This is the approval seam. It is a function, not a constant, so that the
 * invariant "no writes without an approval handler" is enforced at the one place
 * scope is chosen, rather than restated in the UI. It stays `false` until both a
 * handler is registered and write endpoints exist.
 */
export function isWriteScopeAvailable(
  hasApprovalHandler = false,
): boolean {
  return hasApprovalHandler && WRITE_ENDPOINTS.length > 0;
}

/** Resolve the endpoint list for a scope. */
export function endpointsForScope(scope: OperatorScope): readonly string[] {
  return scope === "read_write"
    ? [...READ_ENDPOINTS, ...WRITE_ENDPOINTS]
    : READ_ENDPOINTS;
}

/**
 * Build the `endpoints` filter string for `setup-api`.
 *
 * The backend splits on commas and trims, so a comma-joined list is the wire
 * format.
 */
export function buildEndpointFilter(scope: OperatorScope): string {
  return endpointsForScope(scope).join(", ");
}

/** Parse `"GET /a/{b}"` into its method and path template. */
export function parseEndpoint(
  entry: string,
): { method: string; path: string } | null {
  const match = /^([A-Z]+)\s+(\/\S*)$/.exec(entry.trim());
  if (!match) return null;
  return { method: match[1]!, path: match[2]! };
}
