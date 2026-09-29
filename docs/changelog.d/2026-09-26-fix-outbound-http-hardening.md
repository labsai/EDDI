## 🔒 fix(http): one deadline over the whole exchange, discarded redirect bodies, bounded search/weather answers, weather key kept from the model (2026-09-26)

**Repo:** EDDI (`fix/outbound-http-hardening`)

Outbound fetches made on a model's or a peer's behalf could be held open, or made to exhaust the heap, by the server they fetched from. Most of that was fixed independently on main by #862 (see [its entry](../changelog.d/2026-09-26-fix-security-ssrf-outbound.md)); this branch now carries only what #862 left open.

### What changed and why

- **One wall-clock deadline covers the whole exchange, body included (H16, time).** Main's [`SafeHttpClient`](../../src/main/java/ai/labs/eddi/engine/httpclient/SafeHttpClient.java) consulted its budget only *between* redirect hops. The JDK's per-request timeout stops counting when the response headers arrive, so a server that sent headers and then trickled the body held the call open for as long as it liked. Every send path (`send`, `sendValidated`, `sendNoRedirect`, `sendValidatedNoRedirect`, and the `send*Bounded` pair) now runs through `sendAsync(...).get(remaining)` against one deadline and cancels the exchange when it passes.
  - The budget is `max(3 × connect timeout, request timeout)`: 30 s by default, longer for a caller that asked for longer, such as an A2A peer configured for 120 s.
  - Async failures keep their exception type. The caller's stack frames are attached as a suppressed `CallerFrames`.
  - The timeout message names scheme, host, port and path only. A query string can carry a key (`key=`, `appid=`), and the tools hand a failed call's message to the model.
  - A streaming handler (`ofInputStream`) completes when the headers arrive, so the deadline covers its headers only. The `send*Bounded` paths already bound the body with main's `BoundedBodyReader` (cap plus a deadline of 4 × the request timeout).
- **Redirect bodies are discarded (R8).** A 3xx body used to go to the caller's handler. On the `send*Bounded` paths that was an `InputStream` per hop that nothing ever saw or closed, holding the hop's connection. For `ofString` it was a body buffered whole, however large. It now goes to a discarding subscriber that reads at most 64 KiB, or nothing when the declared `Content-Length` is larger, then drops the connection and lets the redirect proceed. The caller's handler only ever sees the final response. `sendNoRedirect` still hands the 3xx body to its caller, which decides what a redirect means.
- **The search and weather answers are bounded (H16, memory).** `WebSearchTool` (Google, DuckDuckGo, Wikipedia) and `WeatherTool` were the two callers #862 left on an unbounded `ofString`. They now read through main's `SafeHttpClient.sendBounded`, at 2 MiB and 1 MiB. A truncated answer is refused rather than parsed as a JSON fragment.
- **`WeatherTool` no longer hands the operator's key to the model (H17).**
  - `units` went into the URL verbatim. A value with a space in it made `URI.create` throw with the whole URL, `appid=<key>` included, as its message. That message was returned as the tool's answer and logged with its stack trace. `units` is now an allowlist (`metric`/`imperial`/`standard`, case-insensitive, blank means metric), and anything else gets a plain error before any request is built.
  - The key is URL-encoded into the request.
  - Every error message passes through an `appid=` redactor before it reaches the model or the log, and the log line no longer carries the throwable.
  - Incidental fix: `standard` (Kelvin) is no longer labelled °F.
- **The NAT64 local-use prefix `64:ff9b:1::/48` is blocked whole (M-T1).** Main unwraps its trailing 32 bits, as for the well-known `/96`. RFC 8215 lets a network use any RFC 6052 prefix length inside the `/48`, though. Behind a `/48` translator `64:ff9b:1:7f00:0:100:808:808` is 127.0.0.1, and it passed as 8.8.8.8. [`security.md`](../security.md#private--internal-ip-blocking) now lists the ranges main actually blocks: it had still claimed only IPv4-mapped addresses were unwrapped.

### Reconciled with main (#862)

Main landed an independent fix for most of the same findings while this branch was open. Where both implemented the same thing, main's version was kept and this branch's copy removed:

- Bounded downloads use main's `sendBounded`/`sendValidatedBounded` and `BoundedBodyReader`, with the same property names (`eddi.tools.web-scraper.max-response-bytes`, `eddi.tools.pdf-reader.max-download-bytes`). This branch's handler-level `BoundedBodyHandlers` is gone. The PDF and attachment downloads keep main's timeouts: the body gets 4 × the request timeout, so the 120 s requests this branch added are not needed.
- The A2A client stays on main's raw `HttpClient` with a bounded body read, instead of moving to `SafeHttpClient.sendNoRedirect`.
- Inline OpenAPI `$ref` inspection is main's `rejectUnsafeInlineRefs`, which also fails closed. Two of main's decisions differ from this branch's: `http(s)` references stay allowed, guarded by `setSafelyResolveURL`, and a schema property literally named `$ref` is refused. This branch's [`McpApiToolBuilderExternalRefTest`](../../src/test/java/ai/labs/eddi/engine/mcp/McpApiToolBuilderExternalRefTest.java) is kept against main's guard. It covers a 3.3M-character JSON spec, with a plain and with an escaped key, an unreadable spec, remote specs not resolving `file:` references, and local references still resolving. All of these pass on main unchanged.
- `UrlValidationUtils` is main's. It adds Teredo unwrapping, and makes two decisions that override this branch's: `198.18.0.0/15` **is** blocked, and of `240.0.0.0/4` only `255.255.255.255` is. This branch had left 198.18/15 open for fake-IP DNS proxies (Clash, Surge) and had blocked all of 240/4.
- The startup validation this branch added for the size limits is dropped with it: main falls back to the default for a non-positive value.

**Review round (2026-09-29):**

- The always-on metadata refusal, which also runs where SSRF protection is off, now decodes every RFC 6052 layout inside `64:ff9b:1::/48`: /48, /56, /64 and /96. A translated `169.254.169.254` is refused wherever the translator puts it. The /48 as a whole stays blocked only by the private-address policy, so a public target behind a local translator still passes when SSRF protection is off.
- `WebSearchTool` URL-encodes the Google API key and `cx`. A failure message that names the request URI has its `key=` value redacted before it is logged or returned to the model, and the throwable is no longer logged.

**Files:** `SafeHttpClient.java`, `WebSearchTool.java`, `WeatherTool.java`, `UrlValidationUtils.java`, `docs/security.md`. Tests: `SafeHttpClientBodyBoundsTest`, `UrlValidationUtilsEmbeddedIPv4Test`, `McpApiToolBuilderExternalRefTest`, plus cases in `WebSearchToolTest` and `WeatherToolExtendedTest`.

```decision-log
| 2026-09-29 | One wall-clock deadline, max(3 × connect timeout, request timeout), covers every SafeHttpClient send path including buffered body reads; redirect bodies go to a 64 KiB discarding subscriber | H16/R8 after #862: the per-request timeout stops at the headers, and 3xx bodies reached the caller's handler (an unclosed stream per hop on the bounded paths) | Leaving buffered paths to per-caller bounds; discarding redirect bodies without a cap (downloads a huge courtesy page in full) |
| 2026-09-29 | Reconciled fix/outbound-http-hardening with #862 by keeping main's implementation wherever both fixed the same finding | Two parallel fixes; main's shipped first and is what other code builds on | Keeping the PR's handler-level BoundedBodyHandlers alongside main's sendBounded (two mechanisms for one job) |
| 2026-09-29 | Block the NAT64 local-use prefix 64:ff9b:1::/48 whole instead of unwrapping its trailing 32 bits | RFC 8215 allows any RFC 6052 prefix length inside it, so the IPv4 address need not be at the end | Unwrapping per prefix length (the length is a per-network choice EDDI cannot see) |
```
