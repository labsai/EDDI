## Outbound HTTP facade — Phase 1 of the client consolidation (2026-10-15)

**Repo:** EDDI · **Branch:** `feat/outbound-http-facade` · **Plan:** `planning/http-client-consolidation-plan.md`, Phase 1

**What changed**

- New package `ai.labs.eddi.engine.httpclient.outbound`. `OutboundHttp` is the one place outbound policy lives: the SSRF policy per request (`STRICT`, `HOPS_ONLY`, `METADATA_ONLY`, `NONE`, `FOLLOW_GLOBAL_FLAG`); redirects followed hop by hop with a `MethodRule` (`JDK_SAFE`, the RFC 9110 rule `SafeHttpClient` always used, and `VERTX_COMPAT`, which matches what the `httpcalls` Vert.x client does); cross-origin stripping of the fixed `SENSITIVE_HEADERS` **plus any header the request marks caller-derived** (a `${caller:token}` can sit in any header name); one whole-exchange deadline; redirect bodies dropped after 64 KiB; body caps enforced while reading (`BodyMode.string/bytes/file/stream/discard`, `OnOverflow.TRUNCATE|FAIL`); refusal on a Vert.x event-loop thread; four meters (`eddi.http.outbound.requests|duration|blocked|bytes`) on a new **Outbound HTTP** dashboard row.
- `OutboundEngine` is a one-hop SPI; `JdkOutboundEngine` keeps one `java.net.http` client per `Profile` (`default`, `llm-provider`, `apicalls`, `internal-peer`), with an optional forward proxy (vault-resolvable credentials, answered to proxy challenges only, failing closed when unresolved) and an optional named `quarkus.tls` configuration.
- `SafeHttpClient` is now a `@Deprecated` adapter over the facade. Its API and behaviour are unchanged: all 22 server-backed `SafeHttpClientTest`/`SafeHttpClientBodyBoundsTest` cases pass as they were, apart from one import (`CallerFrames` moved). Its unit tests of moved internals were ported: the method rule to `MethodRuleTest`, the budget and redirect-body discard to `OutboundHttpTest`, and the `withDefaultTimeout` rebuild to a request-conversion test.
- New settings, documented in [configuration-reference.md](../configuration-reference.md#outbound-http-client): `eddi.http.outbound.engine`, `eddi.http.outbound.default.{connect-timeout,total-timeout,protocol,tls.configuration-name}`, `eddi.http.outbound.proxy.{host,port,non-proxy-hosts,credentials}`. The connect timeout falls back to `httpClient.connectTimeoutInMillis`, so a tuned deployment keeps its value.

**Design decisions**

- Only the `default` profile has keys. The others get theirs in the phase that moves their callers, so no documented setting is a silent no-op in the meantime.
- **`tls.named-groups` was dropped from this phase.** `java.net.http.HttpClient` copies the SSL parameters it is given and discards `namedGroups`: `client.sslParameters().getNamedGroups()` comes back `null`. A key would therefore be a no-op. PQC plan PQ-3 needs another route: an `SSLContext` wrapper that sets groups per engine, the global `jdk.tls.namedGroups`, or the Vert.x engine.
- `max-connections-per-host` was deferred to the Vert.x engine (Phase 4), because the JDK client has no per-host pool limit.
- `OutboundBlockedException` extends `IllegalArgumentException` and `OutboundTimeoutException` extends `HttpTimeoutException`, so every existing `catch` keeps working.
- The plan's table maps `send*` to `NONE`, but what `send` actually did was trust the initial URL and fully validate every redirect hop. That is `SsrfPolicy.HOPS_ONLY`, a policy the plan did not list.
- The event-loop guard was enabled after an audit: the only `Uni`/`@NonBlocking` code in `src/main/java` is `NamelessPrincipalAugmentor`, which makes no outbound call.
- Mutation-checked: removing caller-derived stripping, the `VERTX_COMPAT` RETURN, the redirect-hop check, the FAIL-mode cap probe and the redirect-body cap each fails a test.

**Next:** Phase 2 moves the callers (`OAuthTokenClient`, `SafeHttpPageFetcher`, `AttachmentForwarder`, the tools, `SlackWebApiClient`, `A2AToolProviderManager`, `RemoteApiResourceSource`) onto `OutboundHttp` and retires `SafeHttpClient`.
