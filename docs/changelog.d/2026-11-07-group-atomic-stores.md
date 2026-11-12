## fix(groups): atomic budget cap sets and exact outcome caps on both stores (2026-11-07)

**Repo:** EDDI (`fix/group-atomic-stores`)

### What changed and why

- **Setting a team's monthly cap is a compare-and-set.** `TeamBudgetService.setCurrentMonthCap` read the month, computed
  `delta = capUsd - cap` and `$inc`'d the adjustment by it. Two sets racing on a cap of 50 — to 100 and to 80, say an
  administrator and an approved `TeamBudgetProposalTool` proposal — stored 50 + 50 + 30 = 130, a cap nobody asked for,
  while the audit said 50 → 100 and 50 → 80. `ITeamBudgetStore` gains `setAdjustment(expected, new, …)` with a new
  `AdjustOutcome.CONFLICT`: MongoDB applies it as one `findOneAndUpdate` whose filter carries the expected adjustment and
  the "cap stays at or above the spend" predicate, PostgreSQL under the row lock it already took for transfers. The
  service re-reads and retries a conflict up to three times (the later set wins, and its audit entry names the cap it
  actually replaced), then answers 503. Transfers keep `applyAdjustment`: a delta is the right meaning there.
- **The per-subject outcome cap holds under concurrent posts.** `OutcomeEventService.record` counted the subject and
  inserted in two steps, so N concurrent posts at `maxPerSubject - 1` all stored. `IOutcomeEventStore.insertIfAbsent`
  becomes `insert(event, maxPerSubject)` answering `INSERTED` / `DUPLICATE` / `FULL` (a duplicate wins over full, so a
  retried webhook on a full subject still gets 200). PostgreSQL counts and inserts in one transaction behind a
  per-subject `pg_advisory_xact_lock`, as `PostgresAttachmentStore` does. MongoDB has no transactions in EDDI, so each
  event claims the lowest free `slot` of its subject under a new unique partial index
  `idx_outcome_subject_slot (groupId, subjectId, slot)`: two writers that read the same subject pick the same slot and
  only one stores. The service's own count, now redundant, is gone.
- **Dead code removed:** `OutcomeEventService.retroDigestProvider()` (the RETRO digest is wired through
  `retroDigest` directly), `SharedTaskList.findTasksForAgent` (only its own two tests called it) and
  `IOutcomeEventStore.countBySubject` with both store implementations (its one caller was the service's count).

### Design decisions

- **Slots rather than insert-then-check on MongoDB.** Inserting and then deleting the event if it is not among the
  subject's oldest `maxPerSubject` over-admits whenever an event that sorts earlier is inserted after a later one's
  check — under eight racing writers, often. The slot index makes the cap exact for a constant `maxPerSubject`; after a
  lowered cap, or for rows written before this change (none in a release: outcomes are new in 6.8.0), the up-front
  count still refuses sequentially.
- **Three attempts, then 503** for a set that keeps losing: a set that conflicts has a fresh snapshot to retry with,
  and three consecutive losses mean sustained contention an administrator should retry, not a loop to spin in.

**Files:** [`ITeamBudgetStore.java`](../../src/main/java/ai/labs/eddi/engine/groups/budget/ITeamBudgetStore.java),
[`MongoTeamBudgetStore.java`](../../src/main/java/ai/labs/eddi/engine/groups/budget/MongoTeamBudgetStore.java),
[`PostgresTeamBudgetStore.java`](../../src/main/java/ai/labs/eddi/engine/groups/budget/PostgresTeamBudgetStore.java),
[`TeamBudgetService.java`](../../src/main/java/ai/labs/eddi/engine/groups/budget/TeamBudgetService.java),
[`IOutcomeEventStore.java`](../../src/main/java/ai/labs/eddi/engine/groups/outcomes/IOutcomeEventStore.java),
[`OutcomeEventService.java`](../../src/main/java/ai/labs/eddi/engine/groups/outcomes/OutcomeEventService.java),
[`MongoOutcomeEventStore.java`](../../src/main/java/ai/labs/eddi/engine/groups/outcomes/mongo/MongoOutcomeEventStore.java),
[`PostgresOutcomeEventStore.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresOutcomeEventStore.java),
[`SharedTaskList.java`](../../src/main/java/ai/labs/eddi/configs/groups/model/SharedTaskList.java);
docs [`team-budgets.md`](../team-budgets.md), [`team-learning-loop.md`](../team-learning-loop.md).
