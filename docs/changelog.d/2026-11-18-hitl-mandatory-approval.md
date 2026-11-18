## ✨ feat(hitl): mandatory, argument-aware tool approvals (2026-11-18)

**Repo:** EDDI (`feat/hitl-mandatory-approval`) — PDF documents plan, work package WP-0.2

### What changed and why

The tool approval gate matches a call by its address (`source.method:path`, `source:name`, bare
name), never by its arguments; `exempt` always beats `requireApproval`, and an empty
`requireApproval` turns the gate off. So "pause every call that carries a file out of the
deployment" and "a team-library add always needs the user" could not be expressed, and an agent
designer could exempt either. Later packages (A2A outbound files, team library adds) need exactly
that (invariant I9). This adds the engine hook; **no tool uses it yet**, so behaviour on `main` is
unchanged.

- **Rules from the tool sources.** `ToolSourceProvider#mandatoryApprovals(ctx)` (default: none)
  returns per-turn `MandatoryApprovalRule`s that see a call's arguments and answer a
  `MandatoryApproval` (`reasonCode`, `ApproverRule`, a server-built summary). `ToolSourceRegistry`
  asks for them in the same try as `contribute`, so a source whose rules cannot be built loses its
  tools for the turn. They ride on `AgentOrchestrator.ToolSetup` through the LAZY completion and the
  reply tool to the live loop and the resume.
- **Gated before P1–P3.** `ToolApprovalGate.classify(…, mandatoryRules)` gates a matching call
  before the pattern precedence (so `exempt` and an empty `requireApproval` do not apply) and
  carries its approval in `GateResult.mandatoryByCallId`. With no rule matching, the result equals
  the old overload's. A rule that throws gates the call for the conversation user (fail closed); a
  reissue of an approved, executed call is a repeat that keeps its approver. Tool-call ids are
  minted whenever rules exist, so a call is addressable with an inert pattern gate too.
- **Refused where nobody could decide** (`MandatoryApprovals.unpausableReason`): a group member's
  turn, a consent approval in a conversation without a user, an oversight approval without an
  active gate. The call is answered `NOT_EXECUTED` and never runs.
- **Who may approve** (`MandatoryApproverCheck`, on the snapshot the resume runs, before anything
  runs). `CONVERSATION_USER`: the conversation's user with `VERIFIED` provenance, or an
  administrator with a reason in the note. Anyone else gets `403` (`FORBIDDEN` over MCP) and the
  pause is restored untouched. A `system:*` decision never approves a mandatory call, and a batch
  holding one never gets an `AUTO_APPROVE` timeout. Amending a mandatory call is a `400` before the
  pause is consumed. `ANY_PERMITTED_APPROVER`: whoever may decide the pause today.
- **Execution side.** `ToolLoopResumer` runs a mandatory call only when the resume path attached a
  checked `MandatoryApprovalAttribution` for that call to the decision (per call: a batch may mix
  approver rules, and each call runs and is recorded with its own), re-asks the rules about amended arguments,
  records `actingAs`/`approvedBy`/`onBehalfOf` on the `hitl_approved` trace entry, and binds the
  attribution around the call (`ApprovedCallScope.attribution()`) so a record the call creates can
  store it.
- **Admin on behalf is never silent.** The `hitl.approval` audit entry carries `actingAs:
  ADMIN_ON_BEHALF`, `approvedBy`, `onBehalfOf`; an INFO line `hitl.mandatory.admin_on_behalf` carries
  the same fields with the ledger off.
- **Approver view.** `PendingToolCall` persists `mandatoryReasonCode`, `mandatoryApprover`,
  `mandatorySummary`; they appear in `approval-status` `pauseDetails.calls`, `detail=full` and the
  names-only projection. The operator gate dry-run documents that it reports pattern results only.
- **Channels.** REST and MCP resumes authenticate the approver; a Slack approval of a
  `CONVERSATION_USER` call is refused and logged (rejections work), and the timeout handler does
  not retry a refused automatic approval.

### Design decisions

- The approver check lives on the resume path next to the GDPR Art. 18 check, not in the REST
  layer, so REST, MCP, Slack and the timeout all pass through it; the resumer's check is the second,
  execution-side line.
- An administrator's reason is the decision's `note` (per-call note first): no new body field, and
  `HitlDecision` gains only a server-side, `@JsonIgnore` attribution.
- A group member's turn is refused up front rather than paused and auto-rejected by the group.
- A run without a user id is the "token-less run" arm for consent approvals; with authentication
  disabled there is no approver identity, so only rejections can decide a consent pause.

### Not done here

- No rule is registered by any source (WP-5b.2, WP-7.3, WP-7.4, WP-9.6 add them).
- Manager rendering of the reason (WP-5b.5) and the operator prompt text (folded into the next UI
  package, per the plan). No operator revision bump.

**Files:** [`ToolApprovalGate.java`](../../src/main/java/ai/labs/eddi/engine/hitl/tools/ToolApprovalGate.java),
[`MandatoryApprovals.java`](../../src/main/java/ai/labs/eddi/engine/hitl/tools/MandatoryApprovals.java),
[`MandatoryApproverCheck.java`](../../src/main/java/ai/labs/eddi/engine/hitl/tools/MandatoryApproverCheck.java),
[`ConversationHitlService.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationHitlService.java),
[`ToolLoopRunner.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java),
[`ToolLoopResumer.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopResumer.java),
[`hitl.md`](../hitl.md#mandatory-approvals), [`security.md`](../security.md#egress-needs-an-approval-the-designer-cannot-switch-off)
