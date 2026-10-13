## NATS cluster stack brought up to main (2026-10-13)

**Repo:** EDDI · **Branch:** `feat/nats-cluster` (PR #956, with #957, #958 and #970 merged into it)

`origin/main` merged into the cluster stack after #937, #942–#954, #990, #993, #996,
#999, #1001, #1008, #969, #936, #967, #1000, #951 and #1002 had landed on it.

### How the cluster code now sits on main's APIs

- **Lost lease → `abandonTurn` (#949).** The lease-lost hook in `ConversationStepRunner`
  raises the cancel flag through `abandonTurn`, the same seam `ITurnAbandonment` and the
  watchdog use, aimed at the turn's own memory rather than looked up by conversation id
  (a late callback must never hit a newer turn). The lease block now runs inside the
  guarded `try` around main's `InFlightRegistration`.
- **Lost lease vs `settleCancelledTurn` (#949).** Both the completion and the failure
  callback ask `discardLeaseLostTurn` first. A lease-lost turn therefore never gets the
  `EXECUTION_INTERRUPTED` CAS that a `/cancel` gets — that state would overwrite the one
  the new holder committed — and is still dead-lettered (`lease-lost`) and answered 409.
  Before, a lease-lost turn that *failed* (rather than completed) took the generic error
  path.
- **Lease-loss reporting on main's single turn path.** `ConversationService` lost its two
  copies of the turn code to `admitTurn` (#949); the lease check is now
  `TurnListener.leaseLost` — skipped/409 for a plain turn, an error event for a stream —
  and runs before `recordTurnOutcome` (#1000), so a turn that is not stored never records
  an idempotency key or a turn error.
- **Erasure redaction (#952).** `pseudonymiseIfErased(entry, queued)` keeps the
  `erasedElsewhere` check: a late entry of a user erased on another node is pseudonymised
  *and* redacted, as on the erasing node. Its drain counters are kept only for users erased
  on this node (nothing would report or clear the others).
- **A2A (#953).** `CachedA2ATaskStore` asks `CacheFactory` for `a2aTaskMapping` and
  `a2aTaskMapping:context`, which cluster mode backs with the `A2A_TASKS` and
  `A2A_CONTEXTS` buckets; a new test round-trips an `A2ATaskRecord` between two nodes.
  `A2A_STATES` is no longer used and is gone from the bucket map and the docs.
- **Undeploy (#990).** `IAgentFactory.undeployAgent` no longer throws checked exceptions;
  the cluster's local undeploy catches `RuntimeException` only.
- **Helm chart 2.7.0** (main is at 2.6.0, from #951). `eddi.updateStrategy` keeps the per-messaging-type
  default and accepts main's object shape; the chart still refuses `RollingUpdate` with
  in-memory messaging, which #938 had documented as an opt-in (see
  [Kubernetes](../kubernetes.md#upgrades-replace-the-pod-no-surge)).
- **Operator revision 9** (main is at 8, from #1002): the merged prompt carries both the cluster
  questions and main's admin-action / redaction text.
- **Turn deadline (#996).** [Clustering](../clustering.md#sizing) now says that a deadline
  counts from arrival, so time spent waiting for the lease is spent from it.

### Open for review

- `TurnIdempotencyService` (#1000) keeps in-flight keys per node. A duplicate of a running
  turn that reaches another node waits for the lease rather than getting a 409, and the
  persisted-key check happens at admission only, so that duplicate can run the turn a
  second time.
