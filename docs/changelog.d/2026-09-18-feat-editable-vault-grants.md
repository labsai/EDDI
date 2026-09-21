## 🔓 feat(secrets): a vault secret's agent grant is editable without its value (2026-09-18)

**Repo:** EDDI (`feat/editable-vault-grants`) — backend and `ui/manager/` together

### The failure this comes from

On a customer deployment, API keys were moved into the vault during an upgrade, with one
key granted to exactly the agents that existed at the time. A newly deployed agent then
referenced the same key, and `VaultGrantGate` correctly refused it:

```text
Agent '0123456789abcdef01234567' v1 references vault secret(s) it is not granted: [${vault:llm-api-key}].
SecretMetadata.allowedAgents lists which agents may use a secret; widen the grant or remove the reference.
```

The message said "widen the grant". **There was no way to do that.**
`PUT /secretstore/secrets/{tenantId}/{keyName}` was the only write path that touched
`allowedAgents`, and `RestSecretStore.storeSecret` rejects a blank value with a 400 — so
widening a grant required the plaintext, which an operator does not have once a key is
vaulted. That *is* the point of vaulting it. The value had to be recovered from a
pre-migration database backup and the whole secret re-PUT.

The worse consequence is the incentive. With no way to enumerate agents cheaply, the path of
least resistance is to grant `["*"]` to everything — which is what that deployment did,
and which discards the only control limiting a secret's blast radius. The missing endpoint
did not merely inconvenience an operator; it pushed them to a wildcard grant.

### What changed — backend

**`PUT /secretstore/secrets/{tenantId}/{keyName}/grant`**, body `{allowedAgents, description?}`.
`PUT` on a sub-resource rather than `PATCH` on the secret: the body replaces the grant
wholesale, which is idempotent, and the sub-resource is what makes the value structurally
unreachable — `GrantRequest` has no field that could carry it. `?dryRun=true` returns what the
change *would* do without writing, which is what lets a UI warn before the operator commits
rather than after. Same `@RolesAllowed("eddi-admin")` as every neighbouring endpoint.

**Plaintext is kept out by signature, not by convention.** Three new members, none of which
takes a value:

- `ISecretProvider.updateGrant(reference, allowedAgents, description)` — no plaintext
  parameter, so it cannot read, re-encrypt or re-write the value even by mistake. It never
  calls `activeDek()`, so a grant edit does not need the tenant's key at all.
- `ISecretPersistence.updateSecretGrant(tenantId, keyName, allowedAgents, description)` — the
  Mongo `$set` and the Postgres `SET` list name **exactly two fields**. `encryptedValue`, `iv`,
  `dekId` and `checksum` are absent from both, and neither upserts: a grant for a key that does
  not exist updates no rows and surfaces as a 404 rather than leaving a valueless entry behind.
- `SecretMetadata.canonicalGrant` / `grantsAllAgents` / `WILDCARD_AGENT` — one definition of
  what "everyone" means, which `VaultGrantChecker.WILDCARD` now aliases instead of repeating.

**A grant edit is not a rotation.** `createdAt` and `lastRotatedAt` are untouched — they are
not in the two-field write list — and both are echoed in the response so the operator can see
it. New counter `eddi.vault.grant.update.count`, kept separate from stores so a spike in
widening is distinguishable from a spike in rotation.

**The `SecretResolver` cache is deliberately not invalidated.** `storeSecret` has to, because
the plaintext behind a cached entry may have changed; here it cannot have. And the cache plays
no part in the grant decision — `VaultGrantChecker` calls `ISecretProvider.getMetadata`, which
reads persistence on every call — so the new grant is in force for the very next deployment
either way. Invalidating would only force a needless decrypt for every agent already using the
key. The reasoning sits in a comment at the call site.

**Tightening a grant is reported, never silently applied.** New `VaultGrantImpactAnalyzer`
answers "which deployed agents reference this secret and would not be granted it", and the
response carries them as `agentsLosingAccess` plus a `warning`. It is a separate bean from
`VaultGrantChecker` on purpose: the checker sits *underneath* `AgentFactory`, so giving it an
`IAgentFactory` of its own would close a CDI loop. It works through a new
`VaultGrantChecker.references(agentId, version, secret)` — the inverse question to
`findUngrantedReferences`, reusing the same traversal including the `${connection:…}` hop,
because "who would this break?" cannot be answered from the grant list that is being replaced.

### Design decisions

- **An omitted `allowedAgents` is a 400, not a default.** `storeSecret` defaults a missing list
  to `["*"]`, which is a reasonable convenience on a *create*. On an edit it would mean a field
  dropped from a JSON body silently opens a narrowed secret to every agent — the exact failure
  this work exists to remove. `["*"]` has to be said out loud.
- **`[]` is a 400 on this endpoint too.** Every other layer reads an empty list as
  "unrestricted", so a client that filtered its list down to nothing would otherwise open the
  secret to everyone with a 200. `["*", "someAgent"]` — which the deploy-time check already
  reads as open — is stored as `["*"]`, so a grant never reads narrower than it behaves.
  (`storeSecret` is unchanged and still stores what it is given.)
- **Resolution no longer re-upserts the whole row.** Found in review: `resolve()` recorded
  `lastAccessedAt` by writing back every field of the row it had read, so a resolve that read
  just before a grant edit silently reverted it (and could equally revert a rotation). It now
  goes through a single-field `ISecretPersistence.touchLastAccessed`.
- **The impact check looks at every deployed version**, via a new
  `IAgentFactory.getAllDeployedAgents`. The latest version can be registered but not `READY`
  while an older one is serving, and two versions can be `READY` at once; scanning only the
  latest per id missed both. The response marks the list `agentsLosingAccessScope: "this-node"`.
- **Narrowing warns rather than refuses.** The gate is deploy-time, so a deployed agent that
  loses its grant keeps running and fails its *next* deployment. Refusing the edit would be
  wrong; saying nothing would be worse. The Manager additionally requires the warning to be
  acknowledged before it will save.
- **No new "grant last changed" timestamp.** It would mean a schema addition in two persistences
  for marginal value; the change is logged at INFO with both the old and the new list instead.
  Agent IDs are not secrets.

### What changed — Manager UI

The **Allowed Agents** column is now the edit affordance (clicking it opens the editor), plus an
explicit **Access** row action. New `EditGrantDialog` (`src/components/secrets/`): an
all-agents / these-agents choice, agents added through the existing `AgentPicker` so they are
chosen from a searchable list rather than typed from memory (a raw ID can still be entered), names resolved for the chips and
the warning, and the dry-run warning with a mandatory acknowledgement. An empty specific list is
refused in the UI, because the backend reads "no entries" as "everyone" and silently turning
*I removed everyone* into *I allowed everyone* is the same bug in miniature. New
`updateSecretGrant` client function, `useUpdateSecretGrant` / `useSecretGrantImpact` hooks,
16 i18n keys across all 11 locales. Save stays disabled while the dry run for the current list is
in flight, and a failed dry run is its own warning needing an explicit "save anyway" — an
acknowledgement is tied to the answer it was given for, so a changed answer needs a new one (the
review found Save enabled throughout the check and on its failure). The description is sent only
when it changed. `openapi-operations.json` is regenerated from this branch's backend, so the new
endpoint is in the contract snapshot rather than exempted from it.

### Tests

`VaultSecretProviderGrantTest` (16) runs real AES-256-GCM over a stateful in-memory persistence,
so "the value is unchanged" is proved by decrypting it again after the edit rather than by a mock
echoing back what the test told it; one test interleaves a grant edit into the middle of a
resolve. `MongoSecretPersistenceTest` +6 and `PostgresSecretPersistenceUnitTest` +4 pin the grant write to exactly two fields and the access stamp to one.
`RestSecretStoreTest` +23 cover widen, tighten, wildcard set and cleared, 400 (omitted and empty
list)/404/503/500, dry run, the warning, and that the cache is *not* invalidated.
`VaultGrantImpactAnalyzerTest` (12) and a `references()` group in `VaultGrantCheckerTest` (7)
cover short-form, full-form, other-tenant, legacy-prefix, `${connection:…}` and `${vars:…}`
references; `AgentFactoryExtendedTest` covers `getAllDeployedAgents` on the real registry. Manager: `secrets-grants.test.tsx` (18). Every behaviour was mutation-checked — reverting
it fails a named test — after an independent review found one (`references()`) that nothing
covered.

### Files

- `src/main/java/ai/labs/eddi/secrets/` — `ISecretProvider`, `VaultGrantChecker`,
  **`VaultGrantImpactAnalyzer`** (new), `impl/VaultSecretProvider`, `model/SecretMetadata`,
  `persistence/{ISecretPersistence,MongoSecretPersistence,PostgresSecretPersistence}`,
  `rest/{IRestSecretStore,RestSecretStore}`
- `docs/secrets-vault.md` — a "Changing a grant without the secret's value" section; the old text
  told operators to "widen the grant" without saying how, which is how this happened
- `ui/manager/` — `src/pages/secrets.tsx`, `src/components/secrets/edit-grant-dialog.tsx`,
  `src/lib/api/secrets.ts`, `src/hooks/use-secrets.ts`, 11 locale files, MSW handlers,
  `src/test/mocks/openapi-operations.json` (regenerated)
- `src/main/java/ai/labs/eddi/engine/runtime/` — `IAgentFactory.getAllDeployedAgents`;
  `engine/setup/AgentSetupService` — its `vaultWarning` now points at `PUT …/grant` instead of a
  PATCH that never existed
