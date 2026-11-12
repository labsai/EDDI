# Outbound HTTP Client Consolidation — Plan and Status

> **Status:** Phases 1–2 IMPLEMENTED (labsai/EDDI#1042, labsai/EDDI#1046, both merged 2026-10-17). **Phase 4 (the Vert.x engine) IMPLEMENTED** on `feat/outbound-vertx-engine` (2026-11-05), not yet merged. Phases 3, 5 and 6 open.
> **Written:** 2026-11-03, re-grounded on `origin/main` @ `1e8ecd3` (Quarkus 3.40.1.1, Vert.x 4.5.35). This file was referenced as the plan by Phases 1 and 2 but never merged. This version rebuilds it from the merged PRs, their changelog entries ([`docs/changelog/2026-10.md`](../docs/changelog/2026-10.md)) and the code. Where the original phase list was only cited, its intent is restated rather than quoted. §4 was revised on 2026-11-05 against the code, while Phase 4 was built (§4.1 lists what changed and why).
> **Related:** [`docs/security.md`](../docs/security.md) (SSRF, DNS rebinding table), [`throughput-optimization-plan.md`](throughput-optimization-plan.md) WS8.6 (per-model HTTP clients → Phase 3), [`security-review-2026-10-17.md`](security-review-2026-10-17.md) H2 (Vert.x URL-parse differential), PQC readiness PQ-3 (TLS named groups).
> Paths are relative to `src/main/java/ai/labs/eddi/` unless they start with `src/`, `docs/` or `planning/`.

---

## 1. Goal

All outbound HTTP policy lives in one place, **`engine/httpclient/outbound/OutboundHttp`**. That covers SSRF per hop, redirects followed hop by hop under a `MethodRule`, cross-origin stripping of `SENSITIVE_HEADERS` and caller-derived headers, one whole-exchange deadline, body caps applied while reading, trace context, metrics, and the event-loop guard. Engines sit behind the one-hop `OutboundEngine` SPI. They move bytes and enforce nothing, so swapping one cannot weaken a guarantee.

Before Phase 1 there were four or five independent copies of this policy, and they had already drifted apart: `httpcalls` followed Vert.x's redirect method rules and the tools followed RFC 9110's, and a `${caller:}` value in a custom header was replayed cross-origin.

## 2. Where it stands (2026-11-05)

| Phase | Scope | State |
| --- | --- | --- |
| 1 | `OutboundHttp` facade + `OutboundEngine` SPI + `JdkOutboundEngine` (one `java.net.http` client per `Profile`), proxy, named `quarkus.tls` config; `SafeHttpClient` reduced to an adapter | **Done** — #1042 |
| 2 | Every EDDI-owned caller moved onto the facade: tools, crawler, attachments, OAuth, Slack, A2A, OpenAPI fetcher, remote sync. `SafeHttpClient` deleted | **Done** — #1046. `java.net.http.HttpClient` appears only in `JdkOutboundEngine` and `BoundedJdkHttpClient` |
| 3 | A langchain4j `HttpClient` adapter over `OutboundHttp` (`LLM_PROVIDER` profile): shared pools instead of a pool and selector thread per cached model, plus `llm-provider` keys | **Open.** LLM builders still use `modules/llm/impl/builder/BoundedJdkHttpClient`. Open PR #1236 moves MCP onto that same client as a stop-gap and says MCP will follow the builders when Phase 3 lands |
| 4 | **Vert.x engine** (`eddi.http.outbound.engine=vertx`, or per profile): per-host pool limits and a connected-peer check | **Implemented** on `feat/outbound-vertx-engine` — see §4 |
| 5 | `httpcalls` (`modules/apicalls/impl/ApiCallExecutor`) onto the facade (`APICALLS` profile, `MethodRule.VERTX_COMPAT`); `ApiCallExecutor` marks `${caller:}` headers caller-derived; `HttpClientWrapper` / `VertxHttpClient` / most of `HttpClientModule` deleted | **Open.** The `APICALLS` profile, `VERTX_COMPAT` and (since Phase 4) the `apicalls → vertx` engine default exist and are tested; nothing sends through them |
| 6 | A guard test that no new outbound client appears outside the engines, plus the docs | **Open** |

**The second stack is still live.** `httpcalls` runs on its own Vert.x `WebClient` (`engine/httpclient/bootstrap/HttpClientModule`, 636 lines; `impl/HttpClientWrapper`, 640 lines), with its own redirect handling (`setFollowRedirects(true)`), decompression and pool settings. It has been hardened separately since Phase 2:

- a connected-peer interceptor (`refusingConnectedPeers`, #1160);
- cross-origin stripping of `OutboundHttp.SENSITIVE_HEADERS` on redirect hops;
- the metadata check on every hop;
- a cap on decompressed bytes.

Every one of those is a second copy of a facade policy, which is the condition Phase 1 was written to end.

## 3. Is the Vert.x engine a good idea?

**Short answer: yes, scoped.** Build it, because Phase 5 needs it. Use it for `httpcalls` first. Do not flip the whole facade to Vert.x by default.

### 3.1 What only a Vert.x engine gives

1. **A pre-send check of the connected peer (the decisive point).** `java.net.http` exposes neither its resolver nor the socket it connected. So on the JDK path the facade can only re-check after the exchange ([`docs/security.md` → DNS rebinding](../docs/security.md#dns-rebinding)). The rebinding request has then already been sent blind, and the facade merely discards the response. The Vert.x client lets the engine refuse a metadata, link-local or private peer before a byte is written, on pooled connections and on every hop. `httpcalls` is the path where this matters most: editor-written URLs, with `ssrf-protection.enabled=false` by default. **Moving `httpcalls` onto the JDK engine in Phase 5 would therefore be a security regression.** That fact alone justifies Phase 4.
2. **A real connection pool limit.** `max-connections-per-host` was deferred in Phase 1 because the JDK client has no such knob. `httpcalls` already relies on `setMaxPoolSize` (`httpClient.maxConnectionPerRoute`). This is a limit on load, not a speed gain, and a per-host concurrency limit in the facade would give most of it on either engine (§3.3).
3. **Byte-for-byte wire behaviour for `httpcalls`.** Today's client is Vert.x. Keeping the transport removes one variable from the riskiest migration in the plan, Phase 5, while the policy moves into the facade.
4. **Fewer threads.** It reuses Quarkus's event loops instead of one JDK selector thread per client. This is negligible at four profiles. The per-model thread growth in WS8.6 comes from one client per cached model, and Phase 3 fixes that on either engine.
5. **A possible route to TLS named groups (PQC PQ-3)**, which the JDK `HttpClient` silently drops. *Unverified*: confirm that Vert.x's JDK SSL engine options carry `namedGroups` through before promising it.

### 3.2 What it costs

1. **Async-to-blocking bridge.** `OutboundEngine.Received` hands back an unread `InputStream`. Vert.x delivers a `ReadStream<Buffer>` on the event loop. The bridge needs bounded buffering with `pause()`/`resume()` backpressure, `close()` before end-of-stream that resets the request and frees the connection, deadline cancellation, and error propagation. The JDK engine got all of this for free, and it is where the bugs will be.
2. **Two engines means two test matrices.** Every guarantee the facade promises must be shown engine-neutral. `OutboundHttpServerTest` (real sockets) has to run against both engines, not just the scripted one.
3. **The URL-parse differential (H2).** Vert.x `requestAbs` parses with `java.net.URL`, and the guards use `java.net.URI`. The engine must build `RequestOptions` from the already-validated `URI`'s host, port, scheme and raw path, and must never pass the absolute string.
4. **Decompression.** The JDK path never decompresses. Vert.x does it transparently when enabled. Either disable it on the facade engine (`setDecompressionSupported(false)`, which leaves `Accept-Encoding` to callers), or count decompressed bytes against `BodyMode`, as `httpcalls` does today. Pick one; never neither.
5. **Upgrade churn.** Vert.x 5 changes the core client's `Future`/request API. Quarkus is expected to move to it in a major release. Build on the **core `HttpClient`, not `WebClient`**. The facade already does everything `WebClient` adds (redirects, codecs), and the core API is the smaller surface to port.
6. **Blocking callers only.** The facade blocks and refuses event-loop threads, and the engine's callbacks run on the event loop. Bridge with completion futures and never `await` on a Vert.x thread.

### 3.3 Performance is not a reason, today

The WS0 baseline ([`scripts/loadtest/results/baseline-2026-10-15.md`](../scripts/loadtest/results/baseline-2026-10-15.md)) measured EDDI against a WireMock "LLM" reached over real HTTP, with a 3,000 ms delay. The results:

- **The ceiling is threads, not I/O.** Throughput flattens at ≈ 61 turns/s ≈ 200 managed platform threads / 3.25 s per turn, with `active{pool="managed"}` pinned at 199. The cure is WS2: virtual threads and admission control. A faster HTTP client does not move this number.
- **EDDI's own overhead is ≈ 250 ms per turn,** and about 23 MongoDB operations account for much of it. The HTTP client's share of that is small, and it is not where the plan's optimisation work sits.
- **Vert.x's efficiency is lost behind this SPI.** `OutboundEngine` is blocking by design: tasks execute synchronously (AGENTS.md §4.1 rule 5), and `OutboundHttp` refuses event-loop threads. A Vert.x engine therefore pays an event-loop-to-caller handoff, and copies a pooled buffer into a heap stream, for every chunk. For SSE token streams of many small chunks, that may well cost more than the JDK client does. The event-loop model pays off only for a caller that is non-blocking end to end, which EDDI's pipeline is not meant to be.
- **Under WS2 the JDK client blocks cheaply.** `HttpClient.send` on a virtual thread parks the thread instead of pinning a platform thread.

**The one load concern that does grow:** once WS2 lifts the 200-thread cap, nothing bounds concurrent outbound connections to one host on the JDK engine. A thousand concurrent turns could mean a thousand TLS handshakes against one LLM provider or API, which is a rate-limit and ephemeral-port problem. Solve it **engine-neutrally**: add a per-host (per-profile) concurrency limit with a bounded wait to `OutboundHttp`, and fail with a clear `OutboundTimeoutException` when the wait expires. On the Vert.x engine the pool limit then sits underneath it.

**If this is ever revisited,** measure first. WS0 can run the same scenario with each engine (`eddi.http.outbound.<profile>.engine`, which Phase 4 adds), including the `stream` scenario, and compare the "EDDI overhead" column. Do not adopt either engine on a benchmark from outside EDDI.

### 3.4 Alternatives considered

| Option | Verdict |
| --- | --- |
| Phase 5 on the JDK engine, no Vert.x engine | Rejected. It loses the pre-send peer check and the per-host limit on the most exposed path |
| A JVM-wide `InetAddressResolverProvider` to pin the validated address | Already rejected on 2026-10-26: it affects every lookup in the process and loads before application classes in Quarkus |
| Leave `httpcalls` on its own stack for good | It works, but every fix keeps landing twice. That is the drift Phase 1 exists to end |
| Vert.x as the default engine for every profile | Not yet. The JDK engine is proven for `default`, `llm-provider` and `internal-peer`. Revisit once Phase 4 has run in production, since a pre-send peer check for `STRICT` tools would be a real gain |

## 4. Phase 4 — as built

1. **`VertxOutboundEngine implements OutboundEngine`** (package-private, `engine/httpclient/outbound`), on the core `io.vertx.core.http.HttpClient` from the Quarkus-managed `Vertx`. One client per profile — two for `protocol=auto`, a TLS one negotiating HTTP/2 by ALPN and a cleartext one on HTTP/1.1, so `auto` never sends an `h2c` upgrade — created on first use. Connect timeout from the profile, HTTP/1 pool size from `max-connections-per-host` (`PoolOptions.setHttp1MaxSize`), TLS from the named `quarkus.tls` configuration (`TlsConfigUtils.configure`), proxy per request. No redirects, no decompression, `TracingPolicy.IGNORE` (the facade writes trace headers itself and strips them cross-origin; Vert.x's tracer would re-add them on every hop). `RequestOptions` are built from the validated `URI`'s host, port, scheme, raw path and raw query; a `URI` with no host (a registry-based authority such as `x_y.example`) is refused instead of being re-parsed from the string (H2).
2. **The peer check is part of the hop, the rules are in the facade.** `OutboundEngine.Hop` carries an optional `PeerCheck` (`InetAddress → void`, throwing `OutboundBlockedException`). The Vert.x engine runs it once `client.request(...)` has assigned the connection and before `send` writes anything, and on refusal closes the connection (evicting it from the pool) and resets the request — a reset alone would hand an unwritten HTTP/1 connection back to the pool. The JDK engine ignores it; the facade's post-exchange recheck stays on both. The rules live in `ConnectedPeers`: `STRICT` refuses loopback, private, link-local and metadata peers (and an unreadable one), `METADATA_ONLY` refuses metadata and link-local peers; `HOPS_ONLY` trusts the first hop and is strict on redirects; `NONE` checks nothing. A request with its own `hopValidator` gets no peer check on its redirect hops — its validator decides what a hop may reach. `HttpClientModule` reads peer literals through `ConnectedPeers.parse`; its allow-list and previous-hop-class rules stay there until Phase 5 (§5 item 5).
3. **The stream bridge** (`VertxBodyStream`): a lock-guarded queue of buffers, the source paused at 256 KiB queued and resumed below 64 KiB (calls into the source run on its context), data then end or failure in order, `close()` before the end resetting the request, and a close from another thread waking a blocked reader — which `OutboundHttp`'s body-read watchdog relies on. Unit-tested against a scripted `ReadStream` (order, backpressure, failure after data, close mid-body, close waking a reader, slow consumer).
4. **Selection:** `eddi.http.outbound.engine` (default `jdk`) plus `eddi.http.outbound.<profile>.engine` for each of `default`, `llm-provider`, `apicalls`, `internal-peer`. `apicalls` defaults to `vertx` unless the global key is set. `eddi.http.outbound.<profile>.max-connections-per-host` (default 50; `apicalls` falls back to `httpClient.maxConnectionPerRoute`) fails startup when set on a `jdk` profile or below 1. The Vert.x engine is only built when some profile uses it; `ProfileRoutedEngine` sends each hop on its profile's engine. Documented in [`docs/configuration-reference.md`](../docs/configuration-reference.md#outbound-http-client).
5. **Tests:** `VertxOutboundHttpServerTest` runs all of `OutboundHttpServerTest` again on the Vert.x engine; `VertxOutboundEngineTest` (loopback) covers a refused peer that receives zero request bytes (a raw `ServerSocket` counts them), the peer check seeing the connected address, the host differential, a chunked body over the cap stopping the download, the deadline without the query in the message, proxy routing (which skips the peer check — the peer is the proxy), and pool-limit queuing; `OutboundEngineSelectionTest` covers the settings and which `PeerCheck` the facade puts on each hop.
6. **Metrics:** the facade's `eddi.http.outbound.requests` and `eddi.http.outbound.duration` gained an `engine` tag (the earlier draft of this plan assumed they had one; they did not).

### 4.1 Where the build departed from the draft

- **The peer check cannot apply through a forward proxy.** The connection's remote address is the proxy, so a `STRICT` check would refuse every request through a private-network proxy. The engine skips it for proxied requests; the post-exchange recheck still runs.
- **A caller's `hopValidator` must also lift the hop peer check.** Otherwise a request allowed to redirect to loopback (the test suites, and any caller that validates hops itself) would pass the name check and fail the address check.
- **`auto` on cleartext:** the JDK client sends an `h2c` upgrade; Vert.x with `http2ClearTextUpgrade=false` would speak HTTP/2 with prior knowledge. Neither is what `httpcalls` does today, so cleartext `auto` is HTTP/1.1 on the Vert.x engine.
- **Proxy credentials on HTTPS:** Vert.x sends Basic proxy credentials on a `CONNECT` tunnel; the JDK withholds them unless the JVM-wide switch is flipped. Documented.
- **Deferred:** pool gauges (in use / queued) for Vert.x profiles. Vert.x exposes pool state only through its metrics SPI, not on the client; add them with Phase 5, when a profile actually sends on Vert.x. The `MetricsDashboardCoverageTest` dashboards are unchanged.
- **Not done here:** mutation-checking the peer-check call and the reset-on-close (PIT is not wired for this package); the refused-peer test fails if the check is not called, and the chunked-over-cap test fails if close does not reset.

**Exit criterion:** both engines pass the same server-backed suite, and `eddi.http.outbound.engine=vertx` boots with every caller green in CI.

## 5. Phase 5 — `httpcalls` onto the facade (depends on Phase 4)

1. `ApiCallExecutor` builds an `OutboundRequest` on `Profile.APICALLS`, which brings the global SSRF flag, `VERTX_COMPAT` redirects and `BodyMode` from the existing response-size settings. Headers whose value came from `${caller:…}` go into `callerDerivedHeaders` (the Phase 1 hook, still unused).
2. Keep the per-request "no redirects when a header carries a resolved reference credential" rule as a request-level `RedirectPolicy.NEVER`.
3. Delete `IHttpClient`, `IRequest`, `IResponse`, `ICompleteListener`, `HttpClientWrapper` and `VertxHttpClient`, plus the policy half of `HttpClientModule` (peer interceptor, metadata hops, cross-origin strip). Keep only what is left of its configuration mapping, translated to `eddi.http.outbound.apicalls.*`, with the old `httpClient.*` keys as fallbacks so tuned deployments keep their values (`max-connections-per-host` already falls back).
4. Port the cookie-jar pin (#973), the redirect-strip tests and the peer tests to the facade. Check `docs/agent-configs/` httpcalls fixtures and the agent-test mocks (#1128), which stub at the `IHttpClient` seam.
5. **Bring the remaining peer rules into `ConnectedPeers`:** `httpcalls` with protection off is `METADATA_ONLY` on the URL but also enforces, on redirect hops, "no step into a more private address class than the previous hop connected to" and the `eddi.security.ssrf-protection.allowed-hosts` exemption — both at the name level (`refusingPrivateHops`) and at the address level (`refusingConnectedPeers`). The `PeerCheck` therefore needs the previous hop's connected class and the allow-list, which today's `Hop` does not carry: add the previous peer to the hop (or let the engine report the connected address back in `Received`) when this phase lands.

## 6. Phase 3 and Phase 6 (independent of 4)

- **Phase 3** can run before or in parallel with Phase 4. It is the throughput item (WS8.6). It needs `BodyMode.stream` for SSE token streaming and must keep the per-request endpoint revalidation that `BoundedJdkHttpClient` does today. MCP follows through the `httpClientBuilder` hook (#1236).
- **Phase 6**: an `OutboundClientGuardTest` that fails when `java.net.http.HttpClient`, `io.vertx.*.HttpClient` or `WebClient` is constructed outside the engines and the documented exceptions (SDK-transport LLM providers, vector stores). Then update the outbound section of [`docs/security.md`](../docs/security.md) and the "still have their own clients" line in `AGENTS.md`.

## 7. Recommended order

**4 → 5 → 6**, with **3** whenever throughput work needs it. Phase 4 has no value until Phase 5 follows it, so plan them as one stretch of work: two PRs, back to back.
