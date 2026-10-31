## fix(secrets): review findings on the vault grant check, redaction, connections and legacy sealing (2026-10-31)

**Repo:** EDDI (`fix/review-secrets`)

Fixes from the 2026-10 external review (CodeRabbit plus a manual code review) in `secrets/**` and `connections/**`.

### What changed and why

**Deploy-time grant check (`VaultGrantChecker`): it now reads what the runtime reads**
- A step's config URI is trimmed, as `ResourceUris.require` does at runtime, and the store is picked by an exact match on the **config URI's host**, the key `ResourceClientLibrary` reads with. Before, a URI with surrounding whitespace failed to parse and went unchecked, a step typed with the `ai.labs.langchain` alias went unscanned, and `contains()` on the step type could pick the wrong store, fail, and skip the config.
- Every string value is scanned **on its own**, not the serialized document. An unterminated `${vault:x` in one field used to run on to the next field's closing brace and swallow the real reference behind it.
- A workflow, credential-bearing extension config or `${vars:…}` that cannot be read is now a **violation** (fail closed), as an unreadable connection already was. Unreadable grant *metadata* still counts as granted, as documented.

**Redaction**
- `ConfigSecretRedactor`: a header or query map stored as a JSON string (`customHeaders`, `customQueryParams`) has its entries judged like a real `headers`/`queryParams` map. In a credential field, a value with no reference is always a literal (a scheme word alone included), and symbols or non-ASCII letters beside a reference no longer count as scaffolding.
- Query names are classified **percent-decoded** in `ConfigSecretRedactor`, `SecretScrubber` and `UriRedactor` (`%61pi_key` is `api_key`).
- `UriRedactor`'s shared header rule adds `cookie` and `signature` (substring) and `sig`, `session`, `sessionid`, `sid` (whole word).
- `SecretRedactionFilter`: a connection string with an empty username (`redis://:pw@host`) and a password containing a comma are redacted; Digest directives allow whitespace around `=`.
- Linear time: `SecretRedactionFilter`'s optional vault-reference prefix is bounded (a run of `password:${vault:` was quadratic), and `SecretScrubber`, `VaultHostAllowlist.referencesIn` and `CredentialReferenceResolver` use the linear `ReferenceScanner` instead of `[^}]*` regexes. `SecretScrubber.redactFreeText` restores its masks in one pass.

**Vault**
- `SecretResolver`: a value read while an invalidation ran is no longer left in the cache for the TTL (invalidation counter, re-checked after the put).
- `VaultHostAllowlist.requireAllowed` refuses a reference whose secret does not exist instead of skipping it: on the API-call path it runs after resolution.
- A secret's `allowedHosts` now applies through a connection: a `STATIC`/`BASIC` connection's vault references are checked against the call's target, an OAuth client secret against the token URL (`CredentialReferenceResolver.requireHostsAllowed`).
- `VaultSecretProvider.store`: a rotation is update-only (`ISecretPersistence.updateSecretIfPresent`); a row deleted mid-rotation is treated as a create and needs a grant, instead of being re-created with `allowedAgents: ["*"]`.
- Legacy unbound (no-AAD) data: every open through the fallback is counted (`eddi.vault.legacy-unbound.opened.count`), and `eddi.vault.accept-unbound-legacy-data=false` (default `true`) retires the fallback once a DEK rotation has re-sealed everything. Re-sealing a legacy secret on read also replaces its bare SHA-256 checksum with the keyed form. `decryptSecretWithFallback` (a one-line wrapper) is inlined.
- `RestSecretStore.updateGrant` evicts cached clients when the host list was written, even if the grant write after it is refused (409) or fails.
- `AutoVaultedSecrets.belongsToUser` reads the owner from the slot's second segment only; a property name carrying another user's hash no longer makes the slot theirs.

**Connections**
- `RestConnectionStore.createConnection` requires `eddi-admin` in the implementation too: the archive import (open to `eddi-editor`) called it directly. An editor's import now skips connections with a logged reason.
- The OAuth callback re-reads its claimed state row after storing the grant and discards the grant if the row is gone: a GDPR erasure that ran during the token exchange no longer leaves a live refresh token behind (`IOAuthStateStore.exists`, Mongo and Postgres).
- A `client_credentials` mint checks the connection's id before the exchange and after the write, and discards the grant if the connection was deleted or re-created under the same name meanwhile.
- `requirePrincipal` refuses a user whose principal name is the reserved `__service__`.
- Docs: the `enabled` connections switch governs account linking only; it does not stop `${connection:…}` resolving ([Connections](../connections.md)).

### Design decisions

- **Store chosen by config-URI host, not step type.** Whichever task a step runs, the document it loads is the one the config URI names, so that is what the scan reads.
- **`accept-unbound-legacy-data` defaults to `true`.** Turning the fallback off before a rotation would make never-read legacy rows unreadable; the counter tells the operator when it is safe.
- **The connections master switch was documented, not enforced on resolution.** It defaults to off, and `STATIC`/`BASIC` connections work with it off today; enforcing it would break those deployments.

### Not changed

- `VaultGrantImpactAnalyzer`: a deployment racing a restrictive secret creation is still unseen (already documented). Closing it needs a post-record re-check on the deploy side (`AgentFactory`), outside this area.
- `ConnectionRegistry` can re-cache the previous version of an updated connection for up to its one-minute TTL: the descriptor advances in `DocumentDescriptorFilter` (engine) after the store has invalidated.
- `allowedHosts` entries without a port still admit `http`; requiring https for vault-backed hosts is a policy decision.
- A Qute-looking credential value such as `{abc}` is still read as a template.
