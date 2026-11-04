## 🔒 fix(secrets): agent-findings pass over the vault, grant checks, redaction and connections (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-secrets-connections`)

### What changed and why

The 45 CodeRabbit "agent findings" filed against the secrets vault, the grant and host checks, the redaction layer and connections were each traced through the current code. Most had already been closed by the October hardening rounds; nine were still real and are fixed here.

- **Space-membership check could miss a tenant** ([`VaultGrantChecker`](../../src/main/java/ai/labs/eddi/secrets/VaultGrantChecker.java)). Tenant discovery used one scanner for `${vault:` and `${vars:`, so a malformed opener of one namespace (`${vars:a${vault:t.finance…/key}`) swallowed a real reference of the other into a single bogus tenant, while the runtime resolved the inner reference with its own rules. Vault tenants now come from a vault-only scanner and variable tenants from `GlobalVariableResolver.VARS_PATTERN`, the grammar the resolver uses (made public for this).
- **Rotation racing a delete** ([`VaultSecretProvider.store`](../../src/main/java/ai/labs/eddi/secrets/impl/VaultSecretProvider.java)). A rotation whose row vanished was turned into a create, and the create checks its endpoint runs had been skipped. The Manager's rotate call, which restates the agent grant but no host list, re-created the key sendable to any host. The rotation now fails and writes nothing.
- **Stale conditional grant edit** ([`RestSecretStore.updateGrant`](../../src/main/java/ai/labs/eddi/secrets/rest/RestSecretStore.java)). With `expectedAllowedAgents` and `allowedHosts` together, the host list was committed before the precondition was checked, so a stale request answered 409 "nothing was written" after it had changed where the key may go. The precondition is now checked before the host write. An edit landing in the moment between that check and the grant write is still refused by the conditional grant write, after the hosts. One atomic operation for both lists would need a new persistence operation on both backends.
- **Linear unresolved-reference check** ([`CredentialReferenceResolver`](../../src/main/java/ai/labs/eddi/connections/CredentialReferenceResolver.java)). The last `find()` over a `[^}]*` reference regex in the area, run on decrypted credential text, was quadratic on unterminated openers. It is replaced by an exact linear equivalent.
- **Read redaction** ([`ConfigSecretRedactor`](../../src/main/java/ai/labs/eddi/secrets/sanitize/ConfigSecretRedactor.java)). A numeric value in an embedded `customHeaders` / `customQueryParams` map is judged like a string. A JSON request body has every literal under a credential-named field masked whatever its length, because the shape scan's eight-character floor left `{"password":"hunter2"}` legible to editors. Only one whole JSON document is re-serialized, never a prefix.
- **Long non-JSON text** ([`SecretRedactionFilter`](../../src/main/java/ai/labs/eddi/secrets/sanitize/SecretRedactionFilter.java)). The 1 MiB bound that assumes a JSON carrier now applies only to text that could be JSON. Text that cannot be JSON keeps apostrophe-quoted passwords whole across an embedded double quote.
- **Export scrubbing** ([`SecretScrubber`](../../src/main/java/ai/labs/eddi/secrets/sanitize/SecretScrubber.java)). The additional query-name rule (`key`, `sig`, …) now also judges the percent-decoded name on both export branches, so `%6Bey=` no longer survives export.

Docs: [`secrets-vault.md`](../secrets-vault.md) (rotation racing a delete, the grant precondition and host lists) and [`security.md`](../security.md) (JSON body masking).

### Verdicts (45 findings)

FIXED 9 · ALREADY_FIXED 27 · ACCEPTED_RISK 6 · NEEDS_DECISION 2 · FALSE_POSITIVE 1.

- **Accepted (documented):** the connections master switch does not stop `${connection:…}` resolution ([`connections.md`](../connections.md)). Unreadable or absent grant metadata is allowed at deploy time, with the grant checked at creation instead, and a deployment racing a secret's creation is a named gap ([`secrets-vault.md`](../secrets-vault.md#what-counts-as-granted)).
- **Open:** whether an OAuth service grant should carry the connection's immutable id, so that a stale configuration minting during a delete and re-create cannot store its token under the successor. And whether GDPR erasure should report the auto-vaulted-secret step incomplete when the vault is disabled (no master key) but secret rows remain.

### Design decisions

- No runtime per-agent grant check was added. `VaultGrantChecker`'s class Javadoc records why resolution time is the wrong place, and the race findings that ask for one are accepted as documented.
- The rotation now prefers "the delete wins" over re-creating the key. A re-create would have to repeat every endpoint check (host list, deployed-agent impact) inside the provider, which knows neither the mode nor the deployments.
