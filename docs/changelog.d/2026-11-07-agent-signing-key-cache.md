## fix(signing): the cached agent signing key expires after 60 s (2026-11-07)

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
- [`configuration-reference.md`](../configuration-reference.md) `eddi.audit.agent-signing-enabled`:
  the 60 s re-check now covers a replaced or removed key, not only a missing one.

### Design decisions

- **One cache for hits and misses** rather than a Caffeine cache beside the existing miss map:
  one TTL, one size bound, one eviction call, and `signIfKeyPresent`'s fast path is a single
  lookup.
- **Not `Cache.get(key, loader)`.** The vault read stays outside the cache's compute, as before,
  so a slow vault does not hold a map bin lock that blocks unrelated agents.

### Tests

`AgentSigningServiceTest`: a key replaced in the vault signs after the TTL and the old one no
longer does; inside the TTL the cached key signs with no second vault read; a key removed from the
vault elsewhere stops signing after the TTL.

**Files:** [`AgentSigningService.java`](../../src/main/java/ai/labs/eddi/configs/agents/AgentSigningService.java),
[`AgentSigningServiceTest.java`](../../src/test/java/ai/labs/eddi/configs/agents/AgentSigningServiceTest.java),
[`configuration-reference.md`](../configuration-reference.md)
