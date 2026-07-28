# Implementation Plan — Approval-Gated Write Capability for the Platform Operator

## 0. Premise

The HITL gate itself is complete and fires before execution (`AgentOrchestrator.java:1169-1253`; gated requests never reach `executeSingleToolCall`). Nothing in the gate needs changing. The blocker is **provisioning** plus **verification**: `setup-api` cannot install a gate, and `tool-scopes.ts` is a provisioning-time constant, not a runtime boundary — so "writes are gated" must be an *asserted, read-back fact*, not an assumption.

Two facts drive every decision below:

- `ToolApprovalGate.classify` (`ToolApprovalGate.java:35-37`) returns the whole batch as `allowed` when `requireApproval` is empty, and unmatched names fall through to `allowed` (`:70-72`). **The gate fails open on naming.** Therefore: `requireApproval: ["http:*"]` + an explicit `exempt` read list — never an enumerated write list.
- Tool names are `operationId`, falling back to `generateSlug(method, path)` (`McpApiToolBuilder.java:217-221`, `:314-317`), source `"http"` (`AgentOrchestrator.java:923`). The exempt list must be **derived from the fetched spec by the same rule**, not hand-written.

---

## 1. Backend contract gaps (must land before any Manager work)

All in `C:\dev\git\EDDI\...\src\main\java\ai\labs\eddi\engine\setup\`.

**B1 — `CreateApiAgentRequest.java:16-26`: no HITL field of any kind.**
Append **one trailing** record component:

```java
AgentConfiguration.HitlConfig hitlConfig
```

Must be last: `McpSetupTools.java:126` uses the positional constructor, and the existing comment at `:19-25` already establishes append-last as the convention. Reuse `AgentConfiguration.HitlConfig` (`AgentConfiguration.java:258`, `:854-909`) rather than a new DTO — Jackson deserializes it directly and there is no field drift to maintain.

**B2 — `AgentSetupService.createApiAgent` step 7 (`AgentSetupService.java:322-325`) builds a bare `AgentConfiguration`.**
Add one line after `:324`:

```java
agentConfig.setHitlConfig(request.hitlConfig());
```

Today `hitlConfig == null` for every setup-api agent, so `memory.getAgentToolApprovalsConfig()` is null and the gate is inert. Do **not** replicate into `setupAgent` (`:188-190`) in this PR — the operator only uses `setup-api`, and a smaller surface is easier to review.

**B3 — pre-flight validation (new).**
`AgentStore.create:48` already calls `HitlConfigValidation.validate(...)`, but it runs at step 7 — after 6 resources exist. A bad pattern currently surfaces as a wrapped `AgentSetupException` at `:349-351` and leaves orphaned apicalls/parser/behavior/llm/workflow documents. Add, next to the other required-param checks at `AgentSetupService.java:233-251`:

```java
try {
    HitlConfigValidation.validate(request.hitlConfig());
} catch (IllegalArgumentException e) {
    throw new AgentSetupException("Invalid hitlConfig: " + e.getMessage(), e);
}
```

**B4 — `McpSetupTools.java:126`:** pass `null` for the new component. Deliberately do **not** expose `hitlConfig` on the MCP `create_api_agent` tool — that tool is itself a privilege-escalation surface (§5) and should not gain reach.

**B5 — nothing else.** `AgentDeploymentManagement.lintInertHitlConfig` already WARNs on a gate that can never pause. Do not change `ConversationService.applyEffectiveToolTimeoutPolicy:2242-2247` (explicit `AUTO_APPROVE` honored verbatim) — changing backend semantics would silently alter existing agents. Refuse `AUTO_APPROVE` **Manager-side** instead (§4).

**Not fixable in this PR, must be designed around:**
- No `POST /agents/{id}/resume/stream`. `RestAgentEngine.java:358-359` passes a `null` handler and returns an empty body; continuation runs async. The client must re-read the conversation after a decision.
- The approver sees **tool arguments, not the HTTP request** (R1). Method/path/headers/body live in the `ApiCall` and are resolved at `AgentOrchestrator.java:2349`, after approval. Mitigated by display-side reconstruction (§3), not solved.

---

## 2. The curated write set

Five entries — four writes and one read prerequisite.

```ts
export const WRITE_ENDPOINTS: readonly string[] = [
  "PATCH /descriptorstore/descriptors/{id}",
  "POST /administration/{environment}/deploy/{agentId}",
  "POST /administration/{environment}/undeploy/{agentId}",
  "POST /schedulestore/schedules/{scheduleId}/disable",
] as const;
// plus, added to READ_ENDPOINTS:
//   "GET /schedulestore/schedules"
```

- **`PATCH /descriptorstore/descriptors/{id}`** — the only *partial* write in the config plane. No execution semantics, no egress, no persistence, trivially reversible. Worst case: confusing metadata. It is also the highest-frequency real request ("tidy this deployment").
- **`POST .../deploy/{agentId}`** — makes the operator operational rather than a search box, and is tightly bounded: it can only activate a config a **human already authored**. It cannot create behavior.
- **`POST .../undeploy/{agentId}`** — paired deliberately. Deploy without rollback is worse than useless in an incident. Availability-only blast radius, instantly reversible.
- **`POST /schedulestore/schedules/{scheduleId}/disable`** — the "stop the bleeding" verb for a runaway job burning LLM spend. Asymmetric by design: disable is bound, `enable`/`create`/`fire`/`retry` are not.
- **`GET /schedulestore/schedules`** — without it the operator cannot see what it would disable. Currently absent from `READ_ENDPOINTS`.

**Notable exclusions and why:**

| Excluded | Reason |
|---|---|
| `PUT /agentstore/agents/{id}` | The operator's own gate lives in that document. One approved write removes all subsequent gating. Also hits the degenerate-body problem: `parseOptions.setResolve(true)` not `setResolveFully` (`McpApiToolBuilder.java:169`) means a `$ref` body schema collapses to a single `{requestBody}` variable (`:333-336`) — the LLM must emit a whole `AgentConfiguration` blob with no partial-update semantics. |
| `POST /agentstore/agents`, `POST`/`PUT /llmstore/llms` | An LLM config carries a provider `baseUrl` and the system prompt: an update is an egress channel *plus* a prompt rewrite for every conversation of that agent. Approval is insufficient when the diff is a full config document. |
| `POST /schedulestore/schedules`, `/enable`, `/fire` | Creating a schedule is **attacker persistence**: a scheduled turn has no human present, so the approval prompt never appears. |
| Any `DELETE` | No undo exists in any of these stores. |
| `POST /backup/export/{agentId}` | Semantically a read, but the response lands in conversation memory and the LLM's context. Config exfiltration. |

Because `requireApproval` is `["http:*"]`, **anything later added to `WRITE_ENDPOINTS` is gated by default** — the failure mode of forgetting to update a pattern list is eliminated.

---

## 3. Pause UX

**Where.** Inline in the operator chat (`components/operator/operator-chat.tsx`), after the last message — the precedent is `discussion-transcript.tsx:579-599`, which already renders `ApprovalBanner` inside a live transcript. Not the approvals page: `pages/approvals.tsx:332-344` deliberately refuses to decide `TOOL_CALL` pauses and links out instead. That page remains the correct *someone else's queue* fallback and needs no change.

**Detecting the pause.** There is no SSE pause event on the 1:1 surface (`RestAgentEngineStreaming.java:66-138`). Two paths, both already proven in `use-chat.ts`:
1. `use-operator-chat.ts:218` currently does `if (event.type === "done") break;` and discards the payload. Parse it: `conversationState === "AWAITING_HUMAN"`, plus `hitlPauseType` and the names-only `hitlPendingToolCalls` (`ConversationMemoryUtilities.java:268-307`) which ride on the snapshot for free.
2. A send while paused returns 409 (`ConversationService.java:425-429` → `RestAgentEngine.java:232-234`). `use-operator-chat.ts:225-228` currently stringifies it into `error`; convert it to a pause (`use-chat.ts:532-555` pattern transfers directly).

**What must be shown to make approval meaningful.** Fetch `getApprovalStatus()` (`lib/api/hitl.ts:241-247`) — the summary view already carries `argumentsRedacted` (`RestAgentEngine.java:476`). Render, per call:

- tool name + `source` badge + `gateReason` (existing `ToolCallRow`, `approval-banner.tsx:577-697`);
- redacted arguments `<pre>` (existing);
- **the HTTP method and path** — reconstructed client-side by mapping `toolName → WRITE_ENDPOINTS` entry via the fetched spec's `operationId`. This is new and it is the single most important addition: without it the admin approves `patchDescriptor({name:"x"})` with no idea *what* is being patched or by which verb. Label it as reconstructed, because the body is still resolved server-side after approval (R2 TOCTOU is not closed by this).
- `pauseReason`, `timeoutPolicy`, `approvalTimeout` — take these from `ApprovalStatusSummary` (`hitl.ts:140-149`), **not** the conversation snapshot. `SimpleConversationMemorySnapshot.java:19-44` has no such fields, so `conversation-detail.tsx:248-251` passes `undefined` today and the countdown at `approval-banner.tsx:338-357` never renders for a 1:1 pause. Fix it in both places.

**Anti-rubber-stamp.** Add a `requireExplicitPerCall?: boolean` prop to `ApprovalBanner`, default `false` (no change for existing surfaces). When set, `submitToolDecision` (`approval-banner.tsx:162-197`) must not let an untoggled call inherit the top-level `APPROVED` verdict (`:176`); Approve stays disabled until every call has an explicit verdict, and the confirm dialog (`:256-263`) lists arguments, not just names. The operator chat sets it `true`. `pauseDetailsPending` already blocks Approve before details load (`:524`) — keep that.

**Reject.** `HitlDecision { verdict: "REJECTED", note }` — all-or-nothing, already enforced by the banner. Rejected calls receive a synthetic DENIED result and are never executed; the turn continues and the model narrates the refusal. `note` is required in the operator UI (it lands in the audit ledger; `decidedBy` is overwritten server-side from `SecurityIdentity`, `RestAgentEngine.java:356`, so it cannot be spoofed).

**After any decision.** `resumeConversation` returns an empty body and the continuation runs async (`ConversationService.java:1577`). `useResumeConversation.onSuccess` (`use-hitl.ts:79-84`) invalidates queries but never reloads messages — the manager chat panel silently never shows the continuation today. In `use-operator-chat.ts`, follow the mutation with a `readConversation` re-read and append the new turn. Do not add `resume/stream` in this PR.

---

## 4. Invariants to encode in tests

`isWriteScopeAvailable()` must **not** be flipped to `true`. It must stop taking an optimistic boolean and start taking verified facts:

```ts
export interface WriteScopePreconditions {
  /** setup-api accepted a hitlConfig — i.e. the backend is ≥ this PR. */
  backendSupportsHitlProvisioning: boolean;
  /** Read back from the agent store: EVERY version carries the gate. */
  gateVerified: boolean;
  /** Writes must be attributable to a real principal. */
  authMode: OperatorAuthMode;
  /** The chat surface that can actually decide a pause is mounted. */
  approvalSurfaceMounted: boolean;
}

export function isWriteScopeAvailable(p: WriteScopePreconditions): boolean {
  return (
    WRITE_ENDPOINTS.length > 0 &&
    p.backendSupportsHitlProvisioning &&
    p.gateVerified &&
    p.authMode === "caller-identity" &&
    p.approvalSurfaceMounted
  );
}
```

`authMode` is load-bearing: it defaults to `"none"` (`operator.ts:86`), and a write under `none` either 401s on every call or — with OIDC off — executes **unauthenticated with no audit identity**.

Tests, ordered by what they protect:

1. **The canonical one.** `provisionOperator({ scope: "read_write" })` must send a body whose `hitlConfig.toolApprovals.requireApproval` is non-empty and contains `"http:*"`. Assert on the captured request. *This test fails the moment writes become reachable without a gate.*
2. `isWriteScopeAvailable` returns `false` for each precondition falsified individually — four negative cases plus the empty-`WRITE_ENDPOINTS` case.
3. **Exempt cannot swallow a write.** For every entry in `WRITE_ENDPOINTS`, its resolved tool name (spec `operationId ?? generateSlug`) is **not** in the generated `exempt` list, and matches `http:*`.
4. **Exempt must cover every read.** Every `READ_ENDPOINTS` entry resolves to an exempted tool name. If it does not, reads start pausing and admins will disable the gate out of annoyance — an availability bug that becomes a security bug.
5. **Name-resolution mirror.** A TS port of `generateSlug` (`McpApiToolBuilder.java:314-317`) with cases pinned against the Java implementation, plus the assertion that every allow-listed endpoint exists in the fetched spec (extend the existing check in `tool-scopes.test.ts`).
6. `verifyGateInstalled()` rejects when: any agent version lacks `hitlConfig.toolApprovals`, `requireApproval` is empty, `timeoutPolicy === "AUTO_APPROVE"`, or any `exempt` entry matches a write tool name.
7. **Backend, `AgentSetupServiceTest`:** (a) `createApiAgent` with a `hitlConfig` → ArgumentCaptor on `IRestAgentStore.createAgent` shows it set; (b) with an invalid pattern → `AgentSetupException` **and** `verify(apiCallsStore, never()).createApiCalls(any())`, proving B3 runs before any resource is created. Use hex ids in fixtures — non-hex ids make `extractResourceId` return null and `anyString()` skips null, so `verify(never())` goes vacuous.
8. **Gate behaviour:** `http:*` gates a tool named `deployAgent` with source `http`; an exempt read is not gated; a tool whose source is unresolved still matches on the bare name.

**Runtime verification the tests cannot give you.** `eddi.hitl.tool.enabled=false` (`application.properties:118`) makes the gate inert deployment-wide and no API reports it. Add a **write canary** to activation, modelled on `runOperatorCanary` (`operator.ts:337`): prompt the operator to rename a descriptor, assert the turn ends `AWAITING_HUMAN` with the expected tool in `hitlPendingToolCalls`, then **REJECT** it (nothing executes). This single probe catches a disabled kill switch, wrong operationIds, and pattern typos. `gateVerified` is set only if it passes.

---

## 5. What I would NOT do

- **Not populate `WRITE_ENDPOINTS` beyond the four.** Specifically never bind, regardless of approval: `setup-api`/`setup` (one call provisions a *new* agent with an arbitrary `endpoints` filter and no gate — complete escape from the allow-list); `POST /agents/{id}/resume` (self-approval — `HitlAccessGuard` has no "not the requester" check); `PATCH /agents/{id}/state` and `/cancel` (clears `AWAITING_HUMAN` under the gate); `PUT /variablestore/variables/...` (the operator's own config blob lives at key `platform.operator`, `operator.ts:74`); all `/secretstore` writes; `/backup/import*`; `apicallstore`/`mcpcallsstore`/`channelstore` writes; `/ragstore/.../ingest`; `usermemorystore` writes; `AgentTriggerStore` writes; `/administration/quotas`; `DELETE /administration/orphans`.
- **Not upgrade an existing read-only operator in place.** Changing scope **re-provisions** a new agent (fresh `setup-api`, gate on v1) and resets the old one via `resetOperator`. An in-place `PUT` would leave an older, ungated version of the same agent that a bound `deployAgent` call could roll back to. This is why "every version carries the gate" is the read-back invariant rather than "the current version does".
- **Not change backend `AUTO_APPROVE` semantics.** Explicit `toolApprovals.timeoutPolicy: AUTO_APPROVE` is honored (`ConversationService.java:2242-2247`) and existing agents may rely on it. Refuse it Manager-side for the operator only.
- **Not enable Slack approvals for operator writes.** `SlackHitlSupport.java:69,75` truncates to 5 calls and 300 chars of arguments while keeping the same buttons — the realistic rubber-stamping surface.
- **Not add `POST /agents/{id}/resume/stream`.** Real gap (`ConversationService.resumeConversation` already accepts a handler; only the REST adapter passes `null`), but a separate backend PR. A post-decision re-read is adequate.
- **Not touch `pages/approvals.tsx`.** Its refusal to decide TOOL_CALL pauses is correct.
- **Not build "approve all".**
- **Not treat `tool-scopes.ts` as a security boundary.** It is applied at provisioning time only. Say so in the file comment.

---

## 6. Ordering — writes are never reachable ungated

`WRITE_ENDPOINTS` stays `[]` until step 5. Until then `endpointsForScope("read_write")` returns exactly the read set and `isWriteScopeAvailable` returns `false` on the length check alone.

1. **Backend PR (B1–B4)** + `AgentSetupServiceTest` cases. Merges independently; changes no runtime behaviour for any existing agent (`hitlConfig` stays null when unset). Ship and confirm green CI.
2. **Manager: gate construction + verification, still read-only.** Add `buildToolApprovals(spec, scope)` (derives `exempt` from `READ_ENDPOINTS` via `operationId ?? slug`), `verifyGateInstalled(agentId)`, the `generateSlug` mirror, and tests 3–6. `provisionOperator` starts sending `hitlConfig` **even for `read_only`** — a read-only operator with `requireApproval: ["http:*"]` and every read exempted is behaviourally identical, and it proves the whole pipeline end-to-end on a zero-risk configuration. `backendSupportsHitlProvisioning` is detected here.
3. **Manager: pause UX.** Pause state + 409 handling + `decide`/`cancel` + post-decision re-read in `use-operator-chat.ts`; `ApprovalBanner` rendered in `operator-chat.tsx` with `requireExplicitPerCall`; method/path reconstruction; input disabled while paused. Fix `conversation-detail.tsx:248-251` to source timeout fields from `useApprovalStatus`. Still no write endpoints — verifiable by temporarily un-exempting one read locally, never committed.
4. **Manager: write canary + `isWriteScopeAvailable` signature change.** All preconditions wired, all still resolving `false` because `WRITE_ENDPOINTS` is empty. Tests 1–2 land here and pass *because* the list is empty.
5. **Populate `WRITE_ENDPOINTS` (4 entries) + add `GET /schedulestore/schedules` to reads.** This is the only commit that grants a write, and by this point every test above is already guarding it: a write cannot resolve to an exempt name (test 3), provisioning cannot omit the gate (test 1), and activation cannot enable the scope without a passing canary read-back (test 6 + step 4).

Each step is a reviewable commit; steps 1 and 2–5 can be two PRs (backend, then Manager) since step 2 hard-depends on step 1 being deployed.
