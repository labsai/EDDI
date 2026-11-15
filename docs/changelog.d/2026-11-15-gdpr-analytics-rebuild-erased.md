## 🐛 fix(gdpr): an approval-analytics rebuild no longer brings back an erased decider (2026-11-15)

**Repo:** EDDI (`fix/gdpr-analytics-rebuild-erased`)

### What was wrong

HITL approval analytics (`approval_decisions`) are a projection of the audit ledger's `hitl.approval` entries. Erasure pseudonymised the decision rows that existed (`OversightGdprParticipant` → `IApprovalDecisionStore.eraseUser`), but a ledger entry is filed under the conversation owner and names its decider in `output.decidedBy`. Erasing a decider who is not the owner leaves that entry as it was, so a rebuild (`POST /hitl/analytics/rebuild`, the first-start backfill) that re-inserted a row it did not have — lost to retention or a failed write, or a pre-6.7 entry imported for the first time — stored the erased person's raw id again. In `erasure-mode=pseudonymize` the same held for an owner who decided their own pause. The existing barriers did not cover it: `ErasureTombstones` is per conversation and expires after seven days, and the ledger's erased-user rewrite is node-local and lasts an hour.

### What changed

- `engine/gdpr/IErasedSubjectStore` with `engine/gdpr/mongo/MongoErasedSubjectStore` and `datastore/postgres/PostgresErasedSubjectStore` (collection / table `gdpr_erased_subjects`), produced by `DataStoreProducers`: one row per erased person, a keyed one-way token and the time of the latest erasure. The write is an idempotent upsert that keeps the later time (`$max` / `GREATEST`).
- `engine/gdpr/ErasedSubjects`: derives the token, answers "was this identity erased, and when" from the store, caching only positive answers (an erasure time only grows; a "not erased" answer is never cached, and a cached time that does not cover the record is re-read, so another node's erasure is seen at once); times are compared in milliseconds, the stores' precision, skips identities that cannot be a person (`system:*`, `unknown`, `anonymous`, existing pseudonyms), and hands out the ledger's pseudonym.
- `GdprPseudonymizer.stableTokenFor`: the token is always the per-deployment salted HMAC, never the ledger's keyed pseudonym, so it survives audit-key rotation. It has no unsalted fallback: without the salt it throws, so erasure reports `erasedSubjectRecord` failed and a projection skips the row, rather than looking a person up under a token their record was not written under.
- `GdprComplianceService.deleteUserData` records the person before anything is erased (step 0c) and again just before the participants pseudonymise the stored rows (step 8a); a failed write reports the step `erasedSubjectRecord` and the erasure incomplete.
- `ApprovalDecisionProjector` checks `userId` and `decidedBy` of every row, live and rebuilt: for a decision made no later than the erasure it stores what erasure leaves (no `userId`, the ledger's pseudonym as decider), so aggregates match the erased rows. When the record cannot be read the unit is not stored and counts as a failed projection. A row inserted with an identity kept is stored *pending check* and checked again after the insert; the result is written to that row only, compare-and-set against the values the check started from (`IApprovalDecisionStore.markChecked`), so it never touches the same person's other rows (a decision made after the erasure keeps its id) and never overwrites an erasure's own pass. Because erasure records before its participants run, a row inserted in that window is reached by one side or the other. If the second check fails, the row is deleted (`deleteById`) so the next rebuild projects it again; if that fails too, or the process dies after the insert, the row stays pending and `ApprovalBackfillService.reconcilePending` checks it — at most 1,000 per call, from the hourly HITL maintenance and after every rebuild.
- `IApprovalDecisionStore`: `markChecked`, `pendingCheck`, `deleteById`; MongoDB adds a sparse `pendingCheck` field and index, PostgreSQL a nullable `pending_check` column (`ALTER TABLE … ADD COLUMN IF NOT EXISTS`) with a partial index.
- Docs: [`gdpr-compliance.md`](../gdpr-compliance.md) (step 16, and the approver row of "What erasure and export do not reach"), [`hitl-oversight.md`](../hitl-oversight.md#gdpr).

### Design decisions

- **A new store, not the ledger's compliance record.** The `GDPR_ERASURE` compliance entry already names an erased user, but it is written last and asynchronously, only when the ledger is enabled, under a pseudonym that changes with the signing key, and finding it would need a scan by task. Nothing durable and cluster-wide keyed by person existed, so one was added — and made generic (`engine/gdpr`) so other ledger-derived projections can consult it.
- **Erasure time, not a permanent ban.** A decision made after the erasure belongs to a returning user and keeps their id; only decisions made at or before the latest erasure are rewritten.
- **Fail closed.** An unreadable record means the unit is skipped, not stored with an identity that may be erased; the rebuild is idempotent, so the next one repairs it.
- **The pseudonym is not stored.** It is recomputed from the raw id the ledger entry carries with the function erasure uses (`AuditLedgerService.pseudonymOf`), so the record holds nothing but the token and a time.

### Not changed

- The ledger entry itself still names the approver in another user's `hitl.approval` row; that is now listed under "What erasure and export do not reach".
- Group outcome events (`groupOutcomes`) have no ledger rebuild and need no change.

```decision-log
| 2026-11-15 | GDPR erasure records erased people in `gdpr_erased_subjects` (keyed token + erasure time); ledger-derived projections consult it so a rebuild cannot re-insert an erased identity | The ledger names deciders in entries filed under other users, which erasure does not rewrite; no durable, cluster-wide per-person record existed | Deriving it from the ledger's `GDPR_ERASURE` compliance entries (written last and async, absent when the ledger is off, keyed by a rotating pseudonym); `ErasureTombstones` (per conversation, 7-day TTL) |
```
