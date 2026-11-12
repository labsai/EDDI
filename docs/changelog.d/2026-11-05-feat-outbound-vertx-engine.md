## ✨ feat(outbound): a Vert.x engine for OutboundHttp, with a pre-send connected-peer check (2026-11-05)

**Repo:** EDDI (`feat/outbound-vertx-engine`)

Phase 4 of [`planning/http-client-consolidation-plan.md`](../../planning/http-client-consolidation-plan.md), which this branch also adds — it was referenced by Phases 1 and 2 but never merged.

### What changed and why

On the JDK engine the facade can only re-check a target **after** the exchange, so a DNS-rebinding request has already been sent blind. Moving `httpcalls` (Phase 5) onto that engine would lose the pre-send check its own Vert.x client has today. This adds the engine Phase 5 needs.

- **`VertxOutboundEngine`** (`engine/httpclient/outbound`) — the core Vert.x `HttpClient` of the Quarkus `Vertx`, one client per profile (TLS and cleartext separately for `protocol=auto`). No redirects, no decompression, no Vert.x tracing (the facade writes and strips trace headers). The request target is built from the validated `URI`'s parts; a `URI` with no host is refused rather than re-parsed (security review H2).
- **`OutboundEngine.Hop.peerCheck`** — run on the address the connection reached, after it is assigned and before a byte is written. It runs on every request, so a pooled connection is judged each time it is handed out. A refused connection is closed, not just reset: resetting a request with nothing written yet hands an HTTP/1 connection back to the Vert.x pool, still open to the refused address until the keep-alive timeout, and the close is what evicts it. The JDK engine ignores it; the post-exchange recheck stays on both. Skipped through a forward proxy, whose peer is the proxy.
- **`ConnectedPeers`** — the address rules, once: `STRICT` refuses loopback/private/link-local/metadata peers, `METADATA_ONLY` metadata/link-local ones, `HOPS_ONLY` only on redirect hops. `HttpClientModule` parses peers through it.
- **`VertxBodyStream`** — the event-loop-to-blocking bridge: bounded queue (pause at 256 KiB, resume below 64 KiB), ordered end/failure, reset on early close, and a close that wakes a blocked reader.
- **Selection** — `eddi.http.outbound.engine` now takes `jdk` or `vertx`; new per-profile `eddi.http.outbound.<profile>.engine` and `…max-connections-per-host` (fails startup on a `jdk` profile). `apicalls` defaults to `vertx` — no caller sends through it until Phase 5, so nothing changes at runtime today. `ProfileRoutedEngine` dispatches; the Vert.x engine is built only when a profile uses it.
- **Metrics** — `eddi.http.outbound.requests` and `eddi.http.outbound.duration` gain an `engine` tag.
- **Docs** — `configuration-reference.md` (new keys), `security.md` (DNS rebinding table), `application.properties` comments.

### Tests

`VertxOutboundHttpServerTest` reruns the whole `OutboundHttpServerTest` suite on Vert.x; `VertxOutboundEngineTest` (loopback: refused peer receives zero bytes, host differential, cap mid-stream, deadline, proxy, pool-limit queuing); `VertxOutboundEnginePeerRefusalTest` (mocks: a refused connection is closed before the reset and nothing is sent, a second request on the same pooled connection is checked and refused again, an allowed peer's connection stays open); `VertxOutboundEnginePoolIT` (loopback: two refused requests to one origin, pool limit 1, open two connections, each closed by the client with zero bytes received); `VertxBodyStreamTest`; `OutboundEngineSelectionTest` (settings, and which peer check each hop carries).

### Design decisions

- The peer check is a callback on the hop, so the rules sit in the facade and an engine only says where it connected.
- A request with its own `hopValidator` gets no peer check on redirect hops: its validator decides what a hop may reach.
- Cleartext `auto` is HTTP/1.1 on Vert.x — neither JDK-style `h2c` upgrade nor prior-knowledge HTTP/2.

### What's next

Phase 5 (`httpcalls` onto the facade). It needs the previous hop's connected class and the allow-list in the peer check (plan §5 item 5), and pool gauges for Vert.x profiles.
