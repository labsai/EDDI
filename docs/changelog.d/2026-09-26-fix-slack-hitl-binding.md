## 🔒 fix(hitl): bind a decision to its pause; scope the approver's identity to what they approved (2026-09-26)

**Repo:** EDDI (`fix/slack-hitl-binding`)

### What changed and why

Fixes the engine-side HITL findings of the 2026-09-25 review (H4b, H5, A2). The Slack findings of the same review (H4a, H4c, H4d, the Slack user-id collision) were fixed on `main` by `fix/security-channel-identity` (#861); see the reconciliation entry below for what this branch still adds on the Slack side.

- **H4b — a decision is bound to the pause it was made for.** New optional `HitlDecision.pauseId` (the pause start's epoch milliseconds, `HitlDecision.pauseIdOf`), reported as `pauseId` by conversation and group `approval-status` (REST and MCP). `ConversationHitlService.resumeConversation` refuses a mismatching decision before the CAS, and re-checks after it against the snapshot the resume runs on (restoring the pause if it changed in between) — `IConversationService.PauseMismatchException`, REST `409`, MCP `errorCode: PAUSE_CHANGED`. The pre-CAS comparison runs only while the conversation is `AWAITING_HUMAN`: an already-resolved conversation gets the ordinary state conflict (`409` naming the state, MCP `WRONG_STATE`). `GroupHitlCoordinator.resumeDiscussion` checks it against `pausedAt` and throws `IGroupConversationService.GroupPauseMismatchException` (a `GroupDiscussionException`, so REST still answers `409`); the MCP `approve_group_phase` tool takes an optional `pauseId` argument like `resume_conversation` and answers `PAUSE_CHANGED`.
- **H5 — the approver's identity covers what the approver saw.** A resume bound the approver as the caller for the whole resumed turn, so every later `${caller:token}` call — including all calls after a RULE pause, which previews nothing — went out with an admin's token. Now: approver == conversation owner → unchanged. Otherwise the turn runs with **no caller** (caller-bound calls fail closed, like a scheduled turn), and the approver is bound on a separate `CallerIdentityContext` approver slot that `ToolLoopResumer` promotes to the caller only around each explicitly approved tool call (`callAsApprover`). `propagate()` carries the new slot. A resumed group discussion runs as the approver only when the approver started it (`GroupHitlCoordinator.resumeCallerFor`).
- **A2 — the approver view drops the batch fingerprint.** `sanitizePendingToolCallsForApprover` now nulls `PendingToolCallBatch.fingerprint`, an unsalted SHA-256 over tool names and raw arguments (an offline guessing oracle next to the redacted arguments). It is still persisted for the no-progress guard.

### Compatibility

- **Behaviour change (intended):** a HITL resume by someone other than the conversation owner no longer carries a caller identity outside the approved tool calls.
- REST/MCP shapes are additive: `pauseId` on the decision body, the `approval-status` summaries and as an optional argument of the MCP `resume_conversation` and `approve_group_phase` tools (an additive MCP tool-schema change). Omitting `pauseId` keeps the previous behaviour.

### Design decisions

- Pause identity is the pause start timestamp in epoch milliseconds, not a new stored field: every pause stamps a fresh `hitlPausedAt` / `pausedAt`, Mongo keeps milliseconds, and REST already exposes `pausedAt` — no migration. It is the same value `main`'s Slack approval records use as their pause epoch.

**Files:** [`ConversationHitlService.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationHitlService.java), [`CallerIdentityContext.java`](../../src/main/java/ai/labs/eddi/engine/security/CallerIdentityContext.java), [`ToolLoopResumer.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopResumer.java), [`GroupHitlCoordinator.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupHitlCoordinator.java), [`HitlDecision.java`](../../src/main/java/ai/labs/eddi/engine/lifecycle/model/HitlDecision.java), [`McpHitlTools.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpHitlTools.java), [`ConversationMemoryUtilities.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryUtilities.java); docs [`hitl.md`](../hitl.md), [`mcp-server.md`](../mcp-server.md).

### Follow-ups

- The group approve path still reads, checks and CAS-writes on state only; the pause-id check there has a read-to-CAS window until the group conversation gets a state-and-version CAS.
- `GroupHitlCoordinator.submitHumanInput` binds the submitting human member for the rest of the discussion — the same class as H5, not in this finding's scope.
- The Manager approvals UI should pass `pauseId` from `approval-status` into its resume call (backend accepts it; omitted keeps the old behaviour).

```decision-log
| 2026-09-26 | HITL decisions may carry a pauseId (epoch-ms of the pause start), checked before and after the resume CAS | A stale REST/MCP decision could approve a later, different pause | A stored random pause UUID (needs a schema field and migration for RULE pauses) |
| 2026-09-26 | A non-owner approver's identity is bound only around the tool calls they approved; the rest of the resumed turn has no caller | ${caller:token} calls after an approval ran with the approver's token, including un-previewed calls after a RULE pause | Keeping the whole-turn binding (privilege escalation); dropping it entirely (breaks approved caller-bound calls) |
```

## 🔒 fix(slack): reconcile the Slack HITL binding branch with main's channel-identity fix (2026-09-29)

**Repo:** EDDI (`fix/slack-hitl-binding`)

`main` landed `fix/security-channel-identity` (#861) while this branch was open, fixing the same Slack findings a different way: persisted approval-card records with a per-card id (`ISlackApprovalRecordStore`), event signatures bound to the channel's owner, DMs routed to the signing integration, mandatory `slack:<team>:<user>` ids with the workspace taken only from a declared `platformConfig.teamId`, and unique integration names. Wherever both sides fixed the same thing, **main's implementation is kept** and this branch's is dropped:

- Dropped: the `channelIntegrationId` start context and group origin mapping (main's approval records bind a decision to the card its integration posted); the `<integration>|<subject>|<pauseId>` button value and "out of date" / no-buttons card variants (main's card id is stronger); `SlackEventEnvelope` and `SlackSignatureVerifier.matchingSecret`; the optional `platformConfig.appId` pin; the routed-only name uniqueness check; the opt-in `eddi.slack.namespace-user-ids` / `eddi.slack.legacy-team-id` settings with the bare-id memory copy, and `SlackIdentityStartupCheck`; team-scoped (`T…:U…`) approver entries (a declared `teamId` covers the case, below).

What this branch still adds on top of main's Slack code:

- **A Slack decision carries the pause its card was checked against.** `SlackInteractivityHandler` matched the clicked card to the subject's current pause and then resumed in a second step, so a resume and re-pause in between went undetected (main documented this as a residual). The decision now carries `HitlDecision.pauseId`, which the engine re-checks under the resume CAS and in the group resume; a mismatch marks the card "already resolved".
- **Routes found by a sender-chosen timestamp are bound to the signing app.** The webhook binds a signature to the channel's owner, but a DM thread lock and a group-discussion follow-up are looked up by a timestamp in the body. `EventOrigin` now carries the verified signing secret, and every route the handler takes must belong to an integration or legacy connector holding it (`SlackEventHandler.routeMatchesSigner`). A DM thread reply takes its credentials only from an app that holds the secret *and* serves the locked target (`ChannelTargetRouter.threadCredentialsForDm`) — previously another app's secret could continue it, and the reply had no bot token at all. A follow-up must be in the discussion's own channel and come from the app that started it, and is answered with that route's token. A DM signed by a legacy connector goes to the legacy connector holding its secret (`resolveLegacyDefaultForDm`) instead of the first new-style integration.
- **An approver click from another workspace is refused when the integration declares its `teamId`.** A bare Slack user id is unique within one workspace only, so in a shared (Slack Connect) approval channel a user of another workspace could carry a listed approver's id; `SlackInteractivityHandler` now compares the clicker's `user.team_id` with the declared workspace.
- **A channel integration name may not contain `|`**, which separates the name from the subject and card id in a Slack button value; such a name made every decision on its cards unresolvable.

**Files:** [`SlackInteractivityHandler.java`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackInteractivityHandler.java), [`SlackEventHandler.java`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java), [`RestSlackWebhook.java`](../../src/main/java/ai/labs/eddi/integrations/slack/rest/RestSlackWebhook.java), [`ChannelTargetRouter.java`](../../src/main/java/ai/labs/eddi/integrations/channels/ChannelTargetRouter.java), [`RestChannelIntegrationStore.java`](../../src/main/java/ai/labs/eddi/configs/channels/rest/RestChannelIntegrationStore.java); docs [`slack-integration.md`](../slack-integration.md), [`hitl.md`](../hitl.md).

```decision-log
| 2026-09-29 | Where this branch and #861 fixed the same Slack finding, keep #861's implementation (approval records + card ids, owner-bound signatures, mandatory namespaced ids) | Two parallel bindings of one decision are harder to reason about than one | Keeping both bindings; keeping this branch's opt-in user-id namespacing |
| 2026-09-29 | Every Slack route the event handler takes must belong to an app holding the secret that verified the event | Thread locks and group follow-ups are found by a sender-chosen timestamp, outside the webhook's channel-owner check | Checking the channel only (a DM is owned by nobody) |
```
