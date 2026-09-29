## 🔒 security(secrets): vault master-key, checksum, crypto and reference hardening (2026-09-26)

**Repo:** EDDI (`fix/security-secrets-crypto`)

### What changed and why

A pass over verified secrets/cryptography findings in the vault and related
subsystems. Each item is a hardening; no configuration format changes for agent
authors, and existing vaults keep working.

- **Master-key strength gate at startup.** [`VaultMasterKeyStrength`](../../src/main/java/ai/labs/eddi/secrets/crypto/VaultMasterKeyStrength.java)
  rejects a weak or publicly-known vault master key (too short, too low-entropy, or a
  well-known demo/placeholder such as the `docker-compose.openwebui.yml` key).
  [`VaultSecretProvider`](../../src/main/java/ai/labs/eddi/secrets/impl/VaultSecretProvider.java)
  fails startup in production and warns in development/test, mirroring `AuthStartupGuard`.
  A production opt-out `eddi.vault.allow-weak-master-key` (default `false`, mirroring
  `eddi.security.allow-unauthenticated`) downgrades the failure to a WARN so a
  brownfield deployment already running a weak-but-functional key can boot, rotate to a
  strong key via `POST /secretstore/secrets/admin/rotate-kek`, and then remove the flag
  — rather than being wedged (the key cannot be changed without a booted vault).

- **Keyed, tenant-bound secret checksum.** The plain unsalted `SHA-256(plaintext)`
  stored beside each secret is replaced by an HMAC over `tenantId + NUL + plaintext`
  ([`VaultChecksum`](../../src/main/java/ai/labs/eddi/secrets/crypto/VaultChecksum.java))
  keyed with a **random deployment key persisted KEK-wrapped** (encrypted with the KEK
  like a DEK, never in the clear), so it can no longer be brute-forced offline nor used
  to link equal values across rows/tenants. Because the key is wrapped rather than
  KEK-derived it **survives KEK rotation** — `rotate-kek` re-wraps it with the new KEK
  alongside the DEKs, so it unwraps to the same value before and after and every `h1:`
  checksum keeps verifying and a legitimate same-value re-setup does not spuriously fail. Versioned (`h1:` prefix) with a legacy bare-SHA-256 fallback,
  so existing rows keep verifying and migrate on next write. The checksum is no longer
  returned over REST. De-duplication and value-match go through
  `ISecretProvider.matchesChecksum` (the caller no longer holds the key).

- **Ciphertext bound to its row (GCM AAD).** Secrets are now sealed with
  `tenantId|keyName|dekId` as GCM Additional Authenticated Data, so a ciphertext
  cannot be swapped onto another key/row by someone with database write access. A
  no-AAD decrypt fallback keeps pre-existing rows readable with no migration.

- **Reserved namespace for agent signing keys.** Agents' Ed25519 private keys live
  under a reserved `agent-signing-key:` key-name prefix that
  [`SecretResolver`](../../src/main/java/ai/labs/eddi/secrets/SecretResolver.java)
  refuses to resolve from a configuration or template, closing a path where an editor
  could exfiltrate an agent's own signing key via an httpcall header. The internal
  signing service still reads the key directly through the provider. Peer-verification
  logs an unsigned prior entry at ERROR under `requirePeerVerification`, and
  `docs/architecture.md` is corrected to describe peer verification as **detect-and-log,
  not a gate** (it does not yet drop/block unverified entries — see residuals).

- **Global variables that resolve to a secret are admin-only to write.** Because
  agent-secret grants are checked at deploy time, a non-admin editor could otherwise
  redirect a deployed agent's `${vars:key}` at an ungranted secret by editing the
  variable afterward. Storing a global variable whose value contains a `${vault:…}`,
  `${eddivault:…}` or `${connection:…}` reference now requires the `eddi-admin` role.

- **Sensitive-data leak fixes.** The export scrubber no longer lets a vault reference
  exempt a plaintext secret sitting beside it in the same value — the remainder is
  split on punctuation as well as whitespace (so `sk-live-…,` is still judged as a
  key), and in a credential-named field (`password`, an `Authorization` header, …)
  only an auth-scheme word such as `Bearer` and separators may sit beside the
  reference, so a short low-entropy password next to one is redacted too; Slack
  event, follow-up and group-discussion logging records message length only, at every
  level (no text preview, not even at DEBUG); and the pipeline task-error
  OpenTelemetry span carries the redacted audit summary and only the exception type,
  never the raw exception message.

### Migration / back-compat notes

- **Checksum:** legacy bare-SHA-256 rows keep verifying; new writes are keyed. The
  checksum key is a random deployment key persisted KEK-wrapped and **re-wrapped during
  KEK rotation**, so rotation does **not** invalidate stored checksums (verified by a
  KEK-rotation + same-value re-verify test). First use on an upgraded deployment lazily
  generates and wraps the key; it touches no tenant DEK, so it has no boot/store side
  effects. The key is created **only when the store confirms none exists**, through a
  new atomic `ISecretPersistence.setMetaValueIfAbsent` (Mongo `$setOnInsert` on the
  unique meta-key index, Postgres `ON CONFLICT DO NOTHING`): a failed read or a failed
  unwrap of an existing key (e.g. a KEK mismatch) now fails the operation instead of
  replacing the key, and racing nodes converge on the first key written rather than
  the last. There is no KEK-derived fallback key any more — a checksum written under
  one would be unverifiable later — and a store that keeps no metadata refuses to write
  a keyed checksum rather than use a key that would not survive a restart (legacy
  checksums still verify without the key).
- **KEK rotation:** `rotate-kek` now refuses, in production, a new master key the
  startup strength gate would reject (the `allow-weak-master-key` opt-out is for booting
  on a weak key to rotate off it, never for rotating onto one). The checksum key is
  unwrapped and re-wrapped during the verification phase, so a malformed or
  unwrappable one aborts the rotation before any DEK is written; and a failed write
  during the commit — any failure, including an unchecked store error, not only a
  `PersistenceException` — rolls the already-written DEKs back to the old KEK, so the
  vault stays readable under its configured master key and the rotation can be
  retried. Every rotation failure surfaces as `SecretProviderException`.
- **GCM AAD:** existing (no-AAD) rows decrypt via fallback; DEK rotation rebinds AAD
  to the new generation. The grant list is intentionally not part of the AAD so grant
  edits (which do not re-encrypt) keep values readable.

### Known residuals / deferred (not implemented here)

- **Audit HMAC key-id + per-deployment salt** (LOW): deferred. The `v4:` HMAC string
  format is pinned by tests and a per-entry key id would need an `AuditEntry` schema
  field; the most acute exposure (the publicly-derivable demo-key-derived HMAC key) is
  already removed by the startup master-key strength gate above. Recommended follow-up:
  encode a key id into the stored HMAC string and derive the key with a per-deployment
  random salt, keeping the fixed-salt key as the legacy verification key.
- **AAD downgrade tolerance** (LOW): the AAD decrypt path falls back to a no-AAD decrypt
  for legacy rows, so a legacy (no-AAD) ciphertext copied with its IV onto another row
  under the same DEK still decrypts there — exactly as every row did before this change.
  A per-row marker cannot close this, because it lives in the same database the
  attacker writes. The follow-up is an operator-controlled switch (configuration, not
  data) that disables the no-AAD fallback once every legacy row has been re-sealed with
  AAD — which DEK rotation already does.
- **Grant-list tampering** (LOW): `allowedAgents` is authenticated only at the
  deploy-time grant check, not bound into the ciphertext (binding it as AAD would break
  the no-re-encrypt `updateGrant`); a separate keyed MAC over the grant list is a
  future option.
- **Peer-verification hard enforcement** (#3b): still detect-and-log; dropping/blocking
  unverified entries needs a group input-path refactor. Docs now say so.

```regression-note
| 2026-09-26 | scope:secret checksum was a brute-forceable unsalted SHA-256 exposed over REST | plain digest of low-entropy user secrets | keyed tenant-bound HMAC checksum, never returned over REST | fix/security-secrets-crypto |
```
