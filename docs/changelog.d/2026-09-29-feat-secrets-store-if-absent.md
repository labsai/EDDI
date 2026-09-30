## 🔐 feat(secrets): atomic create-if-absent in the vault SPI (2026-09-29)

**Repo:** EDDI (`feat/secrets-store-if-absent`) · closes #700

`ISecretProvider.store` is an upsert for every caller, so a read-then-write over a secret
was a TOCTOU: two callers could both see a key as absent and both write, and the later
write silently replaced the earlier. `AgentSetupService.useNamedVaultKey` narrowed that
with a read-back after the write (#699) but could not close it.

### What changed

- **`ISecretPersistence.insertSecretIfAbsent(EncryptedSecret) → boolean`** — `false` when
  a row already existed. PostgreSQL: `INSERT … ON CONFLICT (tenant_id, key_name) DO
  NOTHING`, answering from the affected-row count. MongoDB: `insertOne`, treating a
  duplicate-key error as "already there". Neither needs a migration — both backends
  already enforce a unique `(tenant, key)` (`idx_secret_tenant_key`, and the table's
  `UNIQUE (tenant_id, key_name)`).
- **`ISecretProvider.storeIfAbsent(...) → boolean`** exposes it; `VaultSecretProvider`
  seals the value exactly as `store` does (the shared step is now one private `seal`
  method) but does not read the row first — the insert is the existence check. `store`
  is unchanged and remains the explicit upsert for rotation and `RestSecretStore`. The
  SPI method has no default implementation on purpose: a default read-then-write would
  reintroduce the race under a name that promises otherwise.
- **`AgentSetupService.useNamedVaultKey`** creates through `storeIfAbsent`. Losing the
  race no longer overwrites anything: the setup reuses the winner's entry when it holds
  the same value and fails, before creating anything, when it holds a different one. The
  best-effort `verifyStoredValue` read-back is gone. `docs/secrets-vault.md` is updated.

### Not done

Callers that intentionally upsert (`AgentSigningService` key generation,
`PropertySetterTask.autoVaultSecret`, `RestSecretStore`) are unchanged — moving them
changes what a conflict means for each and is a separate decision. The checksum
reservation for `findReusableSecret` (item 4 in the issue, marked lower value) is also
left: that path self-corrects.

### Tests

`MongoSecretPersistenceTest` and `PostgresSecretPersistenceTest` (Testcontainers) gain an
`insertSecretIfAbsent` group: insert-when-absent, an existing row left byte-for-byte
alone, tenants independent, `upsertSecret` still replacing, and eight writers racing for
one key over 25 rounds with exactly one winner whose value is the stored one. With
`DO NOTHING` swapped for `DO UPDATE`, the race and the leave-alone tests fail; without
the unique index, the Mongo ones do. Both ITs now rebuild the Mongo persistence per test,
since the collection drop between tests takes the index with it.
`VaultSecretProviderGrantTest` pins that `storeIfAbsent` never reads or upserts, and
`AgentSetupVaultKeyReuseTest` covers winning, losing to the same value and losing to a
different one.
