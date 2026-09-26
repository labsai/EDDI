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

- **Keyed, tenant-bound secret checksum.** The plain unsalted `SHA-256(plaintext)`
  stored beside each secret is replaced by an HMAC keyed from a KEK-derived key over
  `tenantId + NUL + plaintext` ([`VaultChecksum`](../../src/main/java/ai/labs/eddi/secrets/crypto/VaultChecksum.java)),
  so it can no longer be brute-forced offline nor used to link equal values across
  rows/tenants. Versioned (`h1:` prefix) with a legacy bare-SHA-256 fallback, so
  existing rows keep verifying and migrate on next write. The checksum is no longer
  returned over REST. De-duplication and value-match now go through
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
  logs an unsigned prior entry at ERROR under `requirePeerVerification`.

- **Global variables that resolve to a secret are admin-only to write.** Because
  agent-secret grants are checked at deploy time, a non-admin editor could otherwise
  redirect a deployed agent's `${vars:key}` at an ungranted secret by editing the
  variable afterward. Storing a global variable whose value contains a `${vault:…}`,
  `${eddivault:…}` or `${connection:…}` reference now requires the `eddi-admin` role.

- **Sensitive-data leak fixes.** The export scrubber no longer lets a vault reference
  exempt a plaintext secret sitting beside it in the same value; Slack event/follow-up
  logging records message length only at INFO (full preview at DEBUG); and the
  pipeline task-error OpenTelemetry span carries the redacted audit summary and only
  the exception type, never the raw exception message.

### Migration / back-compat notes

- **Checksum:** legacy bare-SHA-256 rows keep verifying; new writes are keyed. KEK
  rotation re-derives the checksum key, so de-dup/value-match on pre-rotation keyed
  entries may miss (at worst an extra vault entry) — no data loss.
- **GCM AAD:** existing (no-AAD) rows decrypt via fallback; DEK rotation rebinds AAD
  to the new generation. The grant list is intentionally not part of the AAD so grant
  edits (which do not re-encrypt) keep values readable.

### Not done in this pass

- **Audit HMAC key-id + per-deployment salt** (LOW): deferred. The `v4:` HMAC string
  format is pinned by tests and a per-entry key id would need an `AuditEntry` schema
  field; the most acute exposure (the publicly-derivable demo-key-derived HMAC key) is
  already removed by the startup master-key strength gate above. Recommended follow-up:
  encode a key id into the stored HMAC string and derive the key with a per-deployment
  random salt stored in the vault-meta store, keeping the fixed-salt key as the legacy
  verification key.

```regression-note
| 2026-09-26 | scope:secret checksum was a brute-forceable unsalted SHA-256 exposed over REST | plain digest of low-entropy user secrets | keyed tenant-bound HMAC checksum, never returned over REST | fix/security-secrets-crypto |
```
