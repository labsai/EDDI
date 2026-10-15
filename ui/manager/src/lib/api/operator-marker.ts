import { updateDescriptor } from "./descriptors";

/**
 * The token `provisionOperator` writes into the operator agent's descriptor
 * description, and the first thing discovery looks for.
 *
 * A descriptor field because an `AgentConfiguration` has nowhere free-form to
 * put one, and the descriptor is what the agent LISTING already returns — so
 * recognising an operator costs no read beyond the listing itself. Bracketed
 * and hyphenated so it cannot occur in prose by accident, and spelled so that
 * the listing's `filter=perator` (which matches name and description, case-
 * sensitively) finds both it and a default "EDDI Platform Operator" name.
 */
export const OPERATOR_DESCRIPTOR_MARKER = "[eddi-platform-operator]";

/** What the operator agent's descriptor description is set to. Stored data, not UI text. */
export const OPERATOR_DESCRIPTOR_DESCRIPTION =
  `Platform Operator — managed from the Manager's Operator screen. ${OPERATOR_DESCRIPTOR_MARKER}`;

/** Whether a descriptor description carries the operator marker. */
export function hasOperatorMarker(description: string | null | undefined): boolean {
  return typeof description === "string" && description.includes(OPERATOR_DESCRIPTOR_MARKER);
}

/**
 * Stamp the marker on a freshly provisioned operator. Best-effort: an operator
 * without it is still found by the heuristic, so a failed PATCH must not fail
 * an activation.
 */
export async function markOperatorDescriptor(agentId: string, version: number): Promise<void> {
  try {
    await updateDescriptor(agentId, version, { description: OPERATOR_DESCRIPTOR_DESCRIPTION });
  } catch (error) {
    console.warn("[operator] Could not mark the operator agent's descriptor:", error);
  }
}
