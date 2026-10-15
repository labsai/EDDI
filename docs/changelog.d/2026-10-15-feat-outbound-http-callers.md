## Outbound HTTP callers moved onto the facade; `SafeHttpClient` retired — consolidation phase 2 (2026-10-15)

**Repo:** EDDI · **Branch:** `feat/outbound-http-callers` (stacked on `feat/outbound-http-facade`, labsai/EDDI#1042) · **Plan:** `planning/http-client-consolidation-plan.md`, Phase 2

**What changed**

Every EDDI-owned caller now sends through `OutboundHttp`, with its existing caps, timeouts and redirect rules unchanged:

| Caller | SSRF / redirects | Body |
|---|---|---|
| `WeatherTool`, `WebSearchTool` | `HOPS_ONLY` (fixed hosts), JDK-safe redirects | `bytes(1 MiB / 2 MiB, TRUNCATE)`; a truncated answer is refused as before |
| `PdfReaderTool`, `WebScraperTool` | the tool's own URL check, then `HOPS_ONLY` | `bytes(eddi.tools.*, TRUNCATE)` |
| `AttachmentForwarder` | `STRICT` | `bytes(per-file cap, TRUNCATE)` |
| `SafeHttpPageFetcher` (crawler) | `STRICT` | `bytes(cap, TRUNCATE)`; its hand-written bounded read and watchdog are gone (identical in the facade), and so is its `IRuntime` dependency |
| `OAuthTokenClient` | `NONE` (the credential-endpoint allowlist is the gate), `NEVER` | `string(1 MiB, FAIL)`. **New cap**: a token answer was unbounded before; anything over 1 MiB is now a transient `TOKEN_ENDPOINT_UNAVAILABLE` |
| `SlackWebApiClient` | `NONE` (fixed host), `NEVER` | `string(1 MiB, FAIL)`; its private `HttpClient` is gone. **Now bounded in time** — it had no request timeout at all, and gets the facade's 15 s per hop |
| `A2AToolProviderManager` | `STRICT` or `METADATA_ONLY` per `eddi.security.ssrf-protection.enabled`, `NEVER` | `bytes(1 MiB, TRUNCATE)`; its lazy private client and its `a2a-body-watchdog` thread are gone |
| `RemoteApiResourceSource` (+ the two static helpers) | new profile `INTERNAL_PEER`: `NONE` (`SourceUrlValidator` stays the gate), `NEVER` | JSON `string(eddi.backup.sync.max-response-bytes, FAIL)` with the old refusal message; archives `bytes(256 MiB, FAIL)` |

- **`SafeHttpClient` is deleted** (internal API, no callers left), with `CappedStringBodyHandler` and the per-source client lifecycle (`close()`, `configure()`, the owned-client flag): sources share the application's facade. `BoundedBodyReader` moved into `engine/httpclient/outbound` as package-private.
- `RestImportService` gets the facade by setter injection, lazily falling back to a standalone one outside CDI, as `A2AToolProviderManager` does.
- AGENTS.md (§4.2 table, §4.4 Tool Security and its sample, Key Files), README, `architecture.md` (SSRF layers and sample), `connections.md`, `code-review-standards.md`, `security-review.md`, `a2a-protocol.md`, `configuration-reference.md` and `metrics.md` now name `OutboundHttp`.
- The `// codeql[java/ssrf]` suppressions moved with the sinks: they now sit on the `http.send(...)` calls in `RemoteApiResourceSource` (and its `sendJson` helper).

**Tests**

- The 22 server-backed `SafeHttpClient` tests were ported to `OutboundHttpServerTest` against the facade on the real JDK engine (a no-op `hopValidator` replaces the Mockito spy that let a redirect reach loopback). Their subjects, and a new cross-origin `Authorization` case, are all still covered over real sockets.
- New test helpers in `engine/httpclient/outbound` (test sources): `TestOutbound` (standalone facade, facade over any engine, canned responses), `ScriptedEngine` (records hops, replays answers), and `JdkClientEngine` (routes facade hops into a mocked JDK `HttpClient`). The last one let roughly 200 remote-sync test stubs stay as they were while the code under test runs through the real facade.
- The crawler, OAuth, A2A wiring and new Slack tests run the real facade over a scripted engine rather than mocking it. New: a loopback crawl seed is refused before anything is sent; an A2A peer's 307 and a Slack 302 are never followed; a sync source declaring a body over `eddi.backup.sync.max-response-bytes` is refused before it is read (the old size tests only simulated the refusal in a stub, so lifting the cap broke none of them).
- Mutation-checked: dropping `NEVER` from OAuth, A2A, Slack or remote sync, weakening the crawler to `HOPS_ONLY`, or lifting the remote-sync size cap each fails a test.

**Design decisions**

- `internal-peer` has its own connection pool but no configuration keys yet. Remote sync had nothing tunable, so it uses the `default` settings, and the configuration reference says so.
- Pass-through, not rewrite: every caller keeps its old checks (the tools' own `validateUrl`, A2A's pre-credential check, `SourceUrlValidator`). The facade's policy is added on top.

**Next:** Phase 3, the langchain4j adapter: shared LLM provider pools, plus `llm-provider` keys.
