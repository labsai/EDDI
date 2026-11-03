## fix(hitl,alerts): self-heal the journal TTL index and release digests held by stale rules (2026-11-01)

**Repo:** EDDI (`fix/hitl-alerts-followups`, stacked on `fix/review-engine-hitl-alerts`)

Closes the two items deferred in the HITL/alerts review fixes (`2026-10-31-review-engine-hitl-alerts`).

### What changed and why

- **Journal TTL index self-heals.** During a rolling update an old pod could recreate the unfiltered `idx_journal_ttl_claimed` after a new pod dropped it, silently expiring `EXECUTING` claims again. `IHitlToolJournalStore.reconcileRetention()` (MongoDB: lists the indexes, drops any unfiltered TTL on `claimedAt` or a legacy name with a `WARN`, re-ensures the partial `idx_journal_ttl_completed`) is called by the new `HitlToolJournalMaintenance` scheduled job every `eddi.hitl.tool.journal-reconcile-interval` (`15m`); the constructor still drops the known names at startup. PostgreSQL has no such index (default no-op).
- **Stale-cache digest events are released.** `IAlertEventStore.findDigestPendingRuleIds` (Mongo `distinct`, Postgres `SELECT DISTINCT`) lets `AlertMaintenanceService` release `DIGEST_PENDING` events whose rule is now deleted, disabled or has no digest, so an event created by a replica that still cached the old rule stays in the inbox instead of being held until retention. `AlertMaintenanceService.Result` gained `released`.

### Design decisions

- The reconciliation is a periodic pass over the existing alert maintenance job and a small `@Scheduled` bean for the journal, not a new scheduler or a migration marker: both repairs are idempotent, so every replica may run them.
- The journal job lives in its own bean because the Mongo store is created lazily only on MongoDB; a `@Scheduled` method on the store would instantiate it on PostgreSQL.
