## feat(signing): identity is engine-owned, keys can be revoked, and generating one no longer breaks the agent's descriptor (2026-11-08)

**Repo:** EDDI (`feat/agent-signing-key-provisioning`, review round on the same branch)

### What changed and why

A whole-feature review of agent signing found the provisioning endpoint broke the agent it
provisioned, and that the keys it wrote were not the trust anchor they looked like.

- **Generating a key keeps the descriptor in step.** `createSigningKey` wrote the new agent version
  in process and answered `201`. `DocumentDescriptorFilter` takes a POST answered `201` for a
  creation: it tried to create a second descriptor for the agent, failed on the duplicate key and
  answered 500 — with the key stored and the agent already at the new version. The descriptor stayed
  at the old version, so every later save answered 404 and a conversation on any newer deployed
  version could not start. The endpoint now moves the descriptor itself (as
  `UpgradeExecutor.bumpDescriptor` does) and answers `200` with `Location`.
- **`identity` is engine-owned.** Any `eddi-editor` could `PUT` `identity.keys[]` — plant a key
  whose private half they hold (a second `v1` placed first wins the version lookup), delete a
  revocation, or drop the identity so a permanent delete swept nothing — while only minting was
  admin-only. `RestAgentStore.updateAgent` now keeps the stored `identity` for every caller (a
  `PUT`, an import merge, a sync, the cascade, autonomy), as it already did for `dynamicOrigin`, and
  `createAgent` stores none. Keys change only through the admin endpoints.
- **New: `POST /agentstore/agents/{id}/signing-keys/{keyVersion}/revoke?version=N`**
  (`eddi-admin`). Marks every entry of that version `revoked: true` (a new field on
  `AgentPublicKey`, default `false`; a revoked key is valid at no time) in a new compatible version,
  moves the descriptor, then deletes the private key from the vault. `keyVersion` 0 removes the
  legacy `identity.publicKey` and its vault entry, and revokes an `identity.keys` entry declared as
  version 0 (malformed, but what a peer checks a v0 signature against). A negative version, which
  no vault entry can have, deletes nothing: it used to be read as the legacy key, whose private half
  the agent may still need. Already revoked: `200`, nothing written, but the private key is still
  deleted — that delete is best effort after the write, so revoking again is how a key a degraded
  vault kept is retried.
- **The save-time check covers every declared key.** With `signInterAgentMessages` on, a save is
  refused (400) when a key that is not revoked is malformed (version outside 1 to 1 000 000, a
  version declared twice, not an Ed25519 public key), or when the newest key is revoked or outside
  its `validFromMs`/`validUntilMs` window. Versions outside that range are ignored when numbering, so
  a declared `Integer.MAX_VALUE` no longer wraps the next key to v1, and `generateKey` can no longer
  overflow its search into a 503 that blamed the vault.
- **A config write whose outcome is unknown no longer discards a key that landed.** Only a refusal
  (`ResourceModifiedException`, `ResourceNotFoundException`) discards at once; after any other
  failure the agent is re-read, the key is kept and the call answers as a success if the current
  version declares it, and it is kept if the agent cannot be read.
- **Import and sync never carry signing onto an agent that cannot sign.** The target's identity is
  never replaced, so a merge import or sync that would write `signInterAgentMessages: true` onto an
  agent whose own newest key is not usable writes it off instead of being refused and rolled back,
  and says so: `X-Agent-Signing-Turned-Off: <reason>` on an import (also on a create, whose new agent
  has no key), `warnings` in the sync's `UpgradeResult` (a new, normally absent field). New
  non-endpoint `IRestAgentStore.signingKeyProblem(id)` answers the question for both. The Manager
  shows both: the import dialog stays open on its result step with the reason, and the Sync page
  lists each agent's `warnings` under the result (before, both reported a plain success while the
  agent ran unsigned).
- **A cascade keeps the store's status.** `AgentCascadeService` mapped a refused agent save to
  `FAILED`, i.e. a 500 whose message never reached the body. A 400 stays a 400, and a 503 is the new
  `UNAVAILABLE` kind (503); the message carries the store's response body, since the loopback client
  reports only the status line. The OpenAPI texts of the cascade and `updateResourceUri` name both.
- **Manager.** A **Revoke** button per key (admins only, with a confirmation), a *Revoked* badge, the
  DID shown read-only, the page test for a known non-admin that the review found missing, and MSW
  audit fixtures in the backend's `v<n>:<Base64>` form. The legacy key (v0) keeps its row and its
  Revoke once generated keys exist (generating a key keeps `identity.publicKey`, which peers trust
  for v0 signatures; the row used to disappear). The "a save with signing on is refused" warning
  also shows when the newest key is revoked, and the revoke dialog says saves are refused until a
  new key exists or signing is off. Copy in all 11 locales.
- **Operator.** The system prompt explains revocation, that `identity` cannot be edited through a
  save, the cascade's signing 400/503, and the import/sync report; revision bumped.

### Upgrading: agents already stored with these flags

Before this branch the save-time check accepted `signInterAgentMessages` or
`requirePeerVerification` whenever `identity` held any non-blank string, and nothing could put a
private key in the vault. So an agent stored with `signInterAgentMessages: true` signs nothing, and
after the upgrade **every save of it is refused (400)** — a person's save, a workflow cascade, an
autonomy change, an API sync — until an admin either generates a key (**Generate signing key** in
the Manager, or `POST /agentstore/agents/{id}/signing-keys?version={current}`) or turns the flag
off. Find them with a query on `security.signInterAgentMessages: true` in the agents collection
(or table). An import or a sync onto such an agent turns the flag off and reports it, rather than
failing. Any `identity.keys` entry a person pasted before this branch stays trusted by peers until it
is revoked: review the keys of every agent a `requirePeerVerification` agent checks, and revoke
every entry EDDI did not generate — the legacy `identity.publicKey` (v0) included, which generating
a key does not remove.

**Agents stored with `security.requirePeerVerification: true`.** Until now peer verification only
logged a failure. With group-signature verification, which builds on this change, such an agent
receives every peer entry that does not verify as a `[withheld ...]` placeholder — and since nothing
could sign before this branch, that is every entry another agent wrote. One such agent among a
group's members, moderator or facilitator also makes the group's records count only entries that
verify for every reader: the vote finds no options and records that it could not run, the
negotiation table stays empty, and the SERVICE judge and the retrospective see only signed entries —
for every member of that group. The only trace is ERROR logs. Find these agents with a query on
`security.requirePeerVerification: true`. To recover, generate a key and turn on
`signInterAgentMessages` for every agent that writes into those groups (members, moderator or
synthesizer, facilitator), or turn the flag off.

### Design decisions

- Identity is kept for **every** caller, admins included, not only for non-admins: one rule, and the
  endpoints that change keys are where the audit trail and the vault write are.
- Revocation is a flag, not a deletion of the entry: a verifier must recognise and refuse an entry
  signed under the key, which an unknown version would only report as "unknown key". The group
  verifier on this branch does not read the flag yet (that is the group-signing verification work);
  until it does, the deleted private key is what stops anything new being signed with it.
- The newest key being revoked stops signing rather than falling back to an older key, matching the
  signer's rule for a key outside its window.
- A malformed key that is revoked no longer blocks a save, so revoking is the way out for an agent
  stored with a planted entry.
- The import/sync report is a header and an `UpgradeResult` field, shown verbatim by one Manager
  component on the import dialog and the Sync page: the texts are EDDI's own and already name the
  way back, and `warnings` is a general list, not a signing flag.
- A repeated revoke retries only the private-key delete. A descriptor left behind by a lost write
  acknowledgement is not repaired here: every `PUT` shares that failure mode, and a recovery path in
  one endpoint would be a special case.

### Tests

`RestAgentStoreTest` (`IdentityIsEngineOwned`, `SigningKeyRevocation`, new `SigningKeyProvisioning`
and validator cases), `AgentPublicKeyTest`, `AgentSigningServiceTest`, `AgentCascadeServiceTest`,
`RestAgentStoreCascadeTest`, `UpgradeExecutorSyncTest`, `RestImportServiceCompatibleTest`,
`RestImportServiceRollbackAndCleanupTest` (including the first-promotion sync's `warnings`), the
Manager section, page, import-dialog, Sync-page and API tests, and a new
[`AgentSigningKeyIT`](../../src/test/java/ai/labs/eddi/integration/AgentSigningKeyIT.java) that runs
generate, save, sign-on, deploy, converse and revoke through the HTTP stack, response filters
included (CI only).
