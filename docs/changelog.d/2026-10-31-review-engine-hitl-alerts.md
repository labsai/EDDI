## fix(engine): review findings — HITL, GDPR, Dream, alerts, audit, mail (2026-10-31)

**Repo:** EDDI (`fix/review-engine-hitl-alerts`)

Fixes from the CodeRabbit review and the full code review (against `01c3250`) for the engine runtime and the packages around it. Each fix has a unit test that fails without it.

### What changed and why

**HITL**
- A reissue of an approved call that has already executed is gated again (`ToolApprovalGate.REPEAT_REASON`) instead of running a second time on one approval.
- Approval patterns are matched by a linear glob matcher (`ToolApprovalPatterns.matches`), not one backtracking `.*` per wildcard.
- `TaskToolApprovalsResolver` (strict) pins a task rule with no `timeoutPolicy` to `WAIT_INDEFINITELY` when it would inherit the agent's `AUTO_APPROVE`, so it cannot hide a stricter agent rule.
- `SelfUngatingGuard` compares an LLM-store write against the stored configuration: a whole-document `PUT` that omits a stored task gate, or a patch on its ancestor, is refused (lookup wired through `ActingAgentResources`).
- The tool journal's TTL expires `EXECUTED` entries only; a crash-orphaned `EXECUTING` claim is kept until its conversation is deleted, so a late approval cannot run the call again.

**GDPR, tenancy, ratings, Dream**
- Erasure keeps conversation snapshots when attachment deletion failed, keeps mapping rows when their cache keys could not be read, and reports nodes that did not confirm the cluster stop (`IClusterRpc.expectedPeers`). `restrictProcessing` removes a legacy agent-owned row under the reserved key first, which otherwise made the restriction unenforced.
- `PostgresTenantQuotaStore.setQuota` propagates a write failure (503) instead of acknowledging a quota that was never stored.
- Withdrawing a rating blanks the comment excerpts alerts copied from it first; erasure could not find them afterwards.
- Dream partitions consolidation by group readership (no union of group ids across groups), merges duplicate keys in linear time with the cap checked per append, and refuses a cycle whose pinned agent version names an ungranted vault secret.

**Alerts and audit**
- Webhook targets, signing-secret references and multi-agent rules need the `eddi-admin` role (`AlertAccess.hasAdminRole`), not merely unrestricted visibility, which every editor has while workspaces are off.
- Digests go out per originating agent; deleting/disabling a rule or setting its digest to `NONE` releases its `DIGEST_PENDING` events (`IAlertEventStore.releaseDigestPending`). An output value older than the erasure fence's lifetime is not stored. `AlertEngine` lost its test-only constructors and the duplicated 24-argument event rebuilds (`AlertEvent.withMatch`), and uses `AlertMatchers.applies`.
- The audit chain check finds holes between sequenced rows in a window that also holds unsequenced rows (was `UNAVAILABLE`), and with signing on ignores positions claimed by v1/v2-signed rows, whose signature does not cover the sequence.

**Mail**
- `DkimVerifier` refuses a header block with a bare CR or LF (the MIME parser splits a header there, the verifier did not). `QuoteStripper`'s label patterns are possessive (quadratic backtracking on long blank runs).

Docs: [Alerts](../alerts.md), [Audit Ledger](../audit-ledger.md), [HITL](../hitl.md), [Secrets Vault](../secrets-vault.md).

- Second review round: a digest is skipped when its rule was disabled or deleted after the pass began, and withdrawn (not delivered) when every held event was released first; `NatsClusterRpc.expectedPeers` reads presence uncached, fails closed when it cannot, and excludes this node by id; erasure keeps conversation snapshots when the conversation-id lookup failed; a failed tool-journal delete keeps the conversation (`ConversationPurgeService.purge` throws) so a retry can still reach the rows naming the approver; Dream `warn` mode logs ungranted vault references; agent-trigger cache misses re-check inside the stripe lock; `AgentSetupService` registers API-call locations before the creation loop so rollback sees them; two docs/Javadoc corrections.

### Design decisions

- **A repeat of an executed approved call goes back to a human** rather than replaying the journaled result: replay needs a new result path through `ToolLoopRunner`; re-gating is safe with the code this group owns.

### Not done (deferred)

- **Stale cached alert rules** can, for up to the rule cache TTL (60 s) on another replica, still create a `DIGEST_PENDING` event after the rule was disabled; such an event stays visible in the inbox until retention. Reconciling them needs a store query for held events of rules that are no longer digest rules.
- **Mongo journal TTL migration during a rolling update:** an old pod can recreate the unfiltered `idx_journal_ttl_claimed` after a new pod dropped it, until it is stopped. Finish the upgrade (no old pods) before relying on the EXECUTING-claim retention; a versioned migration marker is not part of this change.

- **Audit tail / whole-trail deletion** stays undetectable: it needs an anchored chain head stored outside the trail (or a predecessor digest in a new canonical form), which is a format change.
- **Interim narration of a tool-approval pause** is not screened by the task's output guardrails: the narration is built in `modules/llm` (`ToolApprovalGateSupport`), where the guardrails live.
- **Alert webhook HMAC keys** have no grant model of their own; with webhooks now reserved to the administrator role (who controls grants anyway) the remaining oracle is administrator-only.
