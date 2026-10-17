# Human Takeover / Handoff — Design for Review (WS7)

> **Status: design review — nothing here is approved work.** Written 2026-10-15 against
> `origin/main` @ `aa811c838`. It is the Phase 7.0 deliverable of WS7 in the
> competitor-research roadmap: implementation starts only after the maintainer signs off
> on the decisions in [§11](#11-decisions-for-the-maintainer). Every code reference below
> was read at that commit; re-locate by symbol name, line numbers drift.
>
> **Scope.** A live 1:1 conversation is handed to a human — by the agent's own rules, by
> the LLM, or by a person at a desk — who then talks to the end user directly while the
> pipeline is off, and later hands the conversation back. EDDI provides the **mechanism**
> (state, API, events, audit); the Manager is one client and an external helpdesk another
> ([Pillar 3](../docs/project-philosophy.md)). Group-level human participation (humans as
> group *members*, `AWAITING_HUMAN_INPUT`) already ships and is out of scope.

**Contents:** [1. Summary](#1-summary) · [2. Inventory](#2-inventory-every-branch-on-conversationstate) ·
[3. State model](#3-state-model-new-human_control-not-awaiting_human) ·
[4. Entry points](#4-entry-points) · [5. While in control](#5-while-in-human-control) ·
[6. Clients](#6-how-each-client-behaves) · [7. Audit](#7-audit) ·
[8. LLM history after release](#8-llm-history-after-release) · [9. RBAC](#9-rbac) ·
[10. Property patch on a HITL decision](#10-the-separable-item-property-patch-on-a-hitl-decision) ·
[11. Decisions](#11-decisions-for-the-maintainer) · [12. Events and integrations](#12-events-manager-and-external-helpdesks) ·
[13. Phasing, tests, traps](#13-phased-plan-tests-traps)

---

## 1. Summary

| Question | Recommendation |
| --- | --- |
| State | New `ConversationState.HUMAN_CONTROL`. Do **not** reuse `AWAITING_HUMAN` — 60+ call sites treat it as "refuse input, a decision resumes the pipeline", which is the opposite of takeover (§3). |
| Queue vs. claimed | One state; a persisted `humanControl` record says whether an operator has claimed it (`assignee == null` ⇒ waiting in the inbox). No second enum value. |
| Entry | Reserved action `HANDOFF_TO_HUMAN` (end-of-turn transition), opt-in built-in tool `request_human_agent` (emits that action), operator `POST /agents/{conversationId}/takeover`. |
| Paths | All under the existing engine prefix: `/agents/{conversationId}/takeover`, `…/takeover/messages`, `…/takeover/release`, plus `GET /agents/pending-takeovers` beside `pending-approvals`. **Not** `…/operator/…` — "Operator" is already the Manager's Platform Operator (an LLM assistant, `ui/manager/src/lib/operator/`). |
| User input while in control | Stored as an input-only step, no pipeline, answered at once; system callers (schedules, group members, sub-agent tools) are **refused**, not parked. |
| Operator message | A new output-only step per message, item marked `origin: "operator"`; the operator's principal goes to non-public step data and the audit ledger, never into the user-visible output. |
| Serialization | Every takeover write is a coordinator task (`submitInOrder`), so in cluster mode it runs under the conversation lease with its fencing token. The lease is never held for the duration of a takeover. |
| Audit | `operator.takeover` / `operator.message` / `operator.release` entries (`OPERATOR_TAKEOVER`/`MESSAGE`/`RELEASE` in `detail.event`), actor set server-side exactly as `hitl.approval`'s `decidedBy`; message text as digest + length only; state committed first, the entry journaled in the same document write and reconciled if the async ledger lost it (§7.1). |
| History after release | Operator turns become `AiMessage`s, **annotated** by default (configurable prefix), consecutive same-role messages merged so roles alternate. |
| RBAC | New role (working name `eddi-takeover`), endpoints enumerate `{"eddi-admin", "eddi-takeover"}`. The conversation **owner is never admitted** (unlike HITL). Approver role not reused. |
| Ship first | Property patch on a HITL `/resume` decision (§10) — independent, small, audited. |
| Size | Design S (this doc); implementation L, five PRs (§13). |

---

## 2. Inventory: every branch on `ConversationState`

[`ConversationState`](../src/main/java/ai/labs/eddi/engine/memory/model/ConversationState.java)
is `READY, IN_PROGRESS, ENDED, EXECUTION_INTERRUPTED, ERROR, AWAITING_HUMAN`. 52 main
classes reference it. The pattern that decides the risk of a new value:

- **Allow-lists** (`EnumSet.of(READY, …)`, Mongo `eq(READY)`) are safe by default: a new
  value is simply excluded.
- **Deny-lists** (`!= ENDED`, `== AWAITING_HUMAN || == IN_PROGRESS`, `nin(…)`) and
  `switch … default ->` are where `HUMAN_CONTROL` silently falls through to "idle,
  proceed". Those are the rows marked **Must**.

### 2.1 Engine core

| Location (symbol) | Today | `HUMAN_CONTROL` needs |
| --- | --- | --- |
| `ConversationService.admitTurn` (fast-fail before quota) | `AWAITING_HUMAN` → `ConversationAwaitingApprovalException` (REST 409) | **Must.** New branch *before* agent resolution and quota: refuse with `ConversationUnderHumanControlException` unless the caller used the parking entry (§5.2). Parking needs no deployed agent and burns no API-call quota slot (open Q6). |
| `ConversationService.startConversation` (init-turn pause bookkeeping) | Arms HITL timeout when `CONVERSATION_START` paused | Handoff on turn 0 must work too: same end-of-turn hook, plus inbox counter/event. |
| `ConversationService.endConversation` | Ending an `AWAITING_HUMAN` conversation audits a `hitl.approval` cancel and fires `HitlResumeCompletedEvent` | **Must.** Ending a `HUMAN_CONTROL` conversation writes `operator.release` (`reason: ENDED`, actor), clears `humanControl`, disarms its timers, fires the release event. |
| `ConversationService.undo` / `redo` (`loadedStateForUndo == AWAITING_HUMAN \|\| IN_PROGRESS`) | 409 | **Must** add `HUMAN_CONTROL` — undo would delete a parked user message or an operator's reply with no audit. |
| `ConversationService.rerun` | Goes through `admitTurn` | Refused by the new fast-fail (a rerun would run the pipeline). |
| `ConversationService.completeTurn` (`AWAITING_HUMAN` → deferred delivery until the pause commits) | Durable-before-told ordering for pauses | **Must.** A turn that *enters* `HUMAN_CONTROL` gets the same `DeferredDelivery`: the client is told only after the state is committed, so a follow-up message cannot race a not-yet-durable handoff. |
| `ConversationService.resolveConversationAgent` (version following skipped while paused) | — | Skip while `HUMAN_CONTROL` too; after release the next turn may follow the version as usual. |
| `ConversationStepRunner` (persisted-state checks, `committedStateOrError`, interrupt CAS `READY\|IN_PROGRESS → EXECUTION_INTERRUPTED`) | Skip/answer logic per state | Add `HUMAN_CONTROL` to the "skip, do not run" set; the interrupt CAS must never move `HUMAN_CONTROL` to `EXECUTION_INTERRUPTED` (it is not a running turn). |
| `Conversation.runStep` finally (`IN_PROGRESS → READY`) and `Conversation.resume` finally | End-of-turn state | **The entry hook** (§4.1): after `checkActionsForConversationEnd()`, a step carrying `HANDOFF_TO_HUMAN` ends `HUMAN_CONTROL` instead of `READY`. `ENDED` wins over handoff; a pause wins and the handoff applies when the resumed turn finishes. |
| `Conversation.checkIfConversationInProgress` | Throws for `IN_PROGRESS`, `AWAITING_HUMAN` | Add `HUMAN_CONTROL` (backstop; admitTurn should never let it through). |
| `Conversation.priorTurnPersistedLongTermProperties` (`null\|READY\|ENDED`) | Long-term baseline | Add `HUMAN_CONTROL`: the turn that handed off completed cleanly and persisted its long-term writes. |
| `Conversation.clearStepScopedPropertiesUnlessPaused` | Keeps step props across a pause | Clear normally — handoff is a completed turn. |
| `ConversationMemoryStore.NON_APPENDABLE_STATES`, `PostgresConversationMemoryStore.NON_APPENDABLE_STATES` | `ENDED, AWAITING_HUMAN, IN_PROGRESS` | **Must** add `HUMAN_CONTROL` in **both** stores, or a say turn that loaded before a takeover committed appends over it and the conversation silently returns to `READY`. |
| `ConversationMemoryStore.getActiveConversationCount` (`nin(ENDED, AWAITING_HUMAN)`) | Paused conversations do not block undeploy | Add `HUMAN_CONTROL` — no pipeline runs, so it must not block undeploy forever; release works with the agent undeployed (next say then 404s as today). |
| `ConversationMemoryStore.endIdleConversations` (bulk, `eq(READY)`) | Allow-list | Safe by default. |
| `AgentDeploymentManagement.endIdleConversationsOf` (#990, spares only `AWAITING_HUMAN`) | Deny-list | **Must** spare `HUMAN_CONTROL` too (and log it) — a raw `compareAndSetState(…, ENDED)` would end a live desk conversation with no `operator.release` audit. An unclaimed-timeout policy (§5.5) is the audited way to reap. |
| `TurnIdempotencyService.isReplayable` (#1000, `READY\|ENDED\|AWAITING_HUMAN`) | Which settled turns replay | **Must** add `HUMAN_CONTROL`; the parked step records `Idempotency-Key` like any step, so a retried user message is not parked twice. |
| `RestAgentEngine.resetState` (admin `PATCH …/state`) | Any non-`IN_PROGRESS`/`ENDED` → `READY` | **Must** refuse `HUMAN_CONTROL` with 409 naming `…/takeover/release` — otherwise an admin bypasses the release audit. |
| `RestAgentEngine.cancelConversation` → `ConversationHitlService` cancel CAS (`AWAITING_HUMAN → EXECUTION_INTERRUPTED`) | Cancels a pause or running turn | 409 for `HUMAN_CONTROL` (nothing is running; use release or end). |
| `RestAgentEngine` say `onSkipped` / `RestAgentEngineStreaming.buildKnownConditionOrOpaqueErrorEvent` | 409 text / SSE `error` with `code: awaiting_approval` | New `code: human_control` for refused system calls; the parked path is a normal answer (§6.1). |
| `ConversationHitlService` (resume CAS `AWAITING_HUMAN → IN_PROGRESS`, cancel, pending list) | HITL only | Unchanged; takeover is a sibling service (`ConversationTakeoverService`), not more branches in this 1,300-line class. |
| `HitlCrashRecoveryObserver` (reaps `IN_PROGRESS` at startup) | Crash recovery | No change for *state*: `HUMAN_CONTROL` is durable, not a crashed turn, and each takeover write is one atomic store write. Audit completeness is a separate concern: the ledger write is asynchronous and a crash can lose it after the state committed — see §7.1 (journal + reconciliation). |
| `HitlTimeoutHandler.regularPauseStillWaiting` | Timeout fires only while paused | Template for the takeover timers (§5.5), not a change. |
| `ScheduleFireExecutor` (`ROLLOVER_STATES` allow-list; `ConversationAwaitingApprovalException` → `SKIPPED`) | Paused heartbeats are skipped, not failed | **Must** map the new refusal to `SKIPPED` the same way — otherwise a persistent heartbeat on a desk conversation dead-letters (the regression `SKIPPED` was introduced to prevent). |
| `RestConversationStore` delete / soft-delete / bulk end (`== AWAITING_HUMAN` → `endConversation`) | HITL cleanup before delete | Route `HUMAN_CONTROL` through `endConversation` too, so release is audited before the document goes. |
| `ConversationMemoryUtilities` (ERROR output rendering) | — | No change. |
| `ClusterAdminService` conversation diagnosis (`AWAITING_HUMAN` → `OPEN_APPROVALS` finding) | Admin finding | Add a `HUMAN_CONTROL` / `TAKEN_OVER` finding (assignee shown to admins). |

### 2.2 Cluster mode (#956) — leases and fencing

[`IConversationLeaseManager`](../src/main/java/ai/labs/eddi/engine/cluster/lease/IConversationLeaseManager.java)
gives one node a conversation for **one turn**; its revision is a fencing token the store
enforces (`_fence`, `ConversationFencedException`). Interaction with `HUMAN_CONTROL`:

- `HUMAN_CONTROL` is **document state**, never a held lease. Holding a KV lease for the
  minutes or hours a human takes would starve the 45 s acquire timeout and die with the
  node. The assignee lives in the document (`humanControl.assignee`).
- Takeover, operator message and release are **coordinator tasks**
  (`IEventBus.submitInOrder`), implemented as `ILeaseAwareTask`, so in cluster mode they
  wait behind a running turn, acquire the lease, and write with its fence — the same
  guarantees a turn has. A bare `compareAndSetState(READY → HUMAN_CONTROL)` is *not*
  enough: a say turn does not persist `IN_PROGRESS`, so the CAS could succeed while a turn
  runs on another node, and that turn's append would then be refused (correctly, by
  `NON_APPENDABLE_STATES`) — losing the user's message.
- Claiming is a revision-guarded write of `humanControl.assignee` inside that task;
  two desks racing get one 200 and one 409.
- State changes go through `cacheConversationState`, which already rides the
  `ClusterEvent.CONVERSATION_STATE` eviction, so every node's state cache sees the
  takeover.
- `ClusterConversationCoordinator` drain/dead-letter: a parked say is a tiny write; if it
  dead-letters, the classifier treats it like a turn (no change needed, but covered by a
  test).

### 2.3 Integrations and clients

| Location | Today | `HUMAN_CONTROL` needs |
| --- | --- | --- |
| [`OpenAiConversationBridge`](../src/main/java/ai/labs/eddi/integrations/openai/OpenAiConversationBridge.java) `ROLLOVER_STATES` | Allow-list (`READY, ERROR, EXECUTION_INTERRUPTED`) | Safe — a desk conversation is never rolled over. |
| … `render` / `onSkipped` / `asApiException` (`AWAITING_HUMAN` → pause notice) | Paused turn = assistant text + notice | **Must**: parked turn renders catch-up operator messages + notice (§6.2). |
| … `isUsable` (`state != ENDED`) | Deny-list | Correct as is (a desk conversation stays mapped). |
| … stateless turns (`endQuietly` after every request) | Conversation ends after each turn | **Must**: a stateless `/v1` turn that hands off would be ended immediately. Ignore the handoff there (log + metric) — open Q4. |
| [`SlackEventHandler`](../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java) `sendAndDeliver` / `sendAndWait` `onSkipped` | `AWAITING_HUMAN` → still-awaiting notice; others → not-active | Park the thread message; post an "a person will reply here" notice once; operator messages are pushed into the thread (§6.3). |
| `SlackInteractivityHandler`, `SlackHitlResumeObserver` | HITL buttons/continuation | Unchanged (phase 5 may add a "Take over" button). |
| [`A2ATaskHandler`](../src/main/java/ai/labs/eddi/engine/a2a/A2ATaskHandler.java) `settleSkipped`, `outcomeOf` and the task-rebuild switch | `switch` with **`default -> completed`** | **Must**: `HUMAN_CONTROL` falls into `default` and reports an empty *completed* task. Map to `working` (§6.4). |
| `McpConversationTools` (`talk_to_agent`, managed conversations; `skippedResultJson`) | `AWAITING_HUMAN` pause result; others BUSY/NOT_ACTIVE | New `status: HANDED_TO_HUMAN` result. |
| `McpHitlTools` | HITL only | Unchanged; takeover MCP tools are phase 5. |
| `ConverseWithAgentTool`, `CreateSubAgentTool` | Sub-agent `AWAITING_HUMAN` → tool result "awaiting approval" | Sub-agent in `HUMAN_CONTROL` → refused, tool result "handed to a human" (no parking — the caller is an LLM). |
| [`MemberTurnExecutor`](../src/main/java/ai/labs/eddi/engine/internal/groups/MemberTurnExecutor.java) (member `AWAITING_HUMAN` → `SKIPPED` + auto-cancel `system:group`) | Member HITL unsupported in groups | Same treatment: a member turn ending `HUMAN_CONTROL` is recorded `SKIPPED` and auto-released (`operator.release`, actor `system:group`); takeover API refuses group member conversations (409). |
| `GroupConversationService`, `GroupHitlCoordinator`, `TeamCadenceService` | `GroupConversationState` (separate enum) | Unaffected. |
| `ui/chat` — `types.ts` union, `sse-events.ts` (`SKIP_STATES`, `isSkippedTurn`, `isTurnPaused`, `skippedTurnMessage`), `hitl-api.ts` `SETTLED_STATES`, `useHitlPolling`, `ChatWidget` | State union and turn classification; "no push channel on the 1:1 surface" (`chat-store.tsx`) | Extend the union; `done` with `HUMAN_CONTROL` and zero tokens is an *accepted* turn (today `isSkippedTurn` returns false → "no output" rendering); a takeover poll hook modelled on `useHitlPolling`; banner "You are talking to a person". |
| `ui/manager` — `lib/api/conversations.ts` union; `pages/conversations.tsx`, `conversation-detail.tsx`, `conversation-monitoring.tsx` state badge `Record`s; `hooks/use-chat.ts` and `use-operator-chat.ts` `RESUMABLE_STATES`; `lib/cluster-labels.ts`; `components/operator/operator-history.tsx` switches; `lib/operator/system-prompt.ts` | Typed records (compile-time exhaustive) and string switches | Add the state everywhere (TS `Record<ConversationState,…>` catches most); decide in `system-prompt.ts` that the Platform Operator treats `HUMAN_CONTROL` as "not yours, do not talk into it"; bump `operator-revision.json`. |

---

## 3. State model: new `HUMAN_CONTROL`, not `AWAITING_HUMAN`

Reusing `AWAITING_HUMAN` (with a `pauseType: TAKEOVER`) looks cheaper and is not:

1. **Input semantics are opposite.** Every `AWAITING_HUMAN` site — `admitTurn`, the REST
   say `onSkipped`, `/v1`, Slack, A2A, MCP, schedules, chat UI `SKIP_STATES` — implements
   "refuse the user's message without consuming it". Takeover must *accept and store* it.
   Reuse means adding a pause-type sub-branch to each of those ~20 sites; a missed one
   drops customer messages silently. A new value makes every deny-list site visible in a
   review. Only `switch` *expressions* without `default` become compile errors; a `switch`
   *statement* over the enum silently skips the new value, so statements stay in the
   inventory and get explicit tests like any `if`.
2. **Resolution semantics are opposite.** A pause resolves by **re-entering the pipeline**
   at a bookmark (`Conversation.resume`, `hitlPausedAbsoluteTaskIndex`); takeover resolves
   by **returning to idle** with no pipeline run. The resume CAS
   (`AWAITING_HUMAN → IN_PROGRESS`), crash recovery, timeout handler, approval-status,
   `pending-approvals`, `pauseId` binding and the Slack approval cards all assume the
   former — a takeover leaking into any of them is either a wrong resume or a phantom
   approval.
3. **Authorization differs.** HITL admits the **owner** (`HitlAccessGuard`: owner, admin,
   approver). For takeover the owner is the customer and must never act as the operator
   (§9). A shared state invites a shared guard.
4. **Listings and metrics.** `pending-approvals` counts and EU AI Act oversight
   reporting (`hitl.approval`) would mix governance gates with customer-service handoffs.

Cost of the new value, honestly: the Must rows in §2 (≈20 backend sites, ≈12 UI sites),
both store implementations, and the API contract (`GET /agents/{id}/status` returns a
value old clients do not know — the Manager and Chat UI are in-repo and ship together;
external clients get it only on agents that opt in, §4.1).

**`humanControl` record** (new field on the memory snapshot, persisted with it, `null`
outside the state):

```json
{
  "controlId": "3f2b…",               // random; binds release/messages like HITL pauseId
  "requestedAt": "2026-10-15T09:12:03Z",
  "origin": "ACTION | TOOL | OPERATOR",
  "reason": "Customer asked for a refund over the limit",   // ≤ 500 chars, from config/tool/operator
  "assignee": "alice@example.com",     // null ⇒ waiting in the inbox
  "claimedAt": "2026-10-15T09:13:40Z",
  "lastActivityAt": "…",
  "pendingUserMessages": 2             // parked inputs since the last operator message
}
```

`controlId` plays the role of HITL's `pauseId`: a release or message naming a `controlId`
that is no longer current gets 409, so a stale console tab cannot act on the next takeover.

---

## 4. Entry points

### 4.1 Reserved action `HANDOFF_TO_HUMAN`

- Constant on [`IConversation`](../src/main/java/ai/labs/eddi/engine/lifecycle/IConversation.java)
  beside `PAUSE_CONVERSATION`; add to `ReservedActionLint.RESERVED_ACTIONS` (near-miss
  lint at ruleset save, `RuleSetStore`) and to the reserved list in
  [`agent-config-authoring.md`](../docs/agent-config-authoring.md) and `AGENTS.md` §5.3.
- **Transition at end of turn, not mid-pipeline.** Unlike `PAUSE_CONVERSATION`
  (detected by `LifecycleManager.checkIfPauseConversationAction` after the emitting task),
  the handoff lets the rest of the pipeline run so the output task can say "I'm connecting
  you to a colleague". `Conversation.runStep`'s finally (and `resume`'s) checks the step's
  actions after `checkActionsForConversationEnd()`. Precedence: `ENDED` > pause > handoff.
- **Opt-in per agent**: honoured only when the agent has a `takeoverConfig`
  (`AgentConfiguration`, absent ⇒ off — the "opt-in by absence" pattern of
  `artifactConfig`). Without it the action is ignored with a WARN and a deploy-time lint
  (like `ReservedActionLint.checkInertHitlConfig`), because handing a customer to a desk
  nobody watches strands them. Proposed shape:

```json
"takeoverConfig": {
  "reason": "Escalated to support",
  "userNotice": "A member of our team will reply here shortly.",
  "unclaimedTimeout": "PT10M",  "unclaimedPolicy": "RELEASE | END | WAIT_INDEFINITELY",
  "idleTimeout": "PT30M",       "idlePolicy": "RELEASE | END | WAIT_INDEFINITELY",
  "historyRendering": "ANNOTATED | PLAIN",
  "annotationPrefix": "[Human agent] ",
  "patchableProperties": ["orderId", "refundApproved"],
  "operatorVisibleProperties": ["orderId", "customerTier"]
}
```

Defaults: `WAIT_INDEFINITELY` for both policies (no timer armed), `ANNOTATED`, empty
allow-lists. Save-time validation mirrors `hitlConfig` (finite policy needs a valid
duration; 400 otherwise).

### 4.2 Opt-in built-in tool `request_human_agent`

- `@Tool("Hand this conversation to a human support agent…") String requestHumanAgent(@P("why") String reason)`.
  It does **not** change state: it adds `HANDOFF_TO_HUMAN` to the current step's actions
  and records the (capped, redacted) reason, so tool and rule converge on §4.1's single
  end-of-turn transition, and commit-flag rollback of a failed task removes the request.
- **Trap — must be excluded from the "no whitelist ⇒ everything" default.**
  [`BuiltinToolsProvider`](../src/main/java/ai/labs/eddi/modules/llm/impl/BuiltinToolsProvider.java)
  `CatalogEntry.selectedBy` returns true for every entry when `builtInToolsWhitelist` is
  empty; adding the tool to the catalog unchanged would hand it to every agent with
  `enableBuiltInTools: true`. Give the entry an `explicitOnly` flag: selected only when
  whitelisted **and** `takeoverConfig` is present.
- Once per turn (second call returns "already requested"); rate-limited through
  `ToolExecutionService` like every tool.

### 4.3 Operator API

| Method | Path | Body / effect | Success | Errors |
| --- | --- | --- | --- | --- |
| `POST` | `/agents/{conversationId}/takeover` | `{ "reason"?, "force"? }` — enter `HUMAN_CONTROL` from `READY`/`ERROR`/`EXECUTION_INTERRUPTED` and claim, or claim an unassigned one. `force` (admin only) reassigns a claimed one. | `200` + takeover status | `409` `IN_PROGRESS` (`Retry-After: 2`), `AWAITING_HUMAN` (decide the approval first), `ENDED`, claimed by someone else, group member conversation; `404` |
| `GET` | `/agents/{conversationId}/takeover` | Status: state, `controlId`, `origin`, `reason`, `claimed` (bool — assignee identity only for takeover role/admin), timers, `pendingUserMessages` | `200` | `404` |
| `POST` | `/agents/{conversationId}/takeover/messages` | `{ "controlId", "text", "displayName"?, "clientMessageId"? }` (text ≤ `eddi.conversations.max-input-chars`) | `201` + step index | `409` not in control / not the assignee / stale `controlId`; `413` |
| `POST` | `/agents/{conversationId}/takeover/release` | `{ "controlId", "note"?, "propertyPatch"?, "end"? }` — back to `READY` (or `ENDED` when `end: true`) | `200` | `400` invalid patch (state untouched); `409` as above |
| `GET` | `/agents/pending-takeovers?limit=200&claimed=false` | Inbox, oldest first, bounded (max 1000) like `pending-approvals` | `200` | — |

Consistent with `IRestAgentEngine`: the `/agents/{conversationId}/…` prefix, literal
segments beside `{conversationId}` as `pending-approvals` already does, JSON errors with
`text/plain` 409 bodies as the HITL endpoints. These are new v6 paths, so
`DocumentedRestPathsTest` (legacy store spellings banned in prose) is unaffected; document
them in [`rest-api-reference.md`](../docs/rest-api-reference.md) and a new
`docs/human-takeover.md`.

---

## 5. While in `HUMAN_CONTROL`

### 5.1 Data model — one step per message

- **User message** → a new step with `input` (and attachments/context as usual), actions
  `[HUMAN_TURN_PENDING]` (non-reserved marker), no pipeline, no output. Written through
  the normal append path (it is an append), with `Idempotency-Key` recorded.
- **Operator message** → a new step with no input and one output item:
  `{"type":"text","text":"…","origin":"operator","displayName":"Alice"?,"messageId":"…"}`.
  Private step data `operator:sender` = principal. The principal (often an e-mail) is
  **not** placed in the user-visible output: `displayName` is what the operator chose to
  show, absent ⇒ clients render a generic "Support".
- Rejected alternative: appending operator outputs to the last user step. It turns
  appends into history rewrites (`_histRev` bump), breaks the per-step timestamp and the
  A2A task-to-step binding, and makes `clientMessageId` dedupe awkward.
- Long-term and step-scoped properties are untouched while in control; no templating, no
  output templates run on operator text (it is literal — no Qute evaluation of `{…}` in
  human text).

### 5.2 `say` while in control

`ConversationService.say` stays as is for every existing caller and **refuses**
(`ConversationUnderHumanControlException`) — schedules, group members, sub-agent tools,
triggers. User-facing surfaces (REST/SSE say, `/v1`, Slack, A2A) call a new
`IConversationService.sayToHuman(conversationId, inputData, handler)` when they get that
refusal (or the say path accepts an `allowParking` flag — implementation choice). Refuse
by default is the fail-safe: a forgotten caller produces a visible 409, never junk
messages in a customer's thread.

The parked turn: coordinator task → GDPR Art. 18 check, input cap, idempotency, append
step, bump `pendingUserMessages`/`lastActivityAt`, fire `HumanTurnPendingEvent`, answer the
client with the snapshot (state `HUMAN_CONTROL`). Quota: not charged (no model call) —
open Q6.

### 5.3 Operator messages

Coordinator task: verify state + `controlId` + assignee (or admin), dedupe
`clientMessageId` (recorded in the current `controlId`'s journal, §7.1, so a retry after a
restart or a long delay is still recognised — not only within the last N steps), append the operator step, reset
`pendingUserMessages`, fire `OperatorMessageEvent` (§12), write `operator.message` audit.

### 5.4 Release

Coordinator task: verify, apply the optional property patch (same validator as §10,
against `takeoverConfig.patchableProperties`), set conversation-scoped property
`humanTakeover` = `RELEASED` (and `humanTakeoverNote` when given) so the next turn's
behavior rules can branch on `{properties.humanTakeover}`, clear `humanControl`, disarm
timers, `READY` (or `ENDED` via `endConversation` when `end: true`), audit, fire
`TakeoverReleasedEvent`. No pipeline turn runs on release — the agent speaks again on the
user's next message. (A "run a turn on release" option is open Q5.)

### 5.5 Timers

`unclaimedTimeout` / `idleTimeout` use the one-shot schedule mechanism HITL timeouts
already use (`ConversationHitlService.scheduleHitlTimeout` / `HitlTimeoutHandler`), with a
"still in the same `controlId`" check before acting. A timer-driven release is audited
with actor `system:timeout`. Optional deployment-wide retention
`eddi.takeover.max-age` mirrors `eddi.hitl.pending.max-age`.

---

## 6. How each client behaves

| Client | User sends while in control | Operator replies | Notes |
| --- | --- | --- | --- |
| **REST say** `POST /agents/{conversationId}` | `200` with the snapshot, `conversationState: HUMAN_CONTROL`, no output | Client polls `GET /agents/{conversationId}/takeover` (cheap) then the conversation | No push channel exists on 1:1 today. |
| **SSE** `POST /agents/{conversationId}/stream` | Events `human_control` (`{controlId, claimed, userNotice?}`) then `done` (snapshot), stream closes | As REST | `human_turn_pending` is an **operator-side** event (§12), not sent to the user. |
| **Chat UI** | Renders the user bubble as sent, banner "You're talking to a person", composer stays enabled | Poll every 3 s while `HUMAN_CONTROL` (pattern of `useHitlPolling`), back off when hidden; render operator items with a person badge | `isSkippedTurn`/`isTurnPaused` must classify the state explicitly. |
| **`/v1` non-streaming** | `200`, assistant `content` = operator messages not yet delivered to this chat (catch-up) followed by the notice; `finish_reason: "stop"`. Optional bounded wait (`eddi.openai.takeover-wait`, default `PT0S`) for the first operator reply | Delivered with the next request | A request/response protocol cannot receive pushes; catch-up is the honest answer. Cursor = last step index rendered, stored on the chat mapping. |
| **`/v1` streaming** | Same text as one `delta` then `stop` | As above | Stateless chats ignore handoff (§2.3). |
| **Slack** | Message parked; one-time notice in the thread | **Pushed** into the thread by an observer of `OperatorMessageEvent` (resolves the thread from the persistent mapping, like `SlackHitlResumeObserver`) | Real-time for free. |
| **A2A** | Task settles `working` with status text "handed to a human operator" | `tasks/get` rebuilds: the task's step is followed by operator steps ⇒ `completed` with the operator text(s) up to the next user step | Avoids `default -> completed` with empty text. Whether A2A conversations may hand off at all is open Q4. |
| **MCP `talk_to_agent`** | Result `status: HANDED_TO_HUMAN` with `userNotice` | `read_conversation` shows operator steps | — |
| **Group member** | n/a — refused; member turn `SKIPPED`, auto-release `system:group`, `member_handoff_skipped` group SSE event | n/a | Takeover API: 409 for member conversations (detected via the `groupConversationId` context the member conversation carries). |
| **Sub-agent tools / schedules / triggers** | Refused → tool result "handed to a human" / schedule `SKIPPED` | n/a | — |

---

## 7. Audit

Written through `AuditLedgerService.submit` exactly like `ConversationHitlService.auditHitlDecision`:

| `taskId` (type `operator`) | `detail` |
| --- | --- |
| `operator.takeover` | `event: OPERATOR_TAKEOVER`, `origin` (ACTION/TOOL/OPERATOR), `controlId`, `actor`, `reason`, `forced` (reassign), `previousAssignee` |
| `operator.message` | `event: OPERATOR_MESSAGE`, `controlId`, `actor`, `messageId`, `textSha256`, `length` |
| `operator.release` | `event: OPERATOR_RELEASE`, `controlId`, `actor`, `automated`, `reason` (`RELEASED`/`ENDED`/`TIMEOUT`/`GROUP`/`DELETED`), `note`, `propertyPatch` summary (§10) |

- **Actor is set server-side**: from `SecurityIdentity` like `RestAgentEngine.resumeConversation`
  (`decision.setDecidedBy(userId)` — any body value is ignored), `slack:<userId>` from
  Slack, `system:*` for timers/group/admin-delete. Request bodies have no actor field.
- **Message text is not copied into the ledger** — digest + length only. The text lives in
  conversation memory, which GDPR erasure can reach. The ledger records the actor, the
  event, the time and a content commitment (the digest); it does **not** preserve what was
  said. While the conversation exists, the digest proves the stored text is the text that
  was sent; once the conversation is erased the message is gone, and the ledger shows only
  that an attributed message of that length existed. That is the intended trade-off — the
  immutable ledger must not become a second, unerasable copy of customer dialogue. A
  deployment that must retain message text has to keep it elsewhere under its own
  retention policy (open Q10).
- `origin: ACTION/TOOL` entries carry `actor: agent:<agentId>`; the turn's own task
  entries already show which rule or tool requested it.

### 7.1 Ordering and recovery: the audit write is not atomic with the state write

[`AuditLedgerService`](../src/main/java/ai/labs/eddi/engine/audit/AuditLedgerService.java)
is an asynchronous batch writer: `submit` scrubs, sequences, hashes and signs the entry and
queues it in memory; a scheduled flush (`eddi.audit.flush-interval-seconds`, default 3 s)
persists it, and a failed store write dead-letters to `eddi.audit.dead-letter-path`. No
transaction spans the conversation store and the audit store, and there is no outbox. A
process death after a takeover write committed and before the next flush therefore loses
the `operator.*` entry while the message or release stands. HITL has the same window today
(`auditHitlDecision` runs after the resume CAS commits), but an operator message is
customer-visible output with no pipeline task entry to corroborate it, so this design
closes the window rather than inheriting it:

1. **State first, then audit.** The ledger never records a write that did not commit. A
   takeover write that fails (CAS lost, fenced, store error) submits nothing.
2. **The conversation write carries its own audit record — the document is the outbox.**
   Each takeover write appends `{auditId, event, actor, at, controlId, textSha256?,
   length?}` to `humanControl.journal` in the *same* atomic store write as the state change
   or operator step. `auditId` is deterministic (`<conversationId>:<controlId>:<seq>`), not
   a fresh UUID.
3. **Then submit** the ledger entry under that `auditId`, best-effort as today.
4. **Reconcile.** A reconciler (a cluster-leader job like the other sweeps, plus once at
   startup) finds journal records older than flush interval + margin, looks each `auditId`
   up in the ledger and submits only the missing ones (flagged `reconciled: true`, original
   `at` kept). It must look before it submits: the ledger is hash-chained per conversation
   and `/auditstore/verify` grades a duplicate as broken, so blind replay is not
   idempotent. Records found in the ledger are pruned. The journal is bounded: a release
   cannot complete while more than N records are unreconciled — it reconciles first.
5. **Ending or erasing** a conversation reconciles its journal before the document goes
   (`endConversation`, delete, GDPR erasure), so the last records are not lost with it.

Tests: a ledger stub that drops its queue simulates a kill between commit and flush; the
reconciler then restores exactly the missing entries, `/auditstore/verify` stays clean, and
a second pass submits nothing.

---

## 8. LLM history after release

[`ConversationHistoryBuilder.generateMessagesFromOutputs`](../src/main/java/ai/labs/eddi/modules/llm/impl/ConversationHistoryBuilder.java)
maps each step's `input` to a `UserMessage` and its output text to an `AiMessage`;
[`ConversationLogGenerator`](../src/main/java/ai/labs/eddi/engine/memory/ConversationLogGenerator.java)
does the same for `{conversationLog}`, the rolling summary and the recall tool. With one
step per message, a takeover produces `user, user, ai(operator), user, ai(operator), ai(operator)`.

**Recommendation: `ANNOTATED` default, `PLAIN` selectable.**

- `PLAIN` lets the model believe it said things it did not ("I've issued your refund") and
  then claim capabilities it does not have — the same reason fallback text is already
  *removed* from history (`ConversationOutputExtractor.withoutFallbackItems`). The
  precedent in this codebase is "the model must not learn from text it never wrote".
- `ANNOTATED` keeps the content (the agent must know what the human promised) and the
  attribution: operator items render as `AiMessage("[Human agent] …")` with the prefix from
  `takeoverConfig.annotationPrefix`. Risk: the model imitating the prefix; mitigated by
  stating it once in the system prompt via a template flag
  (`{conversationInfo.humanTakeoverOccurred}`), which agent authors can use.
- **Must, either way:** merge consecutive same-role messages (generalise the existing
  `mergeNextUser` fallback logic) — Anthropic rejects non-alternating roles, and other
  providers accept them silently, so it only fails on some providers.
- Recognised by the `origin: "operator"` marker on output items (as `fallback: true` is),
  so `ConversationLogGenerator`, the summary and recall render identically.

---

## 9. RBAC

Facts: roles are literal, never inherited ([`security.md`](../docs/security.md) — "Roles
are checked literally"); `@RolesAllowed("eddi-viewer")` 403s an admin, so every endpoint
enumerates; REST and MCP share one tier matrix guarded by `McpRoleTierParityTest`. #942
made conversation access owner-or-admin (`ConversationAccessGuard.requireConversationOwner`),
and HITL owner-or-admin-or-approver (`HitlAccessGuard`, `OwnershipValidator.isApprover`).

**Recommendation: a dedicated role, working name `eddi-takeover`.**

- Endpoints: `@RolesAllowed({"eddi-admin", "eddi-takeover"})` on all §4.3 paths. Not
  `eddi-user`/`eddi-editor`, and a new `TakeoverAccessGuard` that does **not** admit the
  owner — the owner is the customer.
- **Why not `eddi-approver`:** its scope was deliberately narrowed to *deciding gates*
  ("the approver role exists to decide pending approvals, not as a universal read grant",
  [`hitl.md`](../docs/hitl.md)), with full-transcript reads only while paused. A desk
  operator reads and *writes* customer dialogue for long stretches. Merging them grants
  every approver customer-facing write access, and every helpdesk seat EU AI Act
  approval authority.
- **Read scope:** takeover-role callers read a conversation's dialogue only while it is
  `HUMAN_CONTROL` (or was released by them within `eddi.takeover.read-grace`, default
  `PT1H`, for wrap-up notes); properties only those in `operatorVisibleProperties` —
  never `longTerm` user memory wholesale, never `secret` scope. Outside that, 403.
- Assignment: only the assignee (or an admin) may message/release; `force` reassign is
  admin-only and audited with `previousAssignee`.
- **Blast radius.** As specified, any takeover-role identity can claim any `HUMAN_CONTROL`
  conversation on any agent. A deployment serving several customers needs narrower desks:
  an optional `takeoverConfig.desks` list (role or group names) restricts which
  takeover-role callers may see and claim that agent's conversations; absent ⇒ any
  takeover-role caller. Whether v1 needs it is open Q11.
- Authorization disabled (dev): `OwnershipValidator.isAdmin` already returns true —
  everything works, as for HITL.
- Name: `eddi-operator` was rejected because the Manager's "Operator" is the Platform
  Operator LLM; the role name is open Q2.

---

## 10. The separable item: property patch on a HITL decision

Ships alone, before any takeover code. Closes the "modify extracted slot properties"
gap with a small, audited change.

**API.** `HitlDecision` gains `propertyPatch`:

```json
POST /agents/{conversationId}/resume
{ "verdict": "APPROVED", "pauseId": "…",
  "propertyPatch": [ { "name": "refundAmount", "valueString": "40.00" },
                     { "name": "orderId", "valueString": "A-1234", "scope": "conversation" } ] }
```

**Rules** (validated *before* the resume CAS, like `toolDecisions`, so a 400 never
consumes the pause):

- Opt-in: names must be in `hitlConfig.patchableProperties` (absent/empty ⇒ any patch is
  `400` "this agent allows no property patches"). Pillar 2: the agent author decides what
  a reviewer may change.
- Scope `conversation` (default) or `step`; `longTerm` and `secret` refused (a reviewer
  must not write a user's cross-session memory or plaintext secrets).
- Reserved names refused: `hitlVerdict`, anything prefixed `hitl`, `humanTakeover*`.
- Value: `valueString` (≤ 4 KB, the note cap) or `valueObject` (JSON, ≤ 16 KB); ≤ 20 entries.
- Applied in `Conversation.resume` right where `hitlVerdict` is put, on **both** verdicts
  (a reviewer may correct a slot and reject), before the pipeline re-enters, so the
  resumed tasks and later turns see `{properties.name}`.
- Audit: the `hitl.approval` detail gains
  `propertyPatch: [{name, scope, valueSha256, previousValueSha256}]` — never raw values.
- Surfaces: REST `/resume`, MCP `resume_conversation` (optional arg), Manager approval
  dialog. Slack buttons: not in v1 (all-or-nothing, like tool amendments). Group approve:
  not in v1.
- Back-compat: absent field ⇒ identical behaviour; old stored `hitlConfig` without
  `patchableProperties` ⇒ patches refused.

**Size:** S–M. `HitlDecision`, `AgentConfiguration.HitlConfig`, validator, `Conversation.resume`,
`auditHitlDecision`, `McpHitlTools`, docs (`hitl.md`), Manager dialog, tests.

---

## 11. Decisions for the maintainer

1. **State:** new `HUMAN_CONTROL` (recommended, §3) vs. `AWAITING_HUMAN` + pause type.
2. **Role:** dedicated role (recommended) — name `eddi-takeover`, `eddi-desk`, or other? Or
   reuse `eddi-approver` (not recommended, §9)?
3. **Opt-in:** `HANDOFF_TO_HUMAN`/tool require `takeoverConfig` (recommended); operator
   `POST /takeover` allowed on **any** agent's conversation, or only on agents with
   `takeoverConfig`? (Recommendation: any — a human deciding is the opt-in — behind
   `eddi.takeover.enabled`, default `true`.)
4. **Channels:** may stateless `/v1` and A2A conversations hand off at all? (Recommendation:
   stateless `/v1` no; A2A yes with `working`.)
5. **Release:** return silently to `READY` (recommended), or optionally run a pipeline turn
   with a `HUMAN_RELEASED` action so the agent speaks first?
6. **Quota:** parked user messages free (recommended) or charged as API calls?
7. **History:** `ANNOTATED` default (recommended) vs. `PLAIN`.
8. **Ship §10 first** as its own PR (recommended)?
9. **Outbound events:** is pull (inbox + status polling) plus Slack push enough for v1, or
   should the signed outbound webhook (§12) be in scope?
10. **Message retention:** is "erased with the conversation; the ledger keeps a digest"
    (§7) acceptable, or must some deployments retain operator message text separately?
11. **Desks:** is per-agent desk scoping (§9, blast radius) needed in v1?

---

## 12. Events, Manager and external helpdesks

**What transport exists today** (verified):

- **SSE** — per *turn* only (`POST /agents/{conversationId}/stream`, group discussion
  streams, admin activity/log streams). There is no long-lived per-conversation stream.
- **NATS JetStream** — `IClusterEventBus` (`JetStreamEventBus`/`LocalEventBus`) carries
  `ClusterEvent`s whose contract is "evict or reconcile, never apply the payload"; plus the
  lease KV and `IClusterRpc`. Internal, not a customer integration surface.
- **CDI async events** — `HitlResumeCompletedEvent` (`Event.fireAsync`), consumed by the
  Slack observer. Node-local, best-effort.
- **Webhooks** — none outbound. WS5 is an *inbound* signed webhook channel.

**Design:**

- Engine fires CDI async events `TakeoverRequestedEvent`, `HumanTurnPendingEvent`,
  `OperatorMessageEvent`, `TakeoverReleasedEvent` (ids, state, `controlId`, no message text
  — observers read the conversation). Observers are best-effort, as for HITL.
- **Pull is the v1 contract** for helpdesks and the Manager: `GET /agents/pending-takeovers`
  plus `GET /agents/{conversationId}/takeover` — works identically in single-node and
  cluster mode because it reads the store.
- **Operator live view (phase 4):** `GET /agents/{conversationId}/takeover/events` (SSE,
  takeover role). Cluster-safe by design: the node holding the subscriber re-reads the
  store when it sees a new `ClusterEvent` type `conversation.updated` `{conversationId}`,
  published on every takeover write — consistent with the "reload, never apply" contract,
  so a missed event costs latency (the stream also polls every 10 s), never correctness.
- **Outbound helpdesk webhook (phase 5, optional):** a channel-integration type that
  subscribes to the four events and POSTs CloudEvents signed with the same HMAC scheme WS5
  verifies inbound. A helpdesk then replies through `…/takeover/messages`. Kept out of v1
  so the API settles first. Transport requirements are fixed now so the phase cannot skip
  them: delivery only through `SafeHttpClient` (destination validated by
  `UrlValidationUtils`; the JDK's certificate and hostname verification, never
  overridden); `https` only, `http` refused at save time unless an explicit development
  flag is set; **redirects refused** for signed deliveries (a 3xx is a failed delivery and
  is not followed, so the signature and body never reach a second host); a per-delivery id
  and timestamp inside the signed payload for receiver-side replay protection; bounded
  retries with backoff, then a dead letter. The webhook is fed from the store-backed journal
  (§7.1), not from the best-effort CDI events, so a missed event is redelivered, not lost.
- **Manager (phase 4):** the Approvals page becomes an inbox with *Approvals* and
  *Handoffs* tabs (same "limit reached" banner behaviour); conversation detail gets
  *Take over*, a composer, *Release* with the property-patch form (shared with §10's
  approval dialog). All strings in every locale (`npm run i18n:check` and the full vitest
  run catch plural categories).

---

## 13. Phased plan, tests, traps

### 13.1 PRs

| # | PR | Contents | Size |
| --- | --- | --- | --- |
| 0 | `docs(planning): human takeover design` | This document | S |
| 1 | `feat(hitl): property patch on a resume decision` | §10 end to end incl. Manager dialog + MCP arg | S–M |
| 2 | `feat(conversations): HUMAN_CONTROL state and takeover API` | Enum value; every **Must** row in §2.1/§2.2 (stores, undo/redo, reset, idle sweep, idempotency, active count, schedules, delete); `ConversationTakeoverService` (coordinator tasks, lease/fence); §4.3 REST; `TakeoverAccessGuard` + role; audit; `HANDOFF_TO_HUMAN` + lint + `takeoverConfig` (save-time validation); metrics `eddi.takeover.started{origin}`, `.active` gauge, `.claim_wait` timer, `.messages{direction}`, `.released{reason}`; docs | L |
| 3 | `feat(integrations): human control across channels` | `sayToHuman` + REST/SSE/`/v1`/Slack/A2A/MCP/group/sub-agent handling (§2.3, §6); `request_human_agent` tool; history rendering (§8); timers (§5.5) | L |
| 4 | `feat(ui): takeover in chat and manager` | Chat UI banner + poll; Manager inbox tab, console, release dialog; operator SSE endpoint + `conversation.updated` cluster event; Platform Operator prompt + revision bump | M–L |
| 5 | optional | Takeover MCP tools (`McpRoleTierParityTest`), Slack "Take over" button, outbound webhook | M |

PR 2 must contain **all** state-machine Must rows: shipping the enum value with half of
them is the failure mode this inventory exists to prevent.

### 13.2 Test plan

- **Exhaustiveness guard:** a unit test that scans `src/main/java` for
  `ConversationState.AWAITING_HUMAN` deny-list sites and fails when a file mentions
  `AWAITING_HUMAN` in a refusal/skip context without `HUMAN_CONTROL` (allow-list of
  reviewed exceptions in the test) — the same idea as the repo's other guard tests.
- **Store:** Mongo and Postgres — append refused over `HUMAN_CONTROL`
  (`NON_APPENDABLE_STATES`), active count excludes it, bulk idle end skips it (ITs; both
  stores).
- **Concurrency:** takeover queued behind a running turn (in-memory coordinator); claim race
  (two operators, one 409); stale `controlId` → 409; fenced write from a lost lease refused
  (`KvLeaseManager` with `InMemorySharedKv`).
- **Engine:** handoff via action at end of turn (output still rendered); precedence with
  `CONVERSATION_END` and `PAUSE_CONVERSATION`; handoff on `CONVERSATION_START`; parked say
  stores input and runs no task (`LifecycleManager` never invoked); idempotent parked say;
  undo/redo/reset/cancel refused; end/delete audits release; idle sweep spares; schedule
  `SKIPPED`; group member `SKIPPED` + auto-release; sub-agent refusal.
- **Clients:** `/v1` catch-up rendering and cursor; stateless ignore; Slack observer posts;
  A2A `working` → `completed`; SSE `human_control` event; chat UI and Manager vitest for
  the state union and polling.
- **History:** annotated/plain rendering; role alternation merge (assert on the message
  list passed to the model).
- **Security:** owner gets 403 on every takeover endpoint; `eddi-user`/`eddi-editor`/
  `eddi-approver` 403; admin 200; takeover role 403 on a non-`HUMAN_CONTROL` conversation
  read; actor in audit ignores any body value; property patch refuses `longTerm`/`secret`/
  reserved/non-allow-listed names without consuming the pause.
- **Mutation-check** each behavioural test (revert the fix, confirm red), per the repo's
  practice.

### 13.3 Traps

1. Deny-list sites (§2) — the `A2ATaskHandler` `default -> completed` and
   `endIdleConversationsOf` are the two silent ones.
2. `NON_APPENDABLE_STATES` exists **twice** (Mongo, Postgres).
3. `BuiltinToolsProvider`'s empty-whitelist-means-all (§4.2).
4. Naming: "operator" already means the Platform Operator LLM in the Manager.
5. `ConversationHistoryBuilder` role alternation — fails only on providers that enforce it.
6. Owner admitted by reflex because `HitlAccessGuard` admits owners.
7. Qute: operator text is literal; never run it through templating.
8. `/v1` stateless turns end the conversation right after a handoff.
9. Durable-before-told: a client told `HUMAN_CONTROL` must find it committed
   (`DeferredDelivery`, as for pauses).
10. PII: operator principal in user-visible output, message text in the immutable ledger.
