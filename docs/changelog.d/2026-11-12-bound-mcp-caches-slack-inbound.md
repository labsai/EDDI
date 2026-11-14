## 🐛 fix(mcp, slack): bound the MCP client cache and Slack inbound processing (2026-11-12)

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

**Slack inbound events had no concurrency bound, and a missing `event_id` broke de-duplication.**
`SlackEventHandler` handed every event to a virtual-thread-per-task executor, which
has no limit. A burst of events, or of Slack's retries, started as many concurrent
turns as there were events. A payload without an `event_id` also failed. The
node-local dedup cache threw an NPE on the null key, so the webhook answered 400
and never processed the event. The shared (cluster) cache turned the null into the
string `"null"`, so every id-less event after the first was dropped as a
duplicate.

- Admission is now two semaphores around the existing executor.
  `eddi.slack.max-concurrent-events` (default `32`) events run at once, and
  `eddi.slack.max-queued-events` (default `256`) more may wait. The webhook
  thread only calls `tryAcquire`, so it never blocks.
- Past that limit the Events API webhook answers **503 with `Retry-After: 1`**
  instead of 200, and the handler first releases the event's dedup claim, so
  Slack's redelivery is processed rather than skipped. WARN logs are throttled to
  one every 10 s. `handleEventAsync` returns an `Admission`
  (`ACCEPTED`/`DUPLICATE`/`OVERLOADED`), and only `OVERLOADED` changes the HTTP
  answer.
- A missing or blank `event_id` skips de-duplication, and the event is processed.

**Files:** [`SlackEventHandler.java`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java),
[`SlackConfig.java`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackConfig.java),
[`RestSlackWebhook.java`](../../src/main/java/ai/labs/eddi/integrations/slack/rest/RestSlackWebhook.java),
[`SlackInboundAdmissionTest.java`](../../src/test/java/ai/labs/eddi/integrations/slack/SlackInboundAdmissionTest.java),
[`RestSlackWebhookTest.java`](../../src/test/java/ai/labs/eddi/integrations/slack/rest/RestSlackWebhookTest.java).

### Design decisions

- A per-key future, not "build, then put-if-absent and close the loser": two
  callers racing on a cold key now cost one handshake, not two.
- No new meter. The existing `eddi.mcp.discovery` counter is unchanged.
- Slack overflow is answered with a retryable status, not dropped with a WARN.
  The webhook already acknowledges an event before processing it, so a 503 costs
  nothing against Slack's 3-second window, and Slack redelivers what it got no
  2xx for. Dropping the event would lose a user's message without a trace. The
  A2A limiter answers 503 the same way.
- No dedup key is derived from `channel` + `ts` for an id-less event: Slack
  delivers a message and its `app_mention` as two events with the same `ts`.
- `docs/slack-integration.md` is not touched, because open PR #1342 rewrites it.
