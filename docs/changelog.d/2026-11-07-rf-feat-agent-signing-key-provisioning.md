## feat(signing): admins provision an agent signing key end to end; the validator checks the key is usable (2026-11-07)

**Repo:** EDDI (`feat/agent-signing-key-provisioning`, on top of `fix/agent-signing-key-cache`)

### What changed and why

Agent signing could not be switched on. Nothing wrote an `agent-signing-key:` vault entry (the
secrets API's key names allow no `:`), yet a save with `signInterAgentMessages` on succeeded as long
as `identity` held any non-blank string. From then on every group turn logged a WARN and wrote an
unsigned transcript entry while the configuration said the agent signed.

- **One generator, behind an admin endpoint.** `POST /agentstore/agents/{id}/signing-keys?version=N`
  (`@RolesAllowed("eddi-admin")`; whoever mints a key can sign as the agent) calls the new
  `AgentSigningService.generateKey`, which creates an Ed25519 key pair and stores the private key
  under `agent-signing-key:{id}:v{n}` with `storeIfAbsent`, never an upsert, skipping to the next
  version if one is taken. The endpoint appends the public key to `identity.keys` as version
  `max + 1` and saves a new, compatible agent version (200, Location = that version, body = the
  new key). A stale `version` is refused with 409 before the vault is touched; a failed config write
  takes the key back out of the vault (`discardKey`); a vault failure answers 503 and writes nothing.
  Older keys stay, so entries they signed still verify by the version they declare.
- **The validator is honest.** `RestAgentStore.validateSecurityFlags` now checks that the agent can
  sign: the version that would sign (`AgentIdentity.signingKeyVersion()`, the signer's rule) has a
  public key, it is an Ed25519 public key, and the vault key signs as it
  (`AgentSigningService.checkSigningKey`, a sign-and-verify probe that leaves the verify metrics
  alone). Each failure is a 400 that names the endpoint; a vault that cannot answer is a 503. On
  update the check runs after the edit-access check, since it asks the vault about the agent.
  Creating an agent with signing on is refused (a new agent has no key yet), and
  `requirePeerVerification` no longer demands a key of the agent's own: verifying uses the
  speakers' keys.
- **Duplicating an agent drops its identity and turns its signing off, and so does a ZIP import
  that creates a new agent** (`RestImportService.createNewAgent`). The original's keys live under
  the original's id, so the new agent could never sign with them and would have published the
  original's public keys as its own; with the honest validator, every later save of it (a workflow
  cascade included) would also have been refused. An import *merge* keeps the local agent's
  identity (see the 2026-11-08 entry: `identity` is engine-owned).
- **The crypto inventory lists `KeyPairGenerator` `Ed25519` again** (`CryptoInventoryTest`,
  [`cryptography.md`](../cryptography.md)): the base branch dropped it with the unused generators,
  and `generateKey` uses it.
- **Manager.** A "Generate signing key" button in Security & Identity (admins only; disabled with a
  reason while there are unsaved edits or an older version is open) that follows the version the key
  was saved as. The copy that said signing is "not yet available" and that the backend rejects any
  save with these flags is gone, with its confirmation dialog; a warning now appears only when
  signing is on and no key is declared. The `signMcpInvocations` toggle, a field the backend removed,
  is gone. All 11 locales.
- **Operator.** The endpoint stays off the allow-list (`tool-scopes.ts` says why); the system prompt
  says how keys are provisioned and sends the administrator to the Manager. Revision bumped.
- **Docs.** [`architecture.md`](../architecture.md#cryptographic-agent-identity) describes
  provisioning, the save-time check and rotation as they now are;
  [`configuration-reference.md`](../configuration-reference.md) corrects
  `eddi.audit.agent-signing-enabled` (provisioning writes versioned keys only, so audit entries still
  carry no agent signature) and the `eddi.a2a.signing.nonce.*` rows (they govern group-transcript
  entries; A2A requests are not signed). `AgentConfiguration` and `IRestAgentStore` Javadoc no longer
  say identities are generated on creation or that no endpoint can regenerate a key. (Superseded for
  the nonce rows by the entry "peer verification is fail-closed and binds the conversation, position
  and key version", 2026-11-08: the signer no longer registers nonces, so those two properties do
  nothing.)

### Design decisions

- Versioned keys only: the legacy unversioned `agent-signing-key:{id}` is still read and checked but
  not written, so there is one way to provision.
- No MCP tool: the plan called it optional, and an MCP tool would widen who can mint keys beyond the
  REST role gate this PR pins.
- Validity windows and verification outcomes are untouched here: enforcing `validUntilMs`, a
  fail-closed verdict per entry and binding the conversation into the signed bytes are the entry
  "peer verification is fail-closed and binds the conversation, position and key version"
  (2026-11-08); audit signatures (still on the unversioned key) are left to a later change.

### Tests

`AgentSigningServiceTest` (generator, discard, every `checkSigningKey` outcome),
`RestAgentStoreTest` (endpoint against a real signing service and in-memory vault, validator
outcomes, duplicate, admin-only gate), new `GroupSigningRoundTripTest` (provision through the REST
method, enable through a save, sign with the real `GroupSigningGuard`, verify for a receiver;
tampering and rotation), and the Manager section, page and API tests.
