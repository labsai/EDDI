import type { PendingToolCallView } from "@/lib/api/hitl";
import { parseJsonPatch, type JsonPatchOp } from "./json-patch";

/**
 * Refuses a gated write that would set an approval gate from inside an LLM
 * configuration document.
 *
 * Like `self-guard.ts` and unlike `escalation-flags.ts`, this is a **control**:
 * the Manager's approval surfaces disable Approve outright when it fires.
 *
 * ## Where it holds — defence in depth, not the boundary
 *
 * This runs in the BROWSER, inside the Manager's approval UI (the inbox, the
 * approval banner, the operator drawer). A pause can also be decided without
 * the Manager: over the REST approval endpoint, an MCP tool, or a Slack approval
 * channel — none of which ever load this file. On those paths the client guard
 * does not exist, so the backend's own validation of the approved call is what
 * enforces the rule (review 2026-10-02: server-side enforcement belongs to the
 * HITL engine). Keep this guard: it refuses early, explains why, and keeps the
 * Approve button honest — but never describe it as the thing that makes a
 * gate-carrying write impossible.
 *
 * The distinction
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
 * ## Patches (RFC 6902)
 *
 * The operator edits an LLM configuration with `PATCH /llmstore/llms/{id}`, whose
 * body is a list of operations rather than a document, so "does the body contain
 * `toolApprovals`" is no longer the whole question. A patch is refused when:
 *
 *  - an operation's `path` or `from` names `toolApprovals` anywhere — a
 *    substring test on the raw pointer, so a malformed pointer that merely spells
 *    it fails closed too — at any depth, for any operation, `test` and `remove`
 *    included: the guard is about the field being in play at all, not about which
 *    way the patch moves it;
 *  - an operation's `value` contains a `toolApprovals` key at any depth. That is
 *    also what covers replacing a whole task, the whole `/tasks` array or the
 *    root (`""`) with a value that carries one;
 *  - an operation is `copy` or `move`, or anything but `add`, `replace`, `remove`
 *    or `test`. Those two move a part of the STORED document to somewhere else
 *    without the body naming what — `copy /tasks/0 -> /tasks/1` would duplicate
 *    task 0's gate override onto task 1 — and the same blindness would let them
 *    duplicate a stored credential reference into a field that sends it
 *    somewhere. Neither is needed to edit a configuration, so they are refused
 *    rather than reasoned about;
 *  - the body is not a well-formed patch, or cannot be read in full (the same
 *    truncated / unparseable / unpinned rules as a `PUT`).
 *
 * What stays allowed is, again, the absence case: a patch that never names the
 * field leaves whatever the stored document holds exactly as it is, and a
 * `replace` of a whole task by one with no override falls back to the agent-level
 * gate — the same narrowing the residual below already states for a `PUT`.
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
export type GateCarryingReason = "carries-gate" | "unverifiable-body" | "unpinned-request" | "unsupported-patch-op";

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

/** The only patch operations the operator may use — see "Patches" above. */
const ALLOWED_PATCH_OPS: ReadonlySet<string> = new Set(["add", "replace", "remove", "test"]);

/** Whether a JSON Pointer names the gate field. Case-insensitive and substring on purpose. */
function pointerNamesGate(pointer: string | undefined): boolean {
  return typeof pointer === "string" && pointer.toLowerCase().includes("toolapprovals");
}

/**
 * How a patch relates to the gate: touching it outranks using an operation the
 * guard will not reason about, so the approver is told the more serious thing.
 */
function judgePatch(ops: readonly JsonPatchOp[]): GateCarryingReason | null {
  let unsupported = false;
  for (const op of ops) {
    if (pointerNamesGate(op.path) || pointerNamesGate(op.from)) return "carries-gate";
    if (op.value !== undefined && containsToolApprovalsKey(op.value)) return "carries-gate";
    if (!ALLOWED_PATCH_OPS.has(op.op)) unsupported = true;
  }
  return unsupported ? "unsupported-patch-op" : null;
}

/**
 * The subset of pending calls whose LLM-config write must not be approved.
 *
 * Only WRITE methods count — reading an LLM configuration is how the operator
 * answers "what prompt is this agent running?", and refusing that would break
 * introspection to prevent nothing.
 *
 * A call with no resolved preview at all is NOT refused here: it is also not
 * pinned, so it carries its own separate warning, and refusing every
 * unpreviewable call would block legitimate non-http tools entirely. That is the
 * same line `findSelfTargetedCalls` draws.
 *
 * Everything else about an llmstore write IS decided, and the checks run in a
 * deliberate order — pinned first, then body. Three outcomes, none of which can
 * be reached by an unpinned call:
 *
 *  - *truncated* or unparseable body → refused; the request is pinned, we simply
 *    cannot see all of what we would be approving.
 *  - *absent* body → allowed; the request is pinned, so what was previewed is
 *    what runs, and it carries nothing.
 *  - body containing `toolApprovals` at any depth → refused.
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

    // An UNPINNED http call is previewed best-effort: per PendingToolCallView's
    // own contract the request "can still change before it runs" (a call with
    // pre-request property instructions). Inspecting that body proves nothing —
    // a preview without `toolApprovals` can become a gate-carrying write between
    // approval and execution, which is a bypass of this entire control rather
    // than a gap in it. Only a pinned request is re-checked against its
    // fingerprint immediately before execution, so only a pinned request can be
    // reasoned about.
    //
    // This is checked BEFORE the body, and the order is load-bearing. The body
    // checks below all reason from what the preview shows; on an unpinned call
    // the preview is not what will run, so *no* body shape — including an empty
    // or absent one — establishes anything. Testing the body first let an
    // unpinned write with a null preview body fall straight through this guard:
    // "we were shown no body" is not "no body will be sent".
    // `PendingToolCallView.requestPinned` documents this combination as real —
    // "a best-effort preview can exist while this is false" — and
    // `ResolvedRequestPreview.body` is optional, so it is reachable by contract.
    if (!call.requestPinned) {
      found.push({ callId: call.callId, reason: "unpinned-request" });
      continue;
    }

    // No body at all on a PINNED write to this store: the request is fixed and
    // carries nothing, so there is nothing to refuse. (A bodyless PUT would fail
    // server-side for its own reasons; that is not this guard's business.)
    const body = preview.body;
    if (body == null || body.trim() === "") continue;

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

    // A PATCH body is a list of operations, and what it does to the gate is not a
    // property of any one key in it — see "Patches" above. Anything else keeps the
    // whole-document test, which a patch body would also trip on through `value`.
    if (method === "PATCH") {
      const patch = parseJsonPatch(parsed);
      if (!patch.ok) {
        found.push({ callId: call.callId, reason: "unverifiable-body" });
        continue;
      }
      const reason = judgePatch(patch.ops);
      if (reason) found.push({ callId: call.callId, reason });
      continue;
    }

    if (containsToolApprovalsKey(parsed)) {
      found.push({ callId: call.callId, reason: "carries-gate" });
    }
  }
  return found;
}
