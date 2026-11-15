## 🐛 fix(gdpr): an approval-analytics rebuild no longer brings back an erased decider (2026-11-15)

**Repo:** EDDI (`fix/gdpr-analytics-rebuild-erased`)

### What was wrong

HITL approval analytics (`approval_decisions`) are a projection of the audit ledger's `hitl.approval` entries. Erasure pseudonymised the decision rows that existed (`OversightGdprParticipant` → `IApprovalDecisionStore.eraseUser`), but a ledger entry is filed under the conversation owner and names its decider in `output.decidedBy`. Erasing a decider who is not the owner leaves that entry as it was, so a rebuild (`POST /hitl/analytics/rebuild`, the first-start backfill) that re-inserted a row it did not have — lost to retention or a failed write, or a pre-6.7 entry imported for the first time — stored the erased person's raw id again. In `erasure-mode=pseudonymize` the same held for an owner who decided their own pause. The existing barriers did not cover it: `ErasureTombstones` is per conversation and expires after seven days, and the ledger's erased-user rewrite is node-local and lasts an hour.

### What changed

- `engine/gdpr/IErasedSubjectStore` with `engine/gdpr/mongo/MongoErasedSubjectStore` and `datastore/postgres/PostgresErasedSubjectStore` (collection / table `gdpr_erased_subjects`), produced by `DataStoreProducers`: one row per erased person, a keyed one-way token and the time of the latest erasure. The write is an idempotent upsert that keeps the later time (`$max` / `GREATEST`).
- `engine/gdpr/ErasedSubjects`: derives the token, answers "was this identity erased, and when" through a bounded 30-second Caffeine cache (the store is the source of truth; this node's own erasure invalidates its entry), skips identities that cannot be a person (`system:*`, `unknown`, `anonymous`, existing pseudonyms), and hands out the ledger's pseudonym.
- `GdprPseudonymizer.stableTokenFor`: the token is always the per-deployment salted HMAC, never the ledger's keyed pseudonym, so it survives audit-key rotation.
- `GdprComplianceService.deleteUserData` records the person before anything is erased (step 0c) and again when the cascade ends; a failed write reports the step `erasedSubjectRecord` and the erasure incomplete.
- `ApprovalDecisionProjector` checks `userId` and `decidedBy` of every row, live and rebuilt: for a decision made no later than the erasure it stores what erasure leaves (no `userId`, the ledger's pseudonym as decider), so aggregates match the erased rows. When the record cannot be read the unit is not stored and counts as a failed projection.
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
