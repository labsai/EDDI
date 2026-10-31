## fix(engine): review findings — runtime, scheduling, cluster, HITL, GDPR, alerts, audit, mail (2026-10-31)

**Repo:** EDDI (`fix/review-engine-runtime`)

Fixes from the CodeRabbit review and the full code review (against `01c3250`) for the engine runtime and the packages around it. Each fix has a unit test that fails without it.

### What changed and why

**Runtime and REST**
- `CronParser.parseField` checks a range's endpoints against the field's domain *before* enumerating it; `0-2000000000` used to allocate billions of boxed integers first (reachable from group cadences and schedules). The stepped loop no longer wraps on a huge step.
- `DocumentDescriptorFilter` takes the deleted resource's id from the request path and the `version` parameter only; `?x=/<other id>` made it mark another resource's descriptor deleted.
- `LegacyPathRewriteFilter` rewrites a v5 store prefix only at the start of the path, and `restricted`/`unrestricted` only in `/administration/{environment}/…`. A substring replace turned `DELETE /admin/gdpr/restricted` into an erasure of user `production`.
- `WorkflowStoreClientLibrary` reads secret instructions from the executable `PropertySetter` the task configures to; the check for `PropertySetterConfiguration` never matched, so failed turns of secret-collecting agents kept their input in dead letters.
- `RestInterfaceFactory` addresses the HTTP listener EDDI is bound to (`SelfUrlResolver`'s logic), not always `127.0.0.1`, and forwards no caller token when that address cannot be resolved.

**Scheduling and cluster**
- A schedule whose claim expired without an outcome (its node died or the fire wedged) counts as a failed attempt instead of being re-fired at once with `failCount` untouched — a node-killing fire could never dead-letter. The poll runs with `concurrentExecution = SKIP`. `tryClaim` re-checks `enabled` (MongoDB and PostgreSQL). `POST /{id}/fire` and the MCP `fire_schedule_now` tool are bounded by the fire timeout like a polled fire, so a manual Dream or ingestion run cannot outlast its lease and start twice. A disabled schedule can no longer be fired manually.
- A scheduled fire that starts a conversation runs the agent's startup workflow under `maxCostPerFire`, and the turn gets the remainder.
- A dead letter a node kept locally while cut off from NATS is discarded, not forwarded, when its conversation no longer exists (erased in the meantime).
- NATS TLS verifies the broker host name (`eddi.nats.tls.verify-hostname`, default `true`); jnats sets no endpoint identification, so any trusted certificate for any host was accepted. **Upgrade note:** the broker certificate must now carry a SAN for every host in `eddi.nats.servers` (an IP SAN for an IP address); see [Clustering](../clustering.md).
- `CacheFactory.evictLocal` remembers key hashes instead of SHA-256-ing every cached key on every eviction event. `BoundedLogStore`'s DB queue is capped (50,000; drops are counted and logged at flush). `BoundedFanOut` claims "not started" by compare-and-set, so an item reported as not sent can no longer go on and send. `AgentTestRunner` charges an abandoned (timed-out) turn's cost when it completes.

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

**Cleanup:** unused `ClusterConfig.withDegradedNonces/withDegradedRateLimits/withHitlRecovery` removed; misplaced Javadoc in `ScheduleFireExecutor` and `SchedulePollerService` moved to its methods; the nine fire-log blocks in `ScheduleFireExecutor` share `recordFire`.

Docs: [Clustering](../clustering.md), [Scheduling](../scheduling.md), [Alerts](../alerts.md), [Audit Ledger](../audit-ledger.md), [HITL](../hitl.md), [Secrets Vault](../secrets-vault.md), [Configuration Reference](../configuration-reference.md).

### Design decisions

- **A repeat of an executed approved call goes back to a human** rather than replaying the journaled result: replay needs a new result path through `ToolLoopRunner`; re-gating is safe with the code this group owns.
- **An expired claim is a failure, not a retry-now.** The lease already outlasts the fire timeout by a margin, so a claim that expires means the fire is gone.
- **Hostname verification is on by default** and can be turned off; an IP address in `eddi.nats.servers` then needs an IP SAN.

### Not done (deferred)

- **Audit tail / whole-trail deletion** stays undetectable: it needs an anchored chain head stored outside the trail (or a predecessor digest in a new canonical form), which is a format change.
- **Interim narration of a tool-approval pause** is not screened by the task's output guardrails: the narration is built in `modules/llm` (`ToolApprovalGateSupport`), where the guardrails live.
- **Alert webhook HMAC keys** have no grant model of their own; with webhooks now reserved to the administrator role (who controls grants anyway) the remaining oracle is administrator-only.
- **One fire-timeout mechanism** instead of the executor's turn latch plus the poller's deadline: both now use `eddi.schedule.fire-timeout`, and the manual path is bounded; merging them is a refactor.
