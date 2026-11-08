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
  legacy `identity.publicKey` and its vault entry. Already revoked: `200`, nothing written.
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
  non-endpoint `IRestAgentStore.signingKeyProblem(id)` answers the question for both.
- **A cascade keeps the store's status.** `AgentCascadeService` mapped a refused agent save to
  `FAILED`, i.e. a 500 whose message never reached the body. A 400 stays a 400, and a 503 is the new
  `UNAVAILABLE` kind (503); the message carries the store's response body, since the loopback client
  reports only the status line. The OpenAPI texts of the cascade and `updateResourceUri` name both.
- **Manager.** A **Revoke** button per key (admins only, with a confirmation), a *Revoked* badge, the
  DID shown read-only, the page test for a known non-admin that the review found missing, and MSW
  audit fixtures in the backend's `v<n>:<Base64>` form. Copy in all 11 locales.
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
is revoked: review the keys of agents that use `requirePeerVerification` peers, and revoke every
entry EDDI did not generate.

### Design decisions

- Identity is kept for **every** caller, admins included, not only for non-admins: one rule, and the
  endpoints that change keys are where the audit trail and the vault write are.
- Revocation is a flag, not a deletion of the entry: a verifier must recognise and refuse an entry
  signed under the key, which an unknown version would only report as "unknown key".
- The newest key being revoked stops signing rather than falling back to an older key, matching the
  signer's rule for a key outside its window.
- A malformed key that is revoked no longer blocks a save, so revoking is the way out for an agent
  stored with a planted entry.
- The import/sync report is a header and an `UpgradeResult` field; showing them in the Manager's
  import dialog and sync page is a follow-up.

### Tests

`RestAgentStoreTest` (`IdentityIsEngineOwned`, `SigningKeyRevocation`, new `SigningKeyProvisioning`
and validator cases), `AgentPublicKeyTest`, `AgentSigningServiceTest`, `AgentCascadeServiceTest`,
`RestAgentStoreCascadeTest`, `UpgradeExecutorSyncTest`, `RestImportServiceCompatibleTest`,
`RestImportServiceRollbackAndCleanupTest`, the Manager section, page and API tests, and a new
[`AgentSigningKeyIT`](../../src/test/java/ai/labs/eddi/integration/AgentSigningKeyIT.java) that runs
generate, save, sign-on, deploy, converse and revoke through the HTTP stack, response filters
included (CI only).
