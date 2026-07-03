# Human-in-the-Loop (HITL) Framework

The HITL framework lets you insert **human approval gates** into AI agent conversations. It works on **both surfaces**:

| Surface | Trigger | Pause state | Use case |
|---------|---------|-------------|----------|
| **Regular 1:1** | `PAUSE_CONVERSATION` action in behavior rules | `AWAITING_HUMAN` | Single-agent pipelines needing human checkpoints |
| **Group conversations** | `requiresApproval: true` on a phase | `AWAITING_APPROVAL` | Multi-agent orchestration needing oversight |

---

## Why Two Surfaces?

The two surfaces exist because regular and group conversations have fundamentally different execution models — and therefore different natural "pause points."

### Regular 1:1: Action-Driven

A regular conversation runs a **linear pipeline** of lifecycle tasks (parse → behavior rules → output → templating → …). The natural pause point is **between tasks in the pipeline**, triggered by a behavior rule emitting the reserved `PAUSE_CONVERSATION` action.

This is **opt-in per turn**: whether to pause depends on runtime conditions (user intent, slot values, context). Behavior rules are EDDI's existing mechanism for conditional actions — reusing them keeps the logic in configuration, not Java.

The `hitlConfig` on `AgentConfiguration` only controls **timeout behavior** — the actual triggering is in the behavior rules. Without a rule emitting `PAUSE_CONVERSATION`, `hitlConfig` does nothing.

### Group: Config-Driven

A group conversation runs a **structured pipeline of phases** (Opinion → Challenge → Synthesis). "Should this phase be reviewed?" is a design-time decision — a boolean on the phase definition (`requiresApproval: true`).

### Granularity (group only)

| Granularity | Semantics |
|-------------|-----------|
| **PHASE** (default) | Pause after the phase's repeats fully complete. Resume advances to the **next** phase. |
| **TASK** | For **EXECUTE phases only**: each task result is individually submitted for approval; resume **re-enters the same phase** so approved/retried tasks and their dependents can continue. |

> [!IMPORTANT]
> `granularity: "TASK"` only changes behavior for EXECUTE phases (they have a task list). A non-EXECUTE phase with `requiresApproval: true` **falls back to a PHASE-style pause** — the gate is never silently skipped.

---

## Surface 1: Regular 1:1 Conversations

### How It Works

```
User sends message
  └→ Pipeline executes: Parse → BehaviorRules → Output → Templating
       └→ Rule emits PAUSE_CONVERSATION action
            └→ LifecycleManager detects the action was ADDED by this task
                 └→ Conversation pauses:
                      ├→ state = AWAITING_HUMAN
                      ├→ bookmark: workflowId + absolute task index + pausedAt
                      ├→ timeout policy copied into the bookmark
                      └→ one-shot timeout schedule armed (if finite policy)

⏸️ Conversation is paused — say() is rejected promptly with 409, undo/redo return 409

Human reviews → POST /agents/{conversationId}/resume with a decision
  └→ REJECTED → remaining pipeline tasks are skipped, state = READY
  └→ APPROVED → pipeline resumes from the bookmarked task + 1
       └→ Remaining tasks (output, templating, …) execute normally
```

**While paused, user input is rejected without being consumed**: `POST /agents/{id}/say` returns `409 Conflict` immediately with a body directing to the resume endpoint — the message is *not* queued and *not* processed. The same applies if a pause commits in the narrow window after a message was accepted (the turn is skipped and answered with `409` instead of executing against the paused conversation). Chat clients should render "awaiting approval" and disable input until the decision lands.

### Configuration

```json
{
  "hitlConfig": {
    "approvalTimeout": "PT15M",
    "timeoutPolicy": "AUTO_REJECT",
    "pauseReason": "Account deletion requires manager sign-off"
  }
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `approvalTimeout` | ISO-8601 duration (`"PT30S"`, `"PT15M"`) | `null` | Time before the timeout policy fires. Required for finite policies. |
| `timeoutPolicy` | `AUTO_APPROVE` \| `AUTO_REJECT` \| `ABORT` \| `WAIT_INDEFINITELY` | `WAIT_INDEFINITELY` | What happens when the timeout expires. |
| `pauseReason` | string, ≤ 500 chars | `null` | Approver-facing text shown in pending-approval listings and approval-status — answers "what am I approving?". Falls back to a generic reason when absent. |

Validation happens at **save time** (and on ZIP import): a finite policy without a valid positive `approvalTimeout` (or a malformed duration like `"30m"`, or an overlong `pauseReason`) is rejected with an actionable 400 — it never degrades silently to wait-forever at runtime.

### Behavior Rule (the actual trigger)

```json
{
  "name": "Pause for deletion approval",
  "actions": ["PAUSE_CONVERSATION"],
  "conditions": [
    { "type": "inputmatcher", "configs": { "expressions": "delete_account(*)" } }
  ]
}
```

`PAUSE_CONVERSATION` is a **reserved action** (like `CONVERSATION_START`, `CONVERSATION_END`, `STOP_CONVERSATION`). Do not reuse it as a quick-reply expression.

### REST API

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/agents/{conversationId}/resume` | Submit a decision (`{"verdict": "APPROVED"\|"REJECTED", "note": "..."}` — verdict is case-insensitive) |
| `GET` | `/agents/{conversationId}/approval-status` | Pause summary: `state`, `pausedAt`, `pauseReason`, `timeoutPolicy`, `approvalTimeout` (render the auto-decision deadline as `pausedAt + approvalTimeout`). `?detail=full` for the whole snapshot — approver-only callers get `full` only while the conversation is paused, `403` otherwise |
| `GET` | `/agents/pending-approvals?limit=200` | List paused conversations as summaries (bounded, max 1000). Non-admin/non-approver callers get their own conversations, filtered *inside* the query — the limit applies after the owner restriction, so a personal inbox is never starved by other users' backlog |
| `POST` | `/agents/{conversationId}/cancel` | Cancel a paused (or same-pod running) conversation |

Status codes are discriminating: `400` invalid body (missing verdict, note > 4 KB — the pause is **not** consumed), `404` unknown conversation, `409` not in a resumable state (body names the current state), `200` success. HITL operations on a conversation whose descriptor is missing (legacy/corruption) fail **closed** — only admins/approvers may act on them.

### What the Agent Sees After a Decision

| Where | Key | Notes |
|-------|-----|-------|
| Templates (same turn, APPROVED) | `{{memory.current.hitlDecision}}`, `{{memory.current.hitlDecisionNote}}` | Conversation output written at resume time |
| Properties (this + later turns) | `{properties.hitlVerdict}` | Conversation-scoped property — next-turn behavior rules can react via a property matcher |
| Raw step data (pipeline tasks) | `hitl:decision_verdict`, `hitl:decision_note`, `hitl:decision_by` | Not template-accessible (colon keys) |

On **REJECTED**, the remaining pipeline tasks are skipped (the actions that would have triggered API calls are still in the step — they must not run) and a public output message with the reviewer's note is emitted so UIs render feedback.

---

## Surface 2: Group Conversations

### Configuration

```json
{
  "hitlConfig": {
    "granularity": "TASK",
    "approvalTimeout": "PT10M",
    "timeoutPolicy": "AUTO_APPROVE",
    "onTaskRejection": "RETRY"
  },
  "phases": [
    { "name": "Execution", "type": "EXECUTE", "requiresApproval": true, "...": "..." }
  ]
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `granularity` | `PHASE` \| `TASK` | `PHASE` | See [Granularity](#granularity-group-only) |
| `approvalTimeout` / `timeoutPolicy` | as above | `WAIT_INDEFINITELY` | Validated at save time |
| `onTaskRejection` | `FAIL` \| `RETRY` | `FAIL` | `FAIL`: rejected task is terminal. `RETRY`: task is re-queued (ASSIGNED) with the reviewer's note as feedback — the agent re-executes it addressing the rejection reason. |
| `phases[n].requiresApproval` | boolean | `false` | Per-phase approval gate |

### REST API

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/groups/{groupId}/conversations/{gcId}/approve` | Approve/reject (optionally per-task) |
| `POST` | `/groups/{groupId}/conversations/{gcId}/approve/stream` | Same, with SSE progress events |
| `GET` | `/groups/{groupId}/conversations/{gcId}/approval-status` | Pause summary (state, pausedAt, phase, pauseType, reason, timeoutPolicy, awaiting task ids); `?detail=full` returns the whole conversation incl. transcript — approver-only callers get `full` only while paused, `403` otherwise |
| `GET` | `/groups/{groupId}/conversations/pending-approvals?limit=100` | This group's paused conversations as summaries (bounded, max 1000) |
| `GET` | `/groups/pending-approvals?limit=100` | **Global approval inbox** across all groups (bounded, max 1000): admins/approvers see everything, other callers their own conversations |
| `POST` | `/groups/{groupId}/conversations/{gcId}/cancel` | Cancel the discussion — `409` if it is already in a terminal state |

The `{groupId}` path segment is validated: a conversation that does not belong to the given group returns `404` on read/delete/cancel/approve/approval-status. A discussion deleted concurrently with an approve/cancel also returns `404` (never a misleading `409` state conflict).

**Approval bodies:**

```json
// Phase-level
{ "decision": { "verdict": "APPROVED", "note": "Proceed" } }

// Task-level (selective; values are case-insensitive)
{ "decision": { "verdict": "APPROVED" },
  "taskApprovals": { "task-abc": "APPROVED", "task-def": "REJECTED" } }

// Task-level shortcut: verdict APPROVED with no taskApprovals approves ALL pending tasks
```

Unknown task IDs, tasks not awaiting approval, and unknown decision values (anything other than case-insensitive `APPROVED`/`REJECTED`) are rejected with `400` **before anything is applied** — the pause and its timeout schedule survive a bad request. An explicit empty `taskApprovals` map (`{}`) behaves like the approve-all shortcut. Resume-vs-resume and cancel-vs-approve races are arbitrated by a DB-level compare-and-set; the loser gets `409`.

**SSE events:** `awaiting_approval` (stream closes; re-attach via `/approve/stream`), `hitl_resume` (emitted once the approve commits — the stream stays open for the resumed discussion's events), `cancelled` and `group_complete`/`group_error` (stream closes), plus the standard discussion events. Every terminal outcome — including REJECTED verdicts, config-drift aborts, and resume failures — emits a closing event, so `/approve/stream` clients never hang.

### Config drift protection

Pauses can last days. On resume, the bookmarked phase (index + name) is verified against the **current** group config; if phases were added, removed, or reordered in between, the resume is aborted and the **pause is restored** (state back to `AWAITING_APPROVAL`, timeout schedule re-armed, `group_error` emitted with the mismatch details) — fix the config and approve again, or cancel. Transient failures before the resumed execution starts restore the pause the same way instead of failing the discussion terminally.

### No-progress protection (TASK granularity)

A TASK-gate pause caused by turn-budget exhaustion could previously loop forever under `AUTO_APPROVE` (approve → zero work → identical re-pause). Resumes now fingerprint the pause (phase + non-terminal task states): an **explicit human APPROVED grants a fresh turn budget**; if a resumed leg re-pauses with an identical fingerprint (no progress), the discussion **fails with an actionable error** instead of pausing again — guaranteed termination. Automated (`system:*`) approvals never grant fresh budget, so a timeout policy cannot sustain the loop.

---

## Timeout Policies

| Policy | On timeout | Typical use case |
|--------|-----------|------------------|
| `WAIT_INDEFINITELY` | Nothing is scheduled; waits forever | Critical decisions (compliance, safety) |
| `AUTO_APPROVE` | APPROVED decision (`decidedBy: system:timeout`), resumes | Progress monitoring, non-critical gates |
| `AUTO_REJECT` | REJECTED decision → regular: turn skipped; group: `FAILED` | Strict SLA gates |
| `ABORT` | Cancels the conversation/discussion | Safety-critical pipelines |

Timeouts are one-shot schedules on EDDI's cluster-aware `ScheduleFireExecutor` — exactly one pod owns each claim, and they route through the **same** resume/cancel machinery as human decisions. Delivery is at-least-once (a lease-expired claim can be re-stolen after a pod crash), but that is safe: the timeout resolves through the resume/cancel state CAS, so a duplicate fire against an already-resolved conversation is a no-op.

---

## Who May Decide

Approve/reject/cancel/status require, in order: the **conversation owner**, the **`eddi-admin`** role, or the dedicated **`eddi-approver`** role. Ownerless (legacy/internal) conversations fail **closed** for non-admin/non-approver callers. `decidedBy` is always set server-side from the authenticated identity — it cannot be spoofed via the request body. Approvers and admins see all entries in the pending-approvals listings; other users only their own.

**Approver read scope:** the approver role exists to *decide* pending approvals, not as a universal read grant. Approver-only callers (not owner, not admin) may read `approval-status?detail=full` — the full memory snapshot / transcript — only while the conversation is actually awaiting approval; outside a pause they get `403` and can use the summary view.

## Auditing

Every HITL decision — human or automated — writes an immutable `hitl.approval` entry to the audit ledger (verdict, decider, automated flag, note), and the resumed pipeline leg records its task entries like any other turn. Cancelling a pending approval is a decision too: it writes an `hitl.approval` entry with verdict `CANCELLED`, the cancel mode, and the **cancelling actor** (`decidedBy`: the authenticated principal, or a `system:*` identifier such as `system:timeout` for ABORT policies and `system:group` for auto-cancelled member pauses). This is the EU AI Act human-oversight trail.

## Crash Recovery

On startup, `HitlCrashRecoveryObserver` **repairs** HITL state; it never destroys a legitimate pause. The sweep runs on a background thread (application readiness does not block on it) and reads bounded projected summaries, not full conversation documents:

- Paused conversations with a **finite** policy get their one-shot timeout schedule idempotently re-armed at the original due time (or shortly after startup if overdue). After creating the schedule the pause state is re-checked — if a resume/cancel landed in the window, the schedule is withdrawn.
- `WAIT_INDEFINITELY` pauses are left untouched (and skipped without any further read).
- Regular conversations stuck `IN_PROGRESS` with an intact HITL bookmark (pod died mid-resume) are restored to `AWAITING_HUMAN` so the approval can be re-issued.

Config: `eddi.hitl.crash-recovery.enabled` (default `true`), `eddi.hitl.crash-recovery.recover-in-progress` (default `true`; consider `false` in multi-pod deployments — see the class Javadoc for the rolling-restart caveat).

## Operations

- **Metrics** (`/q/metrics`): `eddi_hitl_pause_count`, `eddi_hitl_resume_count`, `eddi_hitl_timeout_count`, each tagged `surface=regular|group`; `eddi_group_member_pause_skipped_count` for auto-cancelled member pauses inside groups.
- **Undeploy**: paused conversations do **not** count as active — an agent version with pending approvals can be undeployed. Resuming afterwards returns `409 agent not deployed` and the pause is restored (redeploy, then retry). The idle-conversation cleanup sweep likewise **spares** `AWAITING_HUMAN` conversations — a pending approval is never silently force-ended by maintenance.
- **Cancel semantics (regular)**: cancels a paused conversation, or signals a turn executing on the same pod to stop at the next task boundary. `CANCEL_IMMEDIATE` currently degrades to graceful on the regular surface. Cancelling an idle conversation returns `409` (use `endConversation`).
- **Timeout schedules are not manually operable**: HITL timeout schedules live in the schedule store but the schedule REST surface refuses to fire them manually (`409`, use `/resume` or `/cancel` — manual firing would bypass the approval authz), restricts update/delete/enable/disable to `eddi-admin` (`403` otherwise, so an editor cannot disarm an ABORT/AUTO_REJECT safety timeout), and redacts them from non-admin listings.
- **Pause retention (optional)**: `eddi.hitl.pending.max-age` (ISO-8601 duration, default **off**) auto-cancels pauses older than the threshold — audited, schedule-disarmed, via the normal cancel path; `eddi.hitl.pending.sweep-interval` (default 6h) controls the sweep cadence. Under the default `WAIT_INDEFINITELY` policy, pauses otherwise accumulate until decided.
- **Mass-timeout drain**: due schedules are claimed in configurable batches (`eddi.schedule.poll-batch-size`, default 100) and fired concurrently on virtual threads with per-fire error isolation.

## Slack Integration

The Slack channel integration is HITL-aware end to end. Configuration lives in the Slack `ChannelIntegrationConfiguration.platformConfig` (both keys optional):

| Key | Description |
|-----|-------------|
| `hitlApprovalChannel` | Slack channel id that receives approval notifications when a conversation pauses. |
| `hitlApproverUserIds` | Comma-separated Slack user ids allowed to decide via buttons. **Without this list, buttons are not rendered and interactive decisions are rejected — fail-closed.** |

Behavior:

- **In-thread pause notice** — when a turn pauses, the thread receives the output so far plus "⏸️ awaiting human approval" with the `pauseReason`. Messages sent while paused get "Still awaiting approval" instead of a generic error (the input is not consumed).
- **Approval inbox message** — if `hitlApprovalChannel` is set, an interactive Block Kit message is posted there (conversation, agent, reason, timeout deadline) with Approve/Reject buttons. Only the pause reason is shared — no conversation content (data minimization).
- **Deciding from Slack** — buttons post to `POST /integrations/slack/interactive` (configure this as the Slack app's *Interactivity Request URL*; requests are signature-verified like the events webhook). The acting Slack user must be in `hitlApproverUserIds`; decisions are attributed `decidedBy: slack:<userId>` in the audit trail. Double-clicks and already-decided races update the message ("already resolved") without error spam. Group pauses work the same way (the button value carries `group:<conversationId>` and routes to the group approve machinery).
- **Continuation push** — when the pause is resolved (human, Slack button, or `system:timeout`), the originating thread automatically receives the verdict and the resumed conversation's output — no polling. This works cross-pod: the resume fires an async CDI event (`HitlResumeCompletedEvent`) and the Slack observer resolves the thread from the persistent conversation mapping.
- **Group discussions** — Slack-driven group discussions post pause/resume/cancel notices (and `member_pause_skipped` explanations) into their thread.

**Delegated/managed conversations** (agent-to-agent tools, MCP `chat_managed`): when a nested conversation pauses, the calling tool receives a structured `PAUSED_FOR_APPROVAL` result naming the conversation and reason instead of hanging — the delegated approval stays pending (it is *not* auto-cancelled; a reviewer decides, then the tool can be re-invoked).

## MCP Surface

The HITL operations are also exposed over the MCP server (`McpHitlTools`), so an MCP client that drives a conversation (`talk_to_agent` / `chat_managed`) and receives a `PAUSED_FOR_APPROVAL` result can resolve the gate over the **same transport** instead of switching to REST. That paused payload names the tool to call next (`"suggestNextTool": "resume_conversation"`).

| MCP tool                          | Mirrors REST                                                  | Notes                                                                                               |
| --------------------------------- | ------------------------------------------------------------ | --------------------------------------------------------------------------------------------------- |
| `list_pending_approvals`          | `GET /agents/pending-approvals`                              | Owner-scoped; includes RULE and TOOL_CALL pauses                                                    |
| `get_approval_status`             | `GET /agents/{id}/approval-status`                           | Summary reports `pauseType`; `detail=full` returns the snapshot (incl. any pending tool-call batch) |
| `resume_conversation`             | `POST /agents/{id}/resume`                                   | A single verdict resolves both RULE and TOOL_CALL pauses                                             |
| `cancel_conversation`             | `POST /agents/{id}/cancel`                                   |                                                                                                     |
| `list_group_pending_approvals`    | `GET /groups/{groupId}/conversations/pending-approvals`      | Owner-scoped                                                                                         |
| `list_all_group_pending_approvals`| `GET /groups/pending-approvals`                             | Cross-group inbox                                                                                    |
| `get_group_approval_status`       | `GET /groups/{groupId}/conversations/{gcId}/approval-status` | Summary only; `detail=full` returns the whole conversation                                          |
| `approve_group_phase`             | `POST /groups/{groupId}/conversations/{gcId}/approve`        | Optional `taskApprovals` JSON for TASK granularity; blocks and returns the resumed discussion (no SSE variant over MCP) |
| `cancel_group_discussion`         | `POST /groups/{groupId}/conversations/{gcId}/cancel`        |                                                                                                     |

**Authority is identical to REST** — the shared `HitlAccessGuard` enforces owner / `eddi-admin` / `eddi-approver` per conversation (fail-closed on a missing descriptor), and the decision is attributed **server-side** to the authenticated caller, prefixed `mcp:` (e.g. `mcp:alice`, mirroring the `system:timeout` convention) — never taken from a tool argument. Errors are structured JSON (`errorCode` ∈ `NOT_FOUND | WRONG_STATE | FORBIDDEN | DISABLED | BAD_REQUEST`) so a programmatic client can branch on the failure kind.

**Kill-switch:** set `eddi.mcp.hitl.mutations.enabled=false` to make MCP a **read-only** HITL surface — `resume_conversation`, `cancel_conversation`, `approve_group_phase`, and `cancel_group_discussion` then return a `DISABLED` error while the read-only list/status tools keep working. MCP is a transport, not a new authority: no tool lets an agent approve its own gate.

## Known Limitations (v1)

- **Member-level HITL inside a group**: a member agent's own `PAUSE_CONVERSATION` rule firing during a group turn is not supported — the turn is recorded as SKIPPED with an explanatory note, the member's stranded pause is auto-cancelled (audited as `system:group`), and a `member_pause_skipped` SSE event is emitted. Use group-level HITL (`requiresApproval`) instead.
- **Nested groups**: `requiresApproval` inside a sub-group of a group-of-groups is not supported — the sub-pause is cancelled and the member turn is recorded as SKIPPED with an explanatory note.
- **Cross-pod cancel of an actively-running turn**: the cooperative cancel flag is per-pod; the DB CAS covers paused states cluster-wide, and a running group leg re-checks the persisted state at every phase boundary, so a cross-pod cancel takes effect at the next boundary.
- **Tool-level HITL** (pausing inside an LLM tool call) is deferred.
- **Managed REST conversations** (`RestAgentManagement`): a managed intent mapping stays pinned to a paused conversation until it is resumed or reset — a managed `say` against it answers `409` promptly, but the mapping is not automatically re-created while the approval is pending.
- **Upgrade notes**:
  - `DiscussionPhase.requiresApproval` existed before this feature as an inert placeholder. Stored group configs that set it to `true` will begin pausing for approval after this upgrade.
  - `PAUSE_CONVERSATION` is a **newly reserved action name**. A stored behavior-rule config that already emitted an action with this exact name will begin pausing conversations after this upgrade — rename such actions before upgrading.
  - During a rolling upgrade, pods running the previous version cannot read conversations paused by new pods (unknown `AWAITING_HUMAN` state) — schedule pause-heavy traffic after the rollout completes.
