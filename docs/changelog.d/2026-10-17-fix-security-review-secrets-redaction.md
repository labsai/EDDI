## 🔒 fix(security): editors no longer read plaintext credentials in any config store (2026-10-17)

**Repo:** EDDI (`fix/security-review-secrets-redaction`)

### What changed and why

M1 of the 2026-10-17 security review. The 2026-10-15 fix (L12) redacted plaintext
keys for non-admins in `tasks[].parameters` of LLM configurations only; editors still
read them verbatim in `a2aAgents[].apiKey`, `modelCascade` step and judge parameters,
the MCP-calls `apiKey`, a Slack integration's `botToken`/`signingSecret` (enough to
forge `/integrations/slack/*` events), RAG `embeddingParameters`/`storeParameters`,
and literal `Authorization` headers and keys in an httpcall's `request.path` and
`request.body` — current and old (`?version=`) versions alike.

- **One shared read-redactor**, `ConfigSecretRedactor` (`secrets.sanitize`), walks the
  serialized document: credential-named fields are replaced whole, header and
  query-parameter maps are judged by header/parameter name, URLs lose their userinfo
  password and credential-named query values, and `body` fields go through
  `SecretRedactionFilter`. References (`${vault:}`, `${vars:}`, `${connection:}`,
  `${caller:}`) and templates pass unchanged, also beside a scheme word
  (`Bearer ${vault:k}`). `token` matches only singular, so `maxTokens` and
  `thinkingBudgetTokens` are untouched; `httpCalls[].parameters` (tool parameter
  descriptions) and `tasks[].toolCacheScopes` (keyed by tool name) are skipped.
- `RestLlmStore`, `RestMcpCallsStore`, `RestChannelIntegrationStore`, `RestRagStore`
  and `RestApiCallsStore` apply it for every caller who is not `eddi-admin`. MCP
  `read_resource`/`read_channel_integration` reach the same REST stores through the
  loopback client, which forwards the caller, so they are covered too.
- `RestLlmStore`'s write-time warning now uses the same detection, so it warns about
  exactly what an editor cannot read back.
- `RestRagIngestion` reads the knowledge base through the new
  `RestRagStore.readRagForIngestion` (same access check, no redaction): ingestion
  builds the embedding model from that document and must not send the marker.

**Design decision:** no merge-back of the stored literal when an editor saves a
redacted document — the marker is saved and fails at the provider. Merging it back
would let an editor move the marker next to a host of their choosing (H1 with extra
steps).

**Known consequence:** an editor-run upgrade import (`UpgradeExecutor`) reads the
target through the same REST stores, so a target literal it restores is the marker.
This was already so for LLM task parameters since L12; admins are unaffected.

**Files:** [`ConfigSecretRedactor.java`](../../src/main/java/ai/labs/eddi/secrets/sanitize/ConfigSecretRedactor.java),
[`RestLlmStore.java`](../../src/main/java/ai/labs/eddi/configs/llm/rest/RestLlmStore.java),
[`RestMcpCallsStore.java`](../../src/main/java/ai/labs/eddi/configs/mcpcalls/rest/RestMcpCallsStore.java),
[`RestChannelIntegrationStore.java`](../../src/main/java/ai/labs/eddi/configs/channels/rest/RestChannelIntegrationStore.java),
[`RestRagStore.java`](../../src/main/java/ai/labs/eddi/configs/rag/rest/RestRagStore.java),
[`RestRagIngestion.java`](../../src/main/java/ai/labs/eddi/configs/rag/rest/RestRagIngestion.java),
[`RestApiCallsStore.java`](../../src/main/java/ai/labs/eddi/configs/apicalls/rest/RestApiCallsStore.java),
[`security.md`](../security.md#editors-never-read-a-plaintext-credential-back),
[`secrets-vault.md`](../secrets-vault.md#configuration-read-redaction)

**Tests:** `ConfigSecretRedactorTest` (rules, idempotence, a linear-time check on
adversarial braces), one `*ReadRedactionTest` per store; each store test was
mutation-checked by removing the redaction and watching it fail.

## 🔒 fix(security): resetting a tenant's vault requires confirm=true (2026-10-17)

**Repo:** EDDI (`fix/security-review-secrets-redaction`)

L11 of the 2026-10-17 security review. `POST /secretstore/secrets/{tenantId}/reset`
deletes every secret and DEK of a tenant and had no confirmation, unlike
`adopt-master-key`. It now requires `?confirm=true`; without it the answer is a `400`
with `{"error": "confirm=true is required", "action": …}` and nothing is deleted. The
Manager's reset dialog (which already makes the admin type the tenant id) sends the
flag, and the `adopt-master-key` response names it.

**Files:** [`IRestSecretStore.java`](../../src/main/java/ai/labs/eddi/secrets/rest/IRestSecretStore.java),
[`RestSecretStore.java`](../../src/main/java/ai/labs/eddi/secrets/rest/RestSecretStore.java),
[`secrets.ts`](../../ui/manager/src/lib/api/secrets.ts),
[`secrets-vault.md`](../secrets-vault.md#lost-master-key)

## 🔒 fix(security): name the vault secrets that may still be sent anywhere (2026-10-17)

**Repo:** EDDI (`fix/security-review-secrets-redaction`)

The residual of H1 in the 2026-10-17 security review: PR #1079 makes the setup wizard
set `allowedHosts` on every key it vaults from now on, but every secret vaulted before
it keeps an open destination, so an editor can still exfiltrate it by pointing a copy of
the LLM configuration at their own host.

- New `UnrestrictedSecretsCheck` (next to `VaultStartupBanner`): when the vault is
  enabled it scans, on a virtual thread after boot, the current version of every LLM,
  MCP-calls, RAG, httpcalls and channel configuration for `${vault:…}` references, reads
  each referenced secret's metadata, and logs **one** `[SECURITY]` WARN naming every
  secret without `allowedHosts` (`tenant/key`), the configurations that reference it,
  and the `PUT /secretstore/secrets/{tenantId}/{keyName}/grant` call that narrows it.
- `GET /secretstore/secrets/{tenantId}` (admin-only) now returns `SecretListing` entries:
  the same metadata fields as before plus `unrestrictedDestination` (live: `allowedHosts`
  empty) and `referencedBy` (from the last scan; absent before it).

**Design decision:** no automatic migration. Only the admin knows which host a key
belongs to; a wrong guess would break a working agent. `AgentSetupService` and
`VaultSecretProvider` are untouched (PR #1079 changes them).

**Not covered:** references reached only through `${vars:…}` or `${connection:…}`, and
old configuration versions that are still deployed. No Manager warning state yet (UI).

**Files:** [`UnrestrictedSecretsCheck.java`](../../src/main/java/ai/labs/eddi/secrets/UnrestrictedSecretsCheck.java),
[`SecretListing.java`](../../src/main/java/ai/labs/eddi/secrets/rest/SecretListing.java),
[`RestSecretStore.java`](../../src/main/java/ai/labs/eddi/secrets/rest/RestSecretStore.java),
[`secrets-vault.md`](../secrets-vault.md#destination-allow-list-allowedhosts)

## 🔒 fix(security): the free-text redaction filter knows more provider key shapes (2026-10-17)

**Repo:** EDDI (`fix/security-review-secrets-redaction`)

L6 of the 2026-10-17 security review. `SecretRedactionFilter` — which backs logs, dead
letters, the audit scrub, HITL approval cards and now the config read-redaction —
recognised only `sk-`, `sk-ant-`, `Bearer` and named fields. Patterns **added** (no
existing rule changed): Google `AIza…`, xAI `xai-…`, Groq `gsk_…`, GitHub `gh[pousr]_…`
and `github_pat_…`, Slack `xox[baprse]-…`, AWS `AKIA…`/`ASIA…` (exactly 16, not inside
a longer run), bare JWTs, the credential after `Authorization: Basic|Token|Digest`,
`…Subscription-Key:` headers, and the userinfo password of `mongodb(+srv)`, `postgres(ql)`,
`mysql`, `redis(s)` and `amqp(s)` connection strings. (`api-key:`/`x-api-key:` were
already matched by the API-key name rule.)

Every pattern is a literal prefix plus one possessive or bounded character class that
admits no quote, backslash or brace, so a match cannot cross a JSON string or eat a
`${vault:…}` reference. The JWT rule is the one that can fail after consuming a long run,
so a lookbehind admits a start only where a token run begins — without it `eyJ` repeated
60,000 times is quadratic (the mutation check timed out at 30 s).

**Files:** [`SecretRedactionFilter.java`](../../src/main/java/ai/labs/eddi/secrets/sanitize/SecretRedactionFilter.java),
[`SecretRedactionFilterProviderShapesTest.java`](../../src/test/java/ai/labs/eddi/secrets/sanitize/SecretRedactionFilterProviderShapesTest.java),
[`secrets-vault.md`](../secrets-vault.md#log-redaction)

## 🔒 fix(security): legacy no-AAD vault rows are re-sealed bound on first read (2026-10-17)

**Repo:** EDDI (`fix/security-review-secrets-redaction`)

L10 of the 2026-10-17 security review. `VaultSecretProvider.decryptSecretWithFallback`
kept a permanent no-AAD fallback for rows written before row binding, so with database
write access the ciphertexts of two such rows could be swapped and each still decrypted.
Only a DEK rotation retired the fallback, and a deployment that never rotates kept it
forever.

- `resolve()` now notices when a row opened only through the legacy path and re-seals
  it bound (`tenantId|keyName|dekId` AAD) under the **same** DEK generation, through the
  new `ISecretPersistence.resealSecretInPlace` (Mongo and Postgres). It rewrites only the
  ciphertext and IV — not `dekId`, not `lastRotatedAt` — and is guarded on the **IV** the
  row was read with: a dekId guard cannot see a concurrent `store` under the same
  generation and would write the old value back over the new one. A lost guard, a
  failure or an implementation without the method (the interface default writes nothing)
  leaves the row legacy and readable; the read never fails because of it. Logged once
  per key per node.
- `openSealedData` (OAuth grants and other participant-owned sealed data) cannot write
  back — the provider does not own those rows — so it logs once per tenant that
  `POST /secretstore/secrets/{tenantId}/rotate-dek` re-seals them bound.

**Files:** [`VaultSecretProvider.java`](../../src/main/java/ai/labs/eddi/secrets/impl/VaultSecretProvider.java),
[`ISecretPersistence.java`](../../src/main/java/ai/labs/eddi/secrets/persistence/ISecretPersistence.java),
[`MongoSecretPersistence.java`](../../src/main/java/ai/labs/eddi/secrets/persistence/MongoSecretPersistence.java),
[`PostgresSecretPersistence.java`](../../src/main/java/ai/labs/eddi/secrets/persistence/PostgresSecretPersistence.java),
[`secrets-vault.md`](../secrets-vault.md#encryption)

**Tests:** `VaultLegacySealingRetirementTest` (re-seal on read, swap refused afterwards,
a store racing the write-back wins, a failing write-back does not fail the read) and
guard tests in the Mongo and Postgres persistence unit tests; mutation-checked.

## 🔒 fix(security): the read-redaction marker is never stored; upgrade and sync read targets unredacted (2026-10-17)

**Repo:** EDDI (`fix/security-review-secrets-redaction`)

Follow-up to the M1 read-redaction above, so it can never turn into stored corruption.

- **Writes refuse the marker.** New `RedactionMarkerGuard` (`configs/rest`): every create,
  update, JSON Patch and duplicate of the LLM, MCP-calls, channel, RAG and httpcalls
  stores, `RestImportService.createResourceDirect` and `UpgradeExecutor.createExtension`
  answer `400 {"error": "bad_request", "message": …, "fields": […]}` when any string in the
  document contains `<redacted: `, for administrators too. Before this, an editor who
  opened a configuration in the Manager and saved it replaced the stored key with the
  marker, and the agent broke at its next deploy.
- **One recognisable marker.** The in-string marker `ConfigSecretRedactor` writes into
  URLs, headers and bodies is now `<redacted: credential>` instead of the filter's
  generic `<REDACTED>`, which a prompt or output template may legitimately contain. Both
  markers share the `<redacted: ` prefix the guard looks for.
- **Unredacted internal reads.** `RestLlmStore`, `RestMcpCallsStore`, `RestApiCallsStore`
  and `RestRagStore` expose `readUnredacted` (same access check; `readRagForIngestion`
  became `readUnredacted`). `StructuralMatcher` reads upgrade/sync targets through it
  instead of the loopback REST client, so an editor-run upgrade neither diffs against the
  marker nor writes it: the export's `${vault:REDACTED}` placeholder is replaced by the
  target's own key.
- A sync **source** read from a peer through a non-admin credential carries the peer's
  marker; such resources are now refused rather than stored.

**Tests:** a `*RedactionMarkerWriteTest` per store, a marker case in
`RestImportServiceCallerTokenTest`, and `EditorUpgradeKeepsTargetCredentialTest` (an
editor-run upgrade of an agent whose LLM config holds a plaintext key keeps it and is
not refused); each mutation-checked.

**Files:** [`RedactionMarkerGuard.java`](../../src/main/java/ai/labs/eddi/configs/rest/RedactionMarkerGuard.java),
[`ConfigSecretRedactor.java`](../../src/main/java/ai/labs/eddi/secrets/sanitize/ConfigSecretRedactor.java),
[`StructuralMatcher.java`](../../src/main/java/ai/labs/eddi/backup/impl/StructuralMatcher.java),
[`UpgradeExecutor.java`](../../src/main/java/ai/labs/eddi/backup/impl/UpgradeExecutor.java),
[`RestImportService.java`](../../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java),
[`security.md`](../security.md#editors-never-read-a-plaintext-credential-back)
