## fix(signing): private-key cache expires; unused provisioning and rotation methods removed (2026-11-07)

**Repo:** EDDI (`fix/agent-signing-key-cache`)

### What changed and why

- **The loaded private key now expires after 60 s.** `AgentSigningService` kept a loaded agent
  signing key in a plain map for the life of the process. Only this node's own generate and delete
  paths evicted it, so a key replaced or removed in the vault any other way — on another node, or
  outside the service — was never picked up: the node kept signing with the old key, inter-agent
  self-verification against the new public key failed (the message went out unsigned), and audit
  entries carried signatures that no longer verified. The "no key" answer already expired after
  60 s; the "found" answer now does too. Both live in one Caffeine cache (`expireAfterWrite` 60 s,
  10,000 entries) on the existing test clock, replacing the two hand-rolled maps and the
  clear-everything size bound. The TTL constant is renamed `KEY_CACHE_TTL_NANOS` since it no longer
  covers only misses.
- **`generateKeyPair`, `generateKeyPairVersioned` and `rotateKey` are deleted**, with the
  eviction counter that guarded a race between a vault lookup and one of them. None had a
  production caller — `RestAgentStore.validateSecurityFlags` already said so — and
  [`architecture.md`](../architecture.md#cryptographic-agent-identity) claimed keys were
  "provisioned as part of agent creation", sending operators looking for a step that does not
  exist. The section now says what is true: nothing in EDDI provisions or rotates an agent signing
  key, the secrets API cannot write an `agent-signing-key:` name (its key-name pattern has no `:`),
  and an agent without a vault key signs nothing. `deleteKeyPair` and its version sweep stay. The
  deleted generators were the only code able to write such an entry, so making signing keys
  provisionable means bringing key generation back behind an admin operation.
- [`configuration-reference.md`](../configuration-reference.md) `eddi.audit.agent-signing-enabled`:
  the 60 s re-check now covers a replaced or removed key, not only a missing one.
- Comments that named the deleted methods now say what is true: `RestAgentStore.validateSecurityFlags`,
  `RestAgentStoreTest`, and `planning/manager-ui-handoff.md`, whose proposed "Generate Keypair" button
  now notes that it needs an admin endpoint first.
- **A vault outage now reaches signing after 60 s.** A loaded key used to outlive any vault failure;
  now, once it expires, a vault that cannot answer makes signing fail, so inter-agent messages go out
  unsigned (WARN) and audit entries carry no agent signature until the vault answers again. Nothing
  else changes: a vault error is never cached, and the vault shares the configuration database.

### Design decisions

- **Deletion over wiring an admin endpoint.** The alternative was a REST and MCP operation
  (`eddi-admin`) that calls the generators. That is a new capability surface — a route, an MCP
  tool, the operator prompt, docs — for a feature nobody has asked to provision yet; the methods
  were removed and later recovered once before (2026-05) without ever gaining a caller. The
  deletion is its own commit, so it can be reverted alone if the endpoint is wanted.
- **One cache for hits and misses** rather than a Caffeine cache beside the existing miss map:
  one TTL, one size bound, one eviction call, and `signIfKeyPresent`'s fast path is a single
  lookup.
- **Not `Cache.get(key, loader)`.** The vault read stays outside the cache's compute, as before,
  so a slow vault does not hold a map bin lock that blocks unrelated agents.

### Tests

`AgentSigningServiceTest`: a key replaced in the vault signs after the TTL and the old one no
longer does; inside the TTL the cached key signs with no second vault read; a key removed from the
vault elsewhere stops signing after the TTL; a key added for an agent just found keyless (unversioned
and rotated) signs after the TTL. The generator and rotation tests are gone; the remaining tests
provision keys by writing the vault entry directly, which is all the service reads.

**Files:** [`AgentSigningService.java`](../../src/main/java/ai/labs/eddi/configs/agents/AgentSigningService.java),
[`AgentSigningServiceTest.java`](../../src/test/java/ai/labs/eddi/configs/agents/AgentSigningServiceTest.java),
[`architecture.md`](../architecture.md), [`configuration-reference.md`](../configuration-reference.md),
[`RestAgentStore.java`](../../src/main/java/ai/labs/eddi/configs/agents/rest/RestAgentStore.java),
[`manager-ui-handoff.md`](../../planning/manager-ui-handoff.md)
