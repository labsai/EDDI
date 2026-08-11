import type { PendingToolCallView } from "@/lib/api/hitl";
import { findSelfTargetedCalls } from "./self-guard";
import { findGateCarryingCalls, type GateCarryingReason } from "./gate-guard";

/**
 * Every hard refusal the Manager applies to a pending tool-call batch, in one
 * place.
 *
 * The three approval surfaces (operator chat, approvals inbox, conversation
 * detail) must agree exactly on what is refusable — a control that fires on one
 * of them and not the others is a control with a documented bypass. They
 * previously each inlined `findSelfTargetedCalls` plus a copy of its i18n
 * string; adding a second guard would have triplicated that again, so both now
 * resolve here and each surface calls this once.
 *
 * `ApprovalBanner` disables Approve for the WHOLE batch while any entry is
 * present — a batch is approved or rejected together, so one refused call in it
 * refuses all of them. The reason strings therefore say so.
 *
 * See `self-guard.ts` and `gate-guard.ts` for why each is a control rather than
 * a warning, and for the honest scope limits both share (Manager-side only —
 * Slack buttons and the MCP `resume_conversation` tool decide the same pause
 * through different code).
 */

/** A call the approval surfaces must refuse, with the reason to show. */
export interface BlockedCall {
  callId: string;
  reason: string;
}

/**
 * Minimal shape of `react-i18next`'s `t`, so this module stays a plain function
 * rather than a hook and can be unit-tested without an i18n provider.
 */
type Translate = (key: string, defaultValue: string, options?: Record<string, unknown>) => string;

/**
 * @param calls
 *          the pending calls of a TOOL_CALL pause; anything else contributes
 *          nothing (a RULE pause has no per-call requests to target).
 * @param actingAgentId
 *          the agent whose conversation raised the pause — NOT a separately
 *          fetched "the operator" id. See `self-guard.ts` for why that
 *          distinction is load-bearing for the `eddi-approver` role.
 */
export function findBlockedCalls(
  calls: readonly PendingToolCallView[] | null | undefined,
  actingAgentId: string | null | undefined,
  t: Translate,
): BlockedCall[] {
  const selfTargeted = findSelfTargetedCalls(calls, actingAgentId).map((hit) => ({
    callId: hit.callId,
    reason: t(
      "operator.approval.blockedSelfTarget",
      "An agent may not modify its own definition, and this request targets the operator's own agent ({{agentId}}). Approving is unavailable for the whole batch while it is present — reject, and make this change from that agent's own page.",
      { agentId: hit.agentId },
    ),
  }));

  // One reason string per failure mode, not one for "everything that is not a
  // confirmed gate". `unverifiable-body` covers a truncated body AND an
  // unparseable one, so a message naming only size would misdiagnose a small
  // malformed payload; `unpinned-request` is a different cause with a different
  // remedy again. An approver acting on a wrong explanation is worse served than
  // one given a vaguer but true one.
  const gateReason: Record<GateCarryingReason, string> = {
    "carries-gate": t(
      "operator.approval.blockedGateCarrying",
      "This request writes an LLM configuration that sets its own approval gate (toolApprovals), which would replace the gate reviewing it. Approving is unavailable for the whole batch while it is present — reject, and change the gate from the agent's own page.",
    ),
    "unverifiable-body": t(
      "operator.approval.blockedGateUnverifiable",
      "This request writes an LLM configuration whose body cannot be read in full — it is either too large to display or not valid JSON — so it cannot be confirmed free of an approval-gate override. Approving is unavailable for the whole batch while it is present — reject, and make this change from the agent's own page.",
    ),
    "unpinned-request": t(
      "operator.approval.blockedGateUnpinned",
      "This request writes an LLM configuration but is not pinned, so what actually runs can differ from what is shown here and cannot be confirmed free of an approval-gate override. Approving is unavailable for the whole batch while it is present — reject, and make this change from the agent's own page.",
    ),
  };

  const gateCarrying = findGateCarryingCalls(calls).map((hit) => ({
    callId: hit.callId,
    reason: gateReason[hit.reason],
  }));

  return [...selfTargeted, ...gateCarrying];
}
