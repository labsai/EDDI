## Agent Health adopts the GDPR erasure guarantee (2026-11-26)

**Repo:** EDDI · **Branch:** `feat/agent-health`

`main` gained the hard erasure guarantee for asynchronously written rows (#1450):
durable tombstones, guarded writes (`ErasureGuard.beforeWrite` / `afterWrite` with an
`Admission`), a drain barrier per async writer, and verification. This branch merged
`main` and replaced its best-effort `HealthErasureFence` with it, as the GDPR guide's
"For a new async writer" contract and `HealthShapedWriter` describe. This answers the
open review finding that a queued health row could land after an erasure or a
permanent conversation delete reported success.

- **`HealthErasureWriter`** (new, `engine/health`): the `AsyncErasureWriter` bean named
  `agentHealthTurns`. Its drain barrier is the health worker's queue; `removeRow`
  deletes one row by `<conversationId>|<executionId>`; `eraseConversation` and
  `countForConversation` delete and count a conversation's rows and markers.
- **`HealthTurnRecorder`**: queues every write through a `DrainBarrier` (the bounded
  executor keeps `AbortPolicy`), asks `beforeWrite` with the turn's capture time and
  writes neither the row nor its discard markers when refused, and calls `afterWrite`
  for the row and each marker after the write, also after a failed one. The
  fence and the per-write conversation re-read are gone.
- **`HealthTurnGdprParticipant`**: deletes the user's rows, then
  `verifyUserErased(agentHealthTurns, …)` over a count that includes markers.
- **`IHealthTurnStore`**: `deleteRow`, `countByUserId` and `countByConversationId`, in
  both stores. The MongoDB counts read the primary with majority read concern.
- **`ConversationPurgeService`**: a guarded purge erases the health rows through the
  guard. The best-effort `deleteHealthTurns` stays only for the unguarded agent-test
  purge, and the orphan sweep remains for that case.
- Docs: `agent-health.md` (Retention, GDPR, Metrics), `gdpr-compliance.md` item 18
  and the verify step, `metrics.md`, and the Agent Health dashboard panel text.
- The Agent Health dashboard row moved to panel ids 466–468, after `main`'s GDPR
  Erasure Guarantee row (463–465).
- Tests: `HealthTurnRecorderTest` runs a real `ErasureGuard` over in-memory tombstones.
  It covers erasure and conversation delete while queued and while writing, a failed
  write, a failed removal that keeps the erasure failing until a retry, unreadable
  tombstones, and the drain barrier. `HealthTurnStoreContract` covers the new store
  methods, and `ConversationPurgeServiceHealthTest` covers the guarded purge.

```decision-log
| 2026-11-26 | Agent Health turn rows are an `AsyncErasureWriter` (`agentHealthTurns`) of the GDPR erasure guarantee; `HealthErasureFence` and the per-write conversation re-read are removed | A node-local, 15-minute fence plus clean-up was best effort, and main now offers a guarantee every async writer adopts | Keeping the fence next to the guard; one guarded removal per conversation instead of per row |
```
