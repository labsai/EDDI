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
  Sealed values of other subsystems (OAuth grants, system values) are not bound yet — follow-up, needs a row
  identity in `seal()`.
- **L-S3 — tenant reset.** `SealedDataRotationParticipant.discardAll` is called before the DEKs are deleted;
  `ConnectionGrantResealer` deletes the tenant's OAuth grants (which would otherwise fail GCM on every request
  because the next DEK reuses the dekId). A participant failure stops the reset with the DEKs in place. The
  reserved system tenant `__eddi-system` cannot be reset.
- **S1 (backend half) — value rotation reset the grant.** `store()` passed `["*"]` and a null description down
  as a whole-row write. A null grant/description now means "not supplied": both stores keep the stored values on
  update and default only on insert, which also removes the lost update against a concurrent grant edit.
- **S6 — grant precondition.** `PUT …/grant` accepts optional `expectedAllowedAgents`; the write is conditional
  in the store (Mongo `$all`+`$size`, Postgres `@>`/`<@`), and a mismatch is **409** with the current grant
  (dry runs too). Omitted, behaviour is unchanged.
- **S7 — impact analysis.** `VaultGrantChecker.checkReferences` returns `REFERENCES` / `DOES_NOT_REFERENCE` /
  `UNKNOWN`; an unreadable agent, workflow, extension config or connection makes the impact report
  `complete=false`.

### Compatibility

- Stored data: nothing to migrate. Old ciphertext, DEK wrappings and v1–v4 audit rows keep working.
- REST: `GrantRequest` gains an optional field; `PUT …/grant` can answer 409 only when that field is sent.
  Audit verification can report `UNKNOWN_KEY`.
- New properties `eddi.audit.hmac-key` / `eddi.audit.hmac-previous-keys` (empty by default).
- Startup: an unreadable vault salt now fails the start (it used to derive a possibly wrong KEK).

**Files:** [`VaultSaltManager.java`](../../src/main/java/ai/labs/eddi/secrets/crypto/VaultSaltManager.java),
[`VaultSecretProvider.java`](../../src/main/java/ai/labs/eddi/secrets/impl/VaultSecretProvider.java),
[`EnvelopeCrypto.java`](../../src/main/java/ai/labs/eddi/secrets/crypto/EnvelopeCrypto.java),
[`AuditKeyring.java`](../../src/main/java/ai/labs/eddi/engine/audit/AuditKeyring.java),
[`AuditHmac.java`](../../src/main/java/ai/labs/eddi/engine/audit/AuditHmac.java),
[`VaultGrantChecker.java`](../../src/main/java/ai/labs/eddi/secrets/VaultGrantChecker.java),
[`secrets-vault.md`](../secrets-vault.md), [`audit-ledger.md`](../audit-ledger.md).

```decision-log
| 2026-09-26 | Audit HMAC key pinned in the vault (sealed under a reserved system tenant's DEK) and keyed by id in v5 signatures | KEK rotation silently changed the audit key and invalidated the whole ledger | Requiring operators to configure a separate key (kept as an option, eddi.audit.hmac-key); a KEK-wrapped meta value (would need its own re-wrap step in rotateKek) |
| 2026-09-26 | Unreadable vault salt fails startup | Legacy-salt fallback derives the wrong KEK on random-salt deployments | Falling back and warning; marking the vault unavailable |
```
