## 🔒 fix(hitl): approvals run only what was shown, and only the right people decide (2026-11-11)

**Repo:** EDDI (`fix/hitl-decider-and-approval-display`)

Six findings from a security review of the HITL approval path, the earned-autonomy API, dead-letter
replay and self-continuation.

### What changed and why

- **Approvers no longer approve arguments they were not shown.** The approver reads
  `argumentsRedacted`, cut to the 32 KB display cap, while `argsTruncated` was only set above the
  256 KB stored cap and the resume ran the full raw arguments. A model could put benign fields first,
  pad past 32 KB and hide the decisive ones behind the cut. `ToolApprovalGateSupport.buildPendingBatch`
  now marks a call truncated whenever the approver copy was cut, so the existing `NOT_EXECUTED` path
  and amendment refusal apply; the Slack and email preview lines append a notice for such calls.
- **`eddi-approver` can no longer apply, dismiss or lift earned autonomy.** `OversightAccess.requireEditor`
  returned on `seesEverything()`, which is true for everyone while workspaces are off (the default), and
  the resource admits approvers. It now requires `eddi-admin` or `eddi-editor` first (plus `EDIT` under
  enforcement), and the three endpoints carry `@RolesAllowed({"eddi-admin","eddi-editor"})`. Suspend
  stays open to reviewers.
- **Who decides a tool call is configurable** — three `toolApprovals` fields, all defaulting to today's
  behaviour: `deciders` (`OWNER_OR_ROLE` | `ROLE_ONLY`, enforced in `HitlAccessGuard`, shared by REST and
  MCP; the owner keeps the listing and status reads), `allowArgumentAmendments` (`false` refuses
  `amendedArguments` with 400) and `requirePauseId` (a person's decision must name its pause; also
  deployment-wide as `eddi.hitl.require-pause-id`, which covers group approvals too). The strict
  task-level merge lets a task narrow these, never widen them; the suspension overlay copy carries them.
- **The resumed tool loop sees earned-autonomy suspensions.** `ToolLoopResumer` applied only
  `TaskToolApprovalsResolver`; under `task-approvals.mode=replace` a task-level config dropped the
  overlay, so after any approval a suspended pattern ran ungated. `AutonomyOverlayService.resolveEffective`
  is now the one resolution the live and resumed loops share.
- **A dead-letter replay no longer runs as the administrator.** `say` captured the caller from the
  admin's request thread, so a user's replayed turn spent the admin's `${caller:token}` and connection
  credentials. `ClusterAdminService` now submits the replay on `replayCompletions` with no caller bound;
  the claim, idempotency key, audit entry and synchronous outcome are unchanged.
- **Continuation fires re-check `USE`.** Continuations were routed past `creatorUseCheck`, so a user whose
  access to the agent was revoked under enforced workspaces still had the chain run. `ContinuationFireHandler`
  now calls `ResourceAccessGuard.checkPrincipalUse` for the owner (teams from the user directory) and
  stops the chain (`access_revoked`); an unanswerable descriptor store fails the fire for a retry.

### Design decisions

- `deciders` is enforced at the guard both human surfaces call, not in the resume service, so engine
  decisions (`system:timeout`, `system:group`) and Slack's approver lists are untouched.
- `requirePauseId` is checked on the snapshot the resume runs on, after the state CAS, and restores the
  pause on refusal (as a pause mismatch does) — no extra load on the common resume path.
- The continuation re-check leaves an owner the directory has no record of alone, mirroring
  `creatorUseCheck` for schedules without an access snapshot.

**Docs:** [HITL](../hitl.md#who-may-decide), [HITL Oversight](../hitl-oversight.md),
[Configuration reference](../configuration-reference.md), [Long Jobs](../long-jobs.md),
[Coordinator admin](../coordinator-admin.md), [Scheduling](../scheduling.md), [Metrics](../metrics.md)

**Files:** [`ToolApprovalGateSupport.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/orchestration/ToolApprovalGateSupport.java),
[`OversightAccess.java`](../../src/main/java/ai/labs/eddi/engine/hitl/oversight/rest/OversightAccess.java),
[`HitlAccessGuard.java`](../../src/main/java/ai/labs/eddi/engine/hitl/HitlAccessGuard.java),
[`HitlPauseIdPolicy.java`](../../src/main/java/ai/labs/eddi/engine/hitl/HitlPauseIdPolicy.java),
[`ToolLoopResumer.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopResumer.java),
[`ClusterAdminService.java`](../../src/main/java/ai/labs/eddi/engine/cluster/admin/ClusterAdminService.java),
[`ContinuationFireHandler.java`](../../src/main/java/ai/labs/eddi/engine/continuation/ContinuationFireHandler.java)

### Still open

- The Slack/email card shows only the first 300 characters of each call's arguments while its buttons
  approve the whole call (the ellipsis is visible). The Manager's Oversight page still offers apply /
  lift to approvers, who now get 403. The Manager's tool-approvals editor does not offer the three new
  fields yet.
