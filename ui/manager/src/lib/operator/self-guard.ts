import type { PendingToolCallView } from "@/lib/api/hitl";

/**
 * Refuses a gated write that the operator aimed at **itself**.
 *
 * Unlike `escalation-flags.ts`, which is explicitly an attention aid, this is a
 * control: the approval surfaces disable Approve outright when it fires. That
 * difference is deliberate — what it blocks is not "a setting worth reading
 * twice" but the one write that would remove the operator's own oversight, and
 * a warning label an approver can skim past is not a defence against that.
 *
 * ## The chain it breaks
 *
 * `PUT /agentstore/agents/{id}/updateResourceUri` is what makes an authoring
 * edit take effect (see `tool-scopes.ts`), and it cannot carry a `hitlConfig` —
 * its body is a bare URI. But aimed at the operator's OWN agent it completes a
 * loop that ends with an ungated operator:
 *
 * 1. `PUT` its own workflow, repointing the LLM step at some existing
 *    `llmstore` document that carries a permissive `Task.toolApprovals`
 *    (by-id reads are granted, so it can find one) → workflow M+1.
 * 2. `PUT /agentstore/agents/{ownId}/updateResourceUri` → agent N+1, now
 *    running an LLM task whose `toolApprovals` FULLY REPLACES the agent-level
 *    gate.
 * 3. `POST .../deploy/{ownId}?version=N+1` — already granted, and legitimately
 *    so — and every later write executes unapproved.
 *
 * Step 1 alone is inert: the deployed agent still references the old workflow
 * version. Step 2 is the hinge, and step 2 is what this refuses.
 *
 * ## Scope, stated honestly
 *
 * This runs where a human authorises the write, which is the right seam — but
 * it is a Manager-side control, so it governs the Manager's approval surfaces
 * (operator chat, approvals inbox) and not the Slack buttons or the MCP
 * `resume_conversation` tool. Those decide the same pause through different
 * code. Closing that properly needs the backend to refuse the write itself,
 * which needs a server-side notion of "the operator" that does not exist today.
 * Treat this as removing the easy path, not as a boundary.
 *
 * Matching is on the operator's agent id appearing in the resolved request URI.
 * The id is a path segment, not a credential, so it survives
 * `RequestRedactor.redactUri` intact and the redacted preview the UI holds is
 * enough to decide.
 */

/** A call refused because it targets the operator's own agent. */
export interface SelfTargetedCall {
  callId: string;
  /** The operator's own agent id, as found in the request URI. */
  agentId: string;
}

/**
 * Whether a resolved request URI writes to the given agent's own document.
 *
 * Deliberately substring-matching the id rather than parsing the path: the id
 * is a long opaque identifier, a false positive costs one needlessly refused
 * approval, and a false negative costs the gate. When the two error directions
 * are that asymmetric, the loose test is the correct one.
 *
 * Returns false for a blank id — an operator with no agent id provisioned yet
 * has nothing to protect, and matching "" against every URI would refuse every
 * write on the platform.
 */
export function uriTargetsAgent(uri: string | null | undefined, agentId: string | null | undefined): boolean {
  if (!uri || !agentId || agentId.trim() === "") return false;
  return uri.includes(agentId);
}

/**
 * The subset of pending calls that write to the operator's own agent document.
 *
 * Only WRITE methods count. A `GET` of its own configuration is how the
 * operator answers "what am I running?", and refusing that would break
 * introspection to prevent nothing — a read cannot repoint anything.
 *
 * A call with no resolved preview is NOT refused: it is also not pinned, so it
 * carries its own separate warning, and refusing every unpreviewable call here
 * would block legitimate non-http tools entirely.
 */
export function findSelfTargetedCalls(
  calls: readonly PendingToolCallView[] | null | undefined,
  operatorAgentId: string | null | undefined,
): SelfTargetedCall[] {
  if (!calls || !operatorAgentId) return [];
  const found: SelfTargetedCall[] = [];
  for (const call of calls) {
    const preview = call.requestPreview;
    if (!preview) continue;
    const method = (preview.method ?? "").toUpperCase();
    if (method === "GET" || method === "HEAD" || method === "") continue;
    if (uriTargetsAgent(preview.uri, operatorAgentId)) {
      found.push({ callId: call.callId, agentId: operatorAgentId });
    }
  }
  return found;
}
