## 🐛 fix(cluster): review follow-ups to #1089 — non-blocking heartbeat tick, drain/acquire race, one dead-letter budget (2026-10-19)

**Repo:** EDDI · **Branch:** `fix/cluster-resilience-review-followup`

### What changed and why

#1089 merged before its last round of review fixes was pushed. This lands them on their own.

- **The lease scheduler never waits for a heartbeat pass**
  ([`KvLeaseManager`](../../src/main/java/ai/labs/eddi/engine/cluster/lease/KvLeaseManager.java)).
  The single lease-scheduler thread also runs every lease waiter's retry and deadline timer.
  `heartbeatAll` waits up to one interval for its renewals, so a stalled renewal delayed those
  timers by up to an interval. The tick (`dispatchHeartbeat`) now only starts the pass on the I/O
  executor; a tick that finds the previous pass still running skips.
- **A leader lease granted while a drain began is handed back.** `tryAcquireKey` checked `draining`
  before its KV create; a drain whose sweep ran during that create could not see the new lease, so the
  drained node kept renewing a leader lease no other node could take. It re-checks after the grant.
- **One budget for a dead-letter append**
  ([`JetStreamDeadLetterStore`](../../src/main/java/ai/labs/eddi/engine/cluster/JetStreamDeadLetterStore.java)).
  The ~2 s retry budget started only after the first publish and the provisioning, each of which can
  take a full request timeout against a NATS that stopped answering. It now covers the whole append.
- **Late on-connect hooks** (registered after the connection is up) also run on the hook executor
  ([`NatsConnectionManager`](../../src/main/java/ai/labs/eddi/engine/cluster/NatsConnectionManager.java)),
  not on the shared scheduler.
- **Cancellation in a mixed gated/allowed tool batch**
  ([`ToolLoopRunner`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java)): the
  allowed calls of a batch that also pauses for approval now check the turn's cancel flag before each
  tool, like the main loop.

### Tests

`KvLeaseManagerTest.leaderAcquiredDuringADrainIsReleased`, `heartbeatTickNeverBlocksTheScheduler`,
`JetStreamDeadLetterStoreTest.aFirstPublishThatUsedUpTheBudgetSkipsProvisioningAndRetries` — each fails
with its fix reverted. The renewal-overlap test releases its gate in `finally`.
