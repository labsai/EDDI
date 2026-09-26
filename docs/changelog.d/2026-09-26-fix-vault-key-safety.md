## 🔐 fix(vault): key-material races, re-runnable KEK rotation, independent audit key (2026-09-26)

**Repo:** EDDI (`fix/vault-key-safety`)

### What changed and why

Findings from the 2026-09-25 code review, each with a regression test.

- **H6a — salt race and legacy fallback.** `VaultSaltManager` created the per-deployment salt with an
  unconditional upsert, so two replicas booting against an empty database each kept their own salt and the
  loser's DEKs became unreadable at its next restart; a transient read error fell back to the legacy salt even
  on deployments that already had a random one. The salt is now written with
  `ISecretPersistence.putMetaValueIfAbsent` (Mongo `findOneAndUpdate` + `$setOnInsert`, Postgres
  `ON CONFLICT DO NOTHING` + read-back) and the loser adopts the winner; the meta store is re-read after listing
  DEKs so a salt written in between is not mistaken for legacy; and a salt that cannot be read **fails the
  start** instead of falling back.
- **H6b — first DEK race.** A tenant's first DEK was written with `upsertDek`, so concurrent first stores
  replaced each other's generation 1 and lost a secret. It is now `insertDek`; the loser reads back and uses the
  winner's key.
- **H6c — KEK rotation.** `rotateKek` is ordered so a retry with the same two keys completes it: a legacy-salt
  migration persists its new salt as *pending* before any DEK is wrapped under it (it lived only in memory, so a
  half-way failure left DEKs unrecoverable); phase 1 accepts DEKs that open with the old **or** the new KEK (so
  the documented retry no longer fails); a KEK check value is switched to the new KEK before the first re-wrap,
  and every node checks it before wrapping a new DEK, so a replica still on the retired master key refuses
  instead of stranding a tenant; re-wraps are guarded on the IV they read (`updateDekWrapping`), followed by a
  bounded catch-up sweep; the pending salt is promoted last. A node restarted mid-migration with the new key
  opens DEKs under either KEK and wraps new ones under the announced one.
- **H6d — audit HMAC key.** New `AuditKeyring`. The signing key is `eddi.audit.hmac-key` if set, otherwise the
  key *pinned in the vault* (the master-derived key stored sealed, insert-if-absent, on first start — it
  survives KEK rotation because the vault re-wraps its DEKs), otherwise the master-derived key. New entries are
  signed as **v5** `v5:<keyId>:<hex>`; verification uses the named key, tries every known key for pre-v5 rows,
  and reports a key it does not hold as the new status `UNKNOWN_KEY` (counted with `invalid`, listed separately
  in the problem list) instead of as tampering. Retired keys go in `eddi.audit.hmac-previous-keys`.
- **L-S2 — keyed pseudonym.** v5 signs, and GDPR erasure now writes into v5 rows,
  `gdpr-erased:k1:<HMAC(key, userId)>` instead of the unsalted `sha256`. Both audit stores pseudonymise v5 rows
  per signing key first, then everything else with the caller's pseudonym as before. **Partial:** v1–v4 rows keep
  the unkeyed form (their signatures cover it) and so do the database logs, whose pseudonym comes from
  `GdprComplianceService` — owned by the GDPR branch; follow-up.
- **L-S1 — AAD.** New secrets are sealed with AES-GCM associated data naming tenant and key, and new DEK
  wrappings with tenant and generation (`a1:` prefix). Unprefixed ciphertext keeps decrypting without AAD, so
  nothing is migrated; DEK rotation re-seals secrets into the bound form and KEK rotation re-wraps DEKs into it.
  OAuth connection grants sealed through `seal()` are not bound yet — follow-up, needs a row identity in
  `seal()`. System values are bound to their name (see M1 below).
- **L-S3 — tenant reset.** `SealedDataRotationParticipant.discardAll` is called before the DEKs are deleted;
  `ConnectionGrantResealer` deletes the tenant's OAuth grants (which would otherwise fail GCM on every request
  because the next DEK reuses the dekId). A participant failure stops the reset with the DEKs in place. The
  reserved system tenant `__eddi-system` cannot be reset.
- **S1 (backend half) — value rotation reset the grant.** `store()` passed `["*"]` and a null description down
  as a whole-row write. A null grant/description now means "not supplied": both stores keep the stored values on
  update and default only on insert, which also removes the lost update against a concurrent grant edit.
- **S6 — grant precondition.** `PUT …/grant` accepts optional `expectedAllowedAgents`; the write is conditional
  in the store (Mongo `$expr`/`$setEquals`, Postgres `@>`/`<@`), and a mismatch is **409** with the current grant
  (dry runs too). Omitted, behaviour is unchanged.
- **S7 — impact analysis.** `VaultGrantChecker.checkReferences` returns `REFERENCES` / `DOES_NOT_REFERENCE` /
  `UNKNOWN`; an unreadable agent, workflow, extension config or connection makes the impact report
  `complete=false`.

### Pre-push review follow-ups

- **Lost master key (review B1).** The KEK check value that stops stale replicas cannot tell a lost key from a
  stale replica, so after a lost key every DEK creation was refused, and the documented "reset and start fresh"
  recovery could not work. New `POST /secretstore/secrets/admin/adopt-master-key?confirm=true`
  (`VaultSecretProvider.adoptCurrentMasterKey`) is the explicit operator decision. It re-announces the check with
  the configured key; resets the `__eddi-system` tenant and every `system-value:*` if they no longer open; and
  lists the tenants that need `/reset`. A vault holding no DEKs at all adopts the configured key at startup (the
  dev "restarted with another key" case). The decrypt-failure message names the endpoint. Documented under
  *Lost master key* in secrets-vault.md.
- **UNKNOWN_KEY can no longer be forged (review M1).** Every key the audit keyring pins or signs with is recorded
  as a sealed system value, `audit-key-id:<id>`. System values are now sealed bound to their name, with AAD. A v5
  row naming a key the deployment does not hold reports `UNKNOWN_KEY` only for a recorded id, and `INVALID`
  otherwise. The docs and Javadoc no longer call it "distinguishable from tampering".
- **Later rotation after an interrupted legacy migration (review m1).** `rotateKek` also tries the old key with the
  pending salt, and refuses with guidance when a DEK is still under the key before that. The wrong comment in
  `reconcileKekCheck` is corrected. Tests cover this path and the "salt promoted, pending marker not deleted" re-run.
- **Stale-replica TOCTOU (review m2).** After inserting a DEK (first DEK or DEK rotation), a node re-reads the check.
  If a rotation announced in between, it deletes the DEK it just inserted (`deleteDekIfWrappedWith`, guarded on the
  IV) before sealing anything.
- **Decrypt-failure message (review m3).** When this node's key is the vault's key, or a salt migration is pending,
  the message says to re-run `rotate-kek`. During a pending migration it no longer offers a reset.
- **Reset race (review m4).** Participants discard again after `deleteDek`.
- **Mongo grant precondition (review m5).** `$expr`/`$setEquals` replaces `$all`+`$size`, so a stored duplicate no
  longer makes every conditional edit 409.
- **Audit pin retry (review m6).** A failed pin is retried from `signingKey()` with backoff from 30 s to 10 min.
- **Hot path (review nit).** `AuditKeyring` uses a volatile fast path instead of a synchronized call per entry.

### Review follow-ups (CodeRabbit)

- **Refused legacy-salt rotation.** `rotateKek` reserved the pending salt before verifying, so a refusal (a
  wrong `oldMasterKey`, say) left it persisted and the deployment then reported an unfinished rotation at every
  boot and decrypt failure. A reservation the refused run created itself is now discarded
  (`VaultSaltManager.discardPendingSalt`); a pending salt left by an earlier interrupted run is kept.
- **Audit key-id record retry.** A failed `audit-key-id:<id>` write after a successful pin is retried from
  `signingKey()` under the pin backoff, instead of staying unrecorded until restart.
- **Impact analysis completeness.** A workflow or extension config read as `null`, and a `${vars:…}` that
  cannot be expanded, make `checkReferences` answer `UNKNOWN` rather than `DOES_NOT_REFERENCE`. The deploy gate
  (`findUngrantedReferences`) is unchanged.
- **Interrupted adoption.** `adoptCurrentMasterKey` also clears system values when the system tenant holds no
  DEK. An adoption interrupted between deleting the system DEKs and its second clear left a value no key can
  open, and a re-run skipped the cleanup, so every later pin failed.
- **Test.** `dekPersistenceFailure` now fails `insertDek` (the first DEK is never upserted) and asserts the
  write failure is the cause.

### Compatibility

- Stored data: nothing to migrate. Old ciphertext, DEK wrappings and v1–v4 audit rows keep working.
- REST: `GrantRequest` gains an optional field; `PUT …/grant` can answer 409 only when that field is sent.
  Audit verification can report `UNKNOWN_KEY`.
- New properties `eddi.audit.hmac-key` / `eddi.audit.hmac-previous-keys` (empty by default).
- Startup: an unreadable vault salt now fails the start (it used to derive a possibly wrong KEK).
- **After any master-key change, DEK creation is refused** until a KEK rotation runs, or, if the old key is lost,
  until an admin calls `adopt-master-key`. Previously a node silently wrapped new DEKs under whatever key it had.
- New REST operation `POST /secretstore/secrets/admin/adopt-master-key`; the Manager's OpenAPI snapshot is updated.

**Files:** [`VaultSaltManager.java`](../../src/main/java/ai/labs/eddi/secrets/crypto/VaultSaltManager.java),
[`VaultSecretProvider.java`](../../src/main/java/ai/labs/eddi/secrets/impl/VaultSecretProvider.java),
[`EnvelopeCrypto.java`](../../src/main/java/ai/labs/eddi/secrets/crypto/EnvelopeCrypto.java),
[`AuditKeyring.java`](../../src/main/java/ai/labs/eddi/engine/audit/AuditKeyring.java),
[`AuditHmac.java`](../../src/main/java/ai/labs/eddi/engine/audit/AuditHmac.java),
[`VaultGrantChecker.java`](../../src/main/java/ai/labs/eddi/secrets/VaultGrantChecker.java),
[`secrets-vault.md`](../secrets-vault.md), [`audit-ledger.md`](../audit-ledger.md).

```decision-log
| 2026-09-26 | Audit HMAC key pinned in the vault (sealed under a reserved system tenant's DEK) and keyed by id in v5 signatures | KEK rotation silently changed the audit key and invalidated the whole ledger | Requiring operators to configure a separate key (kept as an option, eddi.audit.hmac-key); a KEK-wrapped meta value (would need its own re-wrap step in rotateKek) |
| 2026-09-26 | Lost master key is resolved by an explicit admin call (adopt-master-key), not automatically | A stale replica and a lost key look identical from a node; guessing either way loses data | Auto-adopting on mismatch (lets a stale replica strand tenants); manual DB edit (undocumented) |
| 2026-09-26 | UNKNOWN_KEY only for key ids recorded as sealed system values | The key id in a row is attacker-writable text | Rewording docs only |
| 2026-09-26 | Unreadable vault salt fails startup | Legacy-salt fallback derives the wrong KEK on random-salt deployments | Falling back and warning; marking the vault unavailable |
```
