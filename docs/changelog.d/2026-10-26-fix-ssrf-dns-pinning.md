## 🔒 fix(security): close the DNS-rebinding gap between the SSRF check and the connection (2026-10-26)

**Repo:** EDDI (`fix/ssrf-dns-pinning`)

### What changed and why

A repository security scan flagged three paths where the SSRF check resolves a host and the HTTP client then resolves it again to connect: `OutboundHttp` (JDK client), the Vert.x httpcalls client, and cached MCP transports. Both lookups normally hit the JVM's positive DNS cache and agree; a rebinding name aims for the moment that cache entry expires between them. This was recorded as an accepted risk in `docs/architecture.md`. None of the clients can be handed the validated addresses (the JDK `HttpClient` has no resolver hook, and an IP-literal URL breaks SNI and certificate checks), so each path now closes the gap with what it can see:

- **httpcalls (Vert.x):** a web-client interceptor checks the address the connection actually reached in the `SEND_REQUEST` phase, before a byte is written, on every request, pooled connection and redirect hop. A metadata or link-local peer is refused always; with `eddi.security.ssrf-protection.enabled=true` a private peer is refused unless the host is allow-listed. This is a real pin of the decision to the connection.
- **`OutboundHttp`:** each hop's SSRF check runs again once the response headers are in. The connection's own lookup is what the cache now holds, so a target that turned private (or into the metadata service) has its response discarded and closed before it reaches the caller or a redirect. A startup warning names `networkaddress.cache.ttl=0`, which would defeat it.
- **MCP:** the server URL is validated on a tool-cache hit too, not only on a fresh discovery, and again inside the transport's per-request header hook, so a cached transport never sends to a URL that no longer passes.

What remains (written up in [security.md → DNS rebinding](../security.md#dns-rebinding)): on the `OutboundHttp` path the request itself has already been sent when the second check refuses it, so this blocks reading the response, not a blind request; an exchange slower than the cache lifetime defeats the second look; MCP keeps the pre-connect window. A full pin of the JDK path needs a resolver hook the JDK does not offer, or one `HttpClient` per pinned host with explicit SNI, which is a larger change.

**Files:** [`HttpClientModule.java`](../../src/main/java/ai/labs/eddi/engine/httpclient/bootstrap/HttpClientModule.java), [`OutboundHttp.java`](../../src/main/java/ai/labs/eddi/engine/httpclient/outbound/OutboundHttp.java), [`McpToolProviderManager.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/McpToolProviderManager.java), [`UrlValidationUtils.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/UrlValidationUtils.java), [`security.md`](../security.md), [`architecture.md`](../architecture.md). Tests: `HttpClientModuleConnectedPeerTest`, `OutboundHttpTest$Rebinding`, `McpTransportRevalidationTest`, `McpToolProviderManagerDiscoveryTest#cacheHitIsRevalidated`.

```decision-log
| 2026-10-26 | DNS rebinding is closed per client: Vert.x checks the connected peer before sending, OutboundHttp re-checks after the exchange, MCP re-validates per request | Found by a repository security scan; previously an accepted risk | A JVM-wide InetAddressResolverProvider (affects every lookup in the process, loaded before app classes in Quarkus); IP-literal URLs with a Host header (breaks SNI and certificate checks) |
```
