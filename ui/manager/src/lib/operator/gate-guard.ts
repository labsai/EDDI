import type { PendingToolCallView } from "@/lib/api/hitl";

/**
 * Refuses a gated write that would set an approval gate from inside an LLM
 * configuration document.
 *
 * Like `self-guard.ts` and unlike `escalation-flags.ts`, this is a **control**:
 * the approval surfaces disable Approve outright when it fires. The distinction
 * is the same one `self-guard.ts` draws — what this blocks is not "a setting
 * worth reading twice" but the one field that can silently remove the operator's
 * oversight, and a warning label an approver can skim past is not a defence
 * against that.
 *
 * ## Why llmstore specifically
 *
 * `llmstore/llms` is the only store in `WRITABLE_EXTENSION_STORES` whose
 * document can carry a gate. `LlmConfiguration.Task.toolApprovals`, when
 * present, **fully replaces** the agent-level `hitlConfig.toolApprovals` for
 * that task — the backend resolves
 * `task.getToolApprovals() != null ? task.getToolApprovals() : <agent default>`
 * (`LlmTask.java`, `ToolLoopResumer.java`) and honours an explicit task-level
 * policy as a designer opt-in. So a document with `requireApproval: []` buried
 * among forty other fields is a complete, permanent bypass that reads as an
 * ordinary config edit.
 *
 * With this guard the operator can change what an agent *says and runs* —
 * prompt, model, tool switches, budgets — and cannot change what *gates* it.
 *
 * ## Why absence is safe, not merely unchecked
 *
 * A body with no `toolApprovals` leaves the field `null`, so the agent-level
 * gate applies. That means the operator cannot use an llmstore write to remove
 * a gate that was protecting anything: falling back to the agent-level gate can
 * only leave a task as protected as its own agent already was. An agent with no
 * agent-level gate was already ungated before the edit.
 *
 * The residual, stated plainly: a task-level override that was *stricter* than
 * its agent-level gate loses that extra strictness if the operator rewrites the
 * document without it. That narrows one task's special protection; it never
 * produces an ungated agent, and a human still approves the whole document.
 *
 * ## Why an unpinned request is refused
 *
 * `requestPinned` false means the preview is best-effort and the actual request
 * can still change before it runs. Reading such a body and finding no
 * `toolApprovals` establishes nothing about what will execute. Since this is a
 * control rather than an advisory, "cannot be reasoned about" has to mean
 * "refused" — otherwise the guard is bypassed by the one request shape it cannot
 * see.
 *
 * ## Why a truncated body is refused
 *
 * The preview body is capped at 8 KiB by the backend
 * (`PendingToolCallBatch.PREVIEW_BODY_MAX_BYTES`). Past that, `toolApprovals`
 * could sit in the part that was cut, and "we did not see it" is not "it is not
 * there". The same asymmetry `self-guard.ts` reasons from decides this: a false
 * positive costs one refused approval and a manual edit in the manager; a false
 * negative costs the gate. It is also the honest reading of the approval
 * contract — an approver who cannot see the whole document cannot meaningfully
 * approve it.
 *
 * ## Scope, stated honestly
 *
 * Manager-side, so it governs the Manager's three approval surfaces (operator
 * chat, approvals inbox, conversation detail) and not the Slack buttons or the
 * MCP `resume_conversation` tool, which decide the same pause through different
 * code. Closing that properly needs the backend to refuse a task-level
 * `toolApprovals` that WEAKENS the inherited agent-level gate — the same
 * defensive demotion it already applies to an inherited `AUTO_APPROVE` timeout
 * policy (`ConversationService.applyEffectiveToolTimeoutPolicy`). Treat this as
 * removing the easy path, not as a boundary.
 */

/** Why one call was refused. Distinguishes the failure modes for the UI. */
export type GateCarryingReason = "carries-gate" | "unverifiable-body" | "unpinned-request";

/** A call refused because its body could set, or might set, an approval gate. */
export interface GateCarryingCall {
  callId: string;
  reason: GateCarryingReason;
}

/**
 * Whether a resolved request URI writes an LLM configuration document.
 *
 * Substring match on the store path rather than a parse, for the same reason
 * `uriTargetsAgent` does: the cost of matching one URI too many is a refused
 * approval, and the cost of missing one is the gate.
 */
export function uriTargetsLlmStore(uri: string | null | undefined): boolean {
  if (!uri) return false;
  let decoded = uri;
  try {
    decoded = decodeURIComponent(uri);
  } catch {
    // A malformed escape sequence is not a reason to stop checking — fall back
    // to the raw string rather than returning false and allowing the write.
  }
  return decoded.toLowerCase().includes("/llmstore/llms");
}

/**
 * Whether a parsed body contains a `toolApprovals` key at ANY depth.
 *
 * Depth-agnostic on purpose. The field's documented home is
 * `configs[].task.toolApprovals`, but the operator composes the body itself, and
 * a guard that only looked where the field is *supposed* to be would be defeated
 * by a document shaped even slightly differently — including a shape a later
 * backend version accepts. Matching the key anywhere costs nothing: no other
 * field in an LLM configuration is called this.
 */
function containsToolApprovalsKey(node: unknown): boolean {
  if (Array.isArray(node)) return node.some(containsToolApprovalsKey);
  if (typeof node !== "object" || node === null) return false;
  for (const [key, value] of Object.entries(node)) {
    if (key === "toolApprovals") return true;
    if (containsToolApprovalsKey(value)) return true;
  }
  return false;
}

/**
 * The subset of pending calls whose LLM-config write must not be approved.
 *
 * Only WRITE methods count — reading an LLM configuration is how the operator
 * answers "what prompt is this agent running?", and refusing that would break
 * introspection to prevent nothing.
 *
 * A call with no resolved preview is NOT refused here: it is also not pinned, so
 * it carries its own separate warning, and refusing every unpreviewable call
 * would block legitimate non-http tools entirely. That is the same line
 * `findSelfTargetedCalls` draws. Note the difference from a *truncated* body,
 * which IS refused — there the request is previewable and pinned, we simply
 * cannot see all of what we would be approving.
 */
export function findGateCarryingCalls(
  calls: readonly PendingToolCallView[] | null | undefined,
): GateCarryingCall[] {
  if (!calls) return [];
  const found: GateCarryingCall[] = [];
  for (const call of calls) {
    const preview = call.requestPreview;
    if (!preview) continue;
    const method = (preview.method ?? "").toUpperCase();
    if (method === "GET" || method === "HEAD" || method === "") continue;
    if (!uriTargetsLlmStore(preview.uri)) continue;

    // No body at all on a write to this store: nothing can carry a gate, so
    // there is nothing to refuse. (A bodyless PUT would fail server-side for
    // its own reasons; that is not this guard's business.)
    const body = preview.body;
    if (body == null || body.trim() === "") continue;

    // An UNPINNED http call is previewed best-effort: per PendingToolCallView's
    // own contract the request "can still change before it runs" (a call with
    // pre-request property instructions). Inspecting that body proves nothing —
    // a preview without `toolApprovals` can become a gate-carrying write between
    // approval and execution, which is a bypass of this entire control rather
    // than a gap in it. Only a pinned request is re-checked against its
    // fingerprint immediately before execution, so only a pinned request can be
    // reasoned about.
    if (!call.requestPinned) {
      found.push({ callId: call.callId, reason: "unpinned-request" });
      continue;
    }

    if (preview.bodyTruncated) {
      found.push({ callId: call.callId, reason: "unverifiable-body" });
      continue;
    }

    let parsed: unknown;
    try {
      parsed = JSON.parse(body);
    } catch {
      // Unparseable JSON on a JSON endpoint cannot be shown to be gate-free.
      // Fail closed rather than fall back to a substring test, which would
      // pass a body that merely spells the key differently.
      found.push({ callId: call.callId, reason: "unverifiable-body" });
      continue;
    }

    if (containsToolApprovalsKey(parsed)) {
      found.push({ callId: call.callId, reason: "carries-gate" });
    }
  }
  return found;
}
