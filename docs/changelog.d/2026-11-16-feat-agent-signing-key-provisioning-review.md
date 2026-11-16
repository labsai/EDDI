## feat(signing): crypto inventory entry for key generation, and four review fixes (2026-11-16)

**Repo:** EDDI (`feat/agent-signing-key-provisioning`, PR #1365, review round after merging `main`)

### What changed and why

- **Crypto inventory.** `main` added `CryptoInventoryTest`, which fails on any JCA algorithm the
  inventory does not list. `AgentSigningService.generateKey` asks `KeyPairGenerator` for `Ed25519`,
  so `KeyPairGenerator` → `Ed25519` is now in `CryptoInventoryTest.ALLOWED`, and the agent identity
  row of [`cryptography.md`](../cryptography.md) says what it is for: an agent's signing key pair,
  whose private half goes straight to the secrets vault.
- **A revoked duplicate revokes its version, whatever the order.** `AgentIdentity.signingKey()`
  took the first entry of the signing version. With an active `v2` listed before a revoked `v2`, the
  save validator accepted signing while `GroupSigningGuard` (any revoked entry of that version)
  refused to sign. `signingKey()` now returns a revoked entry of that version when there is one; the
  Manager's `newestSigningKey` applies the same rule, so its "no usable key" warning agrees.
- **A revoked key keeps its Revoke button.** Deleting the private half on revoke is best effort and
  revoking again retries it (the backend already supports that, and the confirmation dialog says
  so), but the Manager hid the button once a key was revoked.
- **Key requests stay with their agent.** Generate and revoke follow the version they created only
  while the page still shows the agent they were started on; navigating to another agent meanwhile
  no longer selects a version that agent may not have.
- **No edits while a key request is in flight.** The key change writes a new version the page then
  follows; an edit made meanwhile built a draft on the version before it and hid the key change. The
  sections, `editAgent` and the fieldset are now locked while either key mutation is pending.

No new UI strings.
