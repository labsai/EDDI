## 🐛 fix(gdpr,audit,memory): erasure redacts the audit ledger, admin actions are audited, log retention is real, Dream stops losing concurrent writes (2026-10-03)

**Repo:** EDDI (`fix/gdpr-audit-memory`)

### What changed and why

**GDPR erasure left verbatim PII in the audit ledger and still said `complete: true`.**
Reproduced on MongoDB, PostgreSQL and the NATS build: a social-security number typed
into a conversation was still in 6 of 6 audit rows after `DELETE /admin/gdpr/{userId}`
answered `complete: true`. The erasure replaced the `userId` and nothing else; the
`input`, `output`, `llmDetail` and `toolCalls` maps kept the prompt, the response and the
tool arguments. And because it ran a store-side `updateMany`, entries still in the ledger's
3-second write queue landed afterwards and were missed by it — the
`auditEntriesPseudonymized: 0` seen on fast erasures.

`AuditLedgerService.eraseUser` now does the ledger part of the cascade:

1. marks the user erased and **flushes the write queue synchronously**; an entry that
   cannot be stored stays queued and is redacted as it leaves the queue, and entries
   produced afterwards by cancelled work are redacted before they are signed;
2. finds every row of the user — under the raw id **and** under any pseudonym an earlier
   erasure gave it, so re-running an erasure after upgrading cleans up rows an older
   release only pseudonymised;
3. verifies each row, then rewrites it through the new `IAuditStore.redactEntry`
   (conditional on the stored HMAC, on both backends): keyed pseudonym for `userId`, a
   `gdprRedaction` marker (reason, time, the original HMAC, the verdict before) for
   `input`, and `output`/`llmDetail`/`toolCalls` removed. Ids, timing, cost, actions,
   timestamp and `sequence` stay, so **the chain stays gap-free**;
4. **re-signs only what verified.** A valid row gets a v5 HMAC over its redacted form
   and still verifies as `VALID`; a row that was already failing keeps its old HMAC and
   keeps failing — redaction never launders tampering.

`GdprDeletionResult` gains `auditEntriesRedacted`; a row that cannot be redacted (or a
store that cannot redact) fails the new `auditRedaction` step, so `complete` is false.
GDPR compliance events and admin-action records are pseudonymised, not redacted.
`eddi.audit.erasure-mode=pseudonymize` keeps the old behaviour for a deployment whose legal
hold requires the content, with a startup WARN. `PostgresDatabaseLogs.pseudonymizeByUserId`
no longer swallows a SQL failure as "0 rows" (which also reported a complete erasure).

**"Every API call is audited" was false.** The ledger recorded pipeline tasks, HITL
decisions and the four GDPR operations — nobody who changed a prompt, deployed an agent,
replaced a vault secret, imported a backup or erased a user was on record (the GDPR event
had no actor). The new `AdminActionAuditFilter` writes one HMAC-signed record per
`POST`/`PUT`/`PATCH`/`DELETE` outside the conversational data plane (excluded by list, so a
new admin endpoint is covered by default): caller, method, path, endpoint, status — never
the body or query string, and a person's id in the path is pseudonymised. Calls an endpoint
refused (`@RolesAllowed` 403, 404, 409) are recorded too; requests turned away by an HTTP path
policy before resource matching are not. `GET /auditstore/admin-actions?actor=` lists them (new task-id index on
both backends). `eddi.audit.admin-actions.enabled` switches it off. On PostgreSQL the two
whole-ledger indexes, `idx_audit_user` and the new `idx_audit_task`, are no longer built with a
plain `CREATE INDEX` on the audit-writer thread (which blocked audit inserts for the length of
the build on a large ledger): they are built `CONCURRENTLY` in the background under an advisory
lock, an index an interrupted build left INVALID is dropped and rebuilt, and failures retry. The docs now say
exactly what is and is not recorded (reads are not; MCP memory tools are not).

**Database-log retention was documented as configurable and was not.** New
`eddi.logs.db-retention-days` (default `-1`, kept) with a daily sweep in the same shape as
the existing conversation/user-memory retention sweeps (Quarkus `@Scheduled`, idempotent
per replica — not a new scheduler; interval and initial delay configurable), `deleteOlderThan`
on both log stores, a timestamp index, and `eddi_logs_db_retention_deleted_total`.

**Dream deleted or overwrote facts updated during its run.** It read the user's memories,
spent seconds in the LLM call, then deleted and upserted by id. New
`IUserMemoryStore.deleteEntryIfUnchanged` / `replaceIfUnchanged` (one conditional statement
on `updatedAt`, MongoDB and PostgreSQL) back every prune, in-place overwrite and original
delete; a group whose originals changed during the LLM call is skipped untouched. Skips are
counted in `DreamResult.conflictsSkipped` and `eddi_dream_conflicts_skipped_total`.

**Dream's "conservative" $0.01/1K estimate undercounted premium models 2–7×.** Input and
output are priced separately: the model's price from the new
`dream.inputPricePer1M`/`outputPricePer1M`, otherwise a documented upper bound ($15/$75 per
1M, Claude Opus list price); the no-usage fallback assumes 3 characters per token.

**`enforceCapacity` race.** Concurrent `rememberFact` inserts at `maxEntriesPerUser - 1` all
passed the count check. The count is re-checked after the insert, and the overshoot settled
deterministically — the newest inserts over the cap are undone (`reject`) or each evicts one
distinct oldest own entry (`evict_oldest`).

**Compliance docs.** `compliance-data-flow.md` is now the canonical data inventory and the
single "What reaches the LLM provider" list — conversation content leaves the deployment on
every LLM call, and `global` user memories other agents wrote (the default for a `longTerm`
property of an agent without `userMemoryConfig`) can reach the prompt; GDPR, HIPAA,
PRIVACY.md and incident-response link to it instead of repeating drifted copies. Corrected:
erasure (redaction, not "pseudonymised only"; the CCPA section no longer contradicts the
GDPR one — the export is not complete and the erasure keeps two stores), audit coverage
("every API call"), HIPAA "✅ built-in" signing and authentication that are off until
configured, database-log retention. The Dream schedule examples in `architecture.md` are
corrected by docs PR #936 and are not touched here.

### Design decisions

- **Redact and re-seal, not a content-digest canonical form.** A v6 form signing digests of
  the payload would let anyone with DB access redact a row without the key. Re-sealing needs
  the key, keeps every redacted row verifiable with the existing v5 verifier (no new
  version), and the marker — with the original HMAC — sits inside the new signature.
  Re-sealing is preceded by verification, which is what keeps it from laundering.
- **The queue is flushed, not just watched.** The 1-hour erased-user rewrite stays as the
  backstop for work still unwinding, but the erasure itself writes and redacts what was
  queued, so the response counts it.
- **Admin auditing by exclusion.** An allow-list of admin paths would silently miss the
  next admin endpoint; the data-plane exclusion list is short and stable.
- **Log retention off by default.** Starting to delete history on the first boot after an
  upgrade is the surprise the EDDI 5 conversation-retention hold exists to prevent.
- **Dream conflicts lose to the user.** On a CAS miss the newer write wins and the
  consolidation is skipped or the original kept; a duplicate is reconciled next cycle,
  a lost fact is not recoverable.

### Live verification

Stock `labsai/eddi:6.5.0` vs a jar from this branch, MongoDB and PostgreSQL, mock LLM: a
conversation containing an SSN, erased while its second turn was still queued. Stock: 6 of 6
rows still contain the SSN, `complete: true`. Branch: 0 of 6, `auditEntriesRedacted: 6`,
`/auditstore/verify` VALID 6/6, chain INTACT. Evidence:
`scratchpad/evidence/fix-gdpr-audit-memory.md` (pasted into the PR).

### Out of scope

Audit chain allocation across replicas (P11), the watchdog / GDPR `stopInFlightWork`
reachability (P7), MCP `isError` (#937), the Manager's GDPR cache and a Manager tile for
`auditEntriesRedacted` (#940), the PostgreSQL retention hold (#943).

**Files:** [`AuditLedgerService.java`](../../src/main/java/ai/labs/eddi/engine/audit/AuditLedgerService.java),
[`IAuditStore.java`](../../src/main/java/ai/labs/eddi/engine/audit/IAuditStore.java),
[`AdminActionAuditFilter.java`](../../src/main/java/ai/labs/eddi/engine/audit/rest/AdminActionAuditFilter.java),
[`GdprComplianceService.java`](../../src/main/java/ai/labs/eddi/engine/gdpr/GdprComplianceService.java),
[`DatabaseLogRetention.java`](../../src/main/java/ai/labs/eddi/engine/runtime/DatabaseLogRetention.java),
[`DreamService.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/DreamService.java),
[`UserMemoryTool.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/UserMemoryTool.java),
[`compliance-data-flow.md`](../compliance-data-flow.md), [`audit-ledger.md`](../audit-ledger.md),
[`gdpr-compliance.md`](../gdpr-compliance.md), [`hipaa-compliance.md`](../hipaa-compliance.md)

```decision-log
| 2026-10-03 | GDPR erasure redacts audit content and re-signs only rows that verified; rows and sequences are kept | Erasure left verbatim PII in audit input/output/llmDetail/toolCalls and reported complete:true | A v6 canonical form over content digests (redactable without the key); deleting rows (breaks the chain); pseudonymising only (the old behaviour, kept as eddi.audit.erasure-mode=pseudonymize) |
| 2026-10-03 | Administrative REST actions are audited by excluding the data plane, not by listing admin paths | "Every API call is audited" was false; no actor was on record for config, deploy, vault, backup or GDPR actions | An allow-list of admin paths, which silently misses the next endpoint; auditing reads (volume, and the proxy access log already has them) |
| 2026-10-03 | Dream deletes/overwrites are compare-and-set on updatedAt; on a miss the user's newer write wins | Dream deleted or overwrote facts written during its LLM call | A version counter field (schema change on both backends for the same guarantee) |
```

```regression-note
| 2026-10-03 | GDPR erasure kept SSNs in 6/6 audit rows and reported complete:true; fast erasures reported auditEntriesPseudonymized: 0 | Erasure only rewrote userId, store-side, while entries were still in the write queue | AuditLedgerService.eraseUser flushes, redacts and re-seals; IAuditStore.redactEntry | fix/gdpr-audit-memory |
```
