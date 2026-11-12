## 🐛 fix(mcp): bound the MCP client cache and build clients outside the map lock (2026-11-12)

**Repo:** EDDI (`fix/bound-mcp-caches-slack-inbound`)

### What changed and why

**MCP client and tool caches were unbounded.** `McpToolProviderManager` kept its
MCP clients, discovered tool lists and circuit-breaker timestamps in plain
`ConcurrentHashMap`s that never evicted. Each distinct server URL + credential +
timeout combination ever used kept an open client for the life of the process.
And the client was built inside `computeIfAbsent`, so the `initialize` handshake
ran while holding the map bin: a slow or hanging server stalled lookups for
unrelated servers hashed to the same bin.

- The client cache is now a Caffeine `AsyncCache`, bounded by
  `eddi.mcp.client-cache.max-size` (default `256`) and
  `eddi.mcp.client-cache.expire-after-access-ms` (default `1800000`, 30 minutes).
  When the cache evicts a client, a removal listener closes it on Caffeine's
  executor, never on the caller's thread, and logs any close error without
  rethrowing it. The evicted key's cached tool list is dropped with it, because
  its executors are bound to the closed client.
- A client is built **outside** any lock. The first caller claims the key with an
  empty future and builds on its own thread. Concurrent callers for the same key
  wait for that future and share the one client. Callers for other keys never wait
  on it. If the build fails, the claim is released, so the next call tries again.
- When the tool cache serves a hit, it touches the client's cache entry. Cached
  executors use the client without calling `getOrCreateClient`, so without this
  touch a client in constant use would look idle.
- The tool cache and the circuit-breaker map are bounded the same way. The
  circuit-breaker entry now expires one window (60 s) after its last failure. By
  then no timestamp in it can open the circuit, so the breaker gives the same
  answers. It is deliberately not cleared on client eviction: closing an idle
  client says nothing about whether its server is healthy.
- Shutdown, secret rotation and `closeClient` remove the entries one by one and
  close the clients synchronously. A client still being built when its entry is
  dropped is closed as soon as its build finishes, instead of leaking.

**Files:** [`McpToolProviderManager.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/McpToolProviderManager.java),
[`McpClientCacheBoundsTest.java`](../../src/test/java/ai/labs/eddi/modules/llm/impl/McpClientCacheBoundsTest.java),
[`McpToolProviderManagerAdditionalTest.java`](../../src/test/java/ai/labs/eddi/modules/llm/impl/McpToolProviderManagerAdditionalTest.java)
(its reflective seeding helper follows the new field type),
[`configuration-reference.md`](../configuration-reference.md), [`mcp-client.md`](../mcp-client.md).

### Design decisions

- A per-key future, not "build, then put-if-absent and close the loser": two
  callers racing on a cold key now cost one handshake, not two.
- No new meter. The existing `eddi.mcp.discovery` counter is unchanged.
