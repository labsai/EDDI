## 🔒 fix(outbound): one cross-origin header allow-list; redirect errors without query strings (2026-11-07)

**Repo:** EDDI (`fix/outbound-header-allowlist`)

### What changed and why

**One allow-list for cross-origin redirect hops (SEC-5).** EDDI had two rules for
the headers a redirect to another origin may carry. The httpcalls client
(`HttpClientModule`) kept only a short allow-list of content-negotiation headers,
because a credential can sit in any header name. The `OutboundHttp` facade used a
deny-list instead: ten fixed credential names, the W3C trace context and the
headers a request had marked as caller-derived. Every other header was replayed, so
a request carrying `Ocp-Apim-Subscription-Key` or `X-Subscription-Token` that met a
`302` to another host sent the key there. No facade caller follows redirects with
such a header today, but `Profile.APICALLS` follows five hops and httpcalls are
planned to move onto the facade.

- `OutboundHttp` now owns `CROSS_ORIGIN_SAFE_HEADERS` (`Accept`,
  `Accept-Encoding`, `Accept-Language`, `User-Agent`, `Content-Type`,
  `Content-Language`, `Cache-Control`, `Pragma`), moved from `HttpClientModule`.
  `redirectHeaders` keeps only those on a cross-origin hop. Credentials and trace
  context drop out with everything else. Same-origin hops still keep every header.
- `OutboundHttp.SENSITIVE_HEADERS` is removed. So is
  `OutboundRequest.Builder.callerDerivedHeader` with its record component: no
  production code called it, and the allow-list already covers what it marked.
- `HttpClientModule.stripCredentialsIfCrossOrigin` reads the facade's list. Its
  explicit removal of the fixed names and the trace headers was redundant with the
  allow-list sweep and is gone.

**Redirect errors without the query string (SEC-6).** "Too many redirects … for
URL:" and "Redirect … without Location header from:" quoted the full URI, query
included. A tool hands these messages to the model and to the log, and an API key
in `?key=` went with them. Both now use `withoutQuery`, like every other message
the facade builds. The per-tool redaction in `WeatherTool` and `WebSearchTool`
stays: the engines rethrow transport messages the facade does not build.

### Behaviour change

A facade request that follows a redirect to another origin now sends only the
headers listed above on that hop. Before this change it also sent any custom header
not on the deny-list. Same-origin redirects are unchanged, and httpcalls behave
exactly as before.

**Files:**
[`OutboundHttp.java`](../../src/main/java/ai/labs/eddi/engine/httpclient/outbound/OutboundHttp.java),
[`OutboundRequest.java`](../../src/main/java/ai/labs/eddi/engine/httpclient/outbound/OutboundRequest.java),
[`HttpClientModule.java`](../../src/main/java/ai/labs/eddi/engine/httpclient/bootstrap/HttpClientModule.java),
[`OutboundHttpTest.java`](../../src/test/java/ai/labs/eddi/engine/httpclient/outbound/OutboundHttpTest.java)
