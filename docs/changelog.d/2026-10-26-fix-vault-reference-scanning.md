## 🔒 fix(secrets): linear-time reference scanning, missing secrets fail the grant check, rotation retires legacy checksums (2026-10-26)

**Repo:** EDDI (`fix/vault-reference-scanning`)

Follow-up to a repository security scan of the vault and connection code.

### What changed and why

- **Reference scanning is linear.** `find()` over the `${vault:…}`, `${connection:…}`, `${vars:…}` and tenant-reference regexes retried at every opener, and when no closing brace followed, each attempt scanned the rest of the text. A global variable value or a parameter with thousands of unterminated openers therefore cost time quadratic in its length, on every variable write, every deployment grant check and every resolved parameter. The new [`ReferenceScanner`](../../src/main/java/ai/labs/eddi/secrets/model/ReferenceScanner.java) gives the regex's answers match for match (same start, end, tenant and name, nested openers included) but remembers its searches for the next `}` and `/`, so each character is examined a constant number of times. `SecretResolver`, `VaultGrantChecker`, `SecretReference.parse` and `ConnectionReference` (`contains`, `requireSole`, `parse`) use it. `SecretReference.VAULT_PATTERN` and `ConnectionReference.CONNECTION_PATTERN` stay as the documented grammar.
- **A reference to a secret that does not exist is a grant violation.** [`VaultGrantChecker`](../../src/main/java/ai/labs/eddi/secrets/VaultGrantChecker.java) used to treat `SecretNotFoundException` like an unreadable grant and let the deployment through. The resolver does not check grants, so a secret created after the deployment and restricted to another agent then resolved for this one until the next deploy. Absence is now reported as `${vault:…} (the secret does not exist, …)`, with a suffix that keeps the gate from offering an append-grant call for it. An unreadable grant or a disabled vault still allows, as before. `VaultGrantGate`'s refusal text names the new case.
- **DEK rotation upgrades legacy checksums.** A row written before the keyed checksum still held a bare SHA-256 of the plaintext, which anyone with the row or a backup can test guesses against offline. `VaultSecretProvider.rotateDek` has the authenticated plaintext in hand, so it now writes the keyed, tenant-bound checksum in the same guarded re-seal. `updateSecretSealing` (Mongo and Postgres) persists the checksum with the ciphertext.

Docs: [`secrets-vault.md`](../secrets-vault.md) ("What counts as granted", and the checksum note under the REST API).

### Tests

- `ReferenceScannerTest`: parity with the regexes on hand-picked and fuzzed input, `matchesWhole`, `replaceAll`, and timing guards on many unterminated openers for each caller.
- `VaultGrantCheckerTest.absentSecretIsAViolation`.
- `VaultKeySafetyTest.rotationRetiresLegacyChecksums`.
- `PostgresSecretPersistenceUnitTest` follows the new parameter order.
