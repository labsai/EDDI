## 🔒 fix(http): bound outbound response bodies, close the NAT64/6to4 SSRF gap, stop the weather tool leaking its key (2026-09-26)

**Repo:** EDDI (`fix/outbound-http-hardening`)

Outbound fetches made on a model's or a peer's behalf could be held open, or made to exhaust the heap, by the server they fetched from. Four smaller defects in the same code rode along.

### What changed and why

- **Response bodies are bounded while they are read (H16).** A new [`BoundedBodyHandlers`](../../src/main/java/ai/labs/eddi/engine/httpclient/BoundedBodyHandlers.java) refuses a declared `Content-Length` above its limit before reading a byte. If the body streams past the limit instead, it cancels the subscription, which closes the connection. Both cases fail the send with `ResponseTooLargeException`, an `IOException`. Every caller named in the finding used to buffer the whole body and check the size afterwards. For a body of several GB that check never ran, because the `OutOfMemoryError` came first, and no `catch (Exception)` sees it. The callers now pass a bounded handler:
  - `WebScraperTool`: `eddi.tools.web-scraper.max-response-bytes`, default 5 MiB.
  - `PdfReaderTool`: `eddi.tools.pdf-reader.max-download-bytes`, default 25 MiB. It no longer writes a temp file. The file was unbounded (disk exhaustion), and the tool read it straight back into memory anyway.
  - `AttachmentForwarder`: the existing `eddi.attachments.max-forward-bytes`, applied to the download itself.
  - `A2AToolProviderManager`: the existing 1 MiB, now measured in bytes rather than chars.
  - `WebSearchTool`: 2 MiB. `WeatherTool`: 1 MiB.
- **One wall-clock deadline covers the whole exchange, body included (H16).** [`SafeHttpClient`](../../src/main/java/ai/labs/eddi/engine/httpclient/SafeHttpClient.java) used to consult its budget only *between* redirect hops. The JDK's per-request timeout stops counting when the response headers arrive, so a server that sent headers and then trickled the body held the tool call open for as long as it liked. Every send path (`send`, `sendValidated`, `sendNoRedirect`, `sendValidatedNoRedirect`) now runs through `sendAsync(...).get(remaining)` against one deadline, and cancels the exchange when the deadline passes. The budget is `max(3 × connect timeout, request timeout)`: 30 s by default, or longer for a caller that asked for longer, such as an A2A peer configured for 120 s. A streaming handler (`ofInputStream`) completes when the headers arrive, so its later reads stay the caller's job; `SafeHttpPageFetcher` already bounds them.
- **Redirect bodies are discarded (R8).** A 3xx body used to go to the caller's handler. For an `ofInputStream` caller that meant an `InputStream` per hop that nothing ever saw or closed, holding the hop's connection. The body now goes to a discarding subscriber, and the caller's handler only ever sees the final response.
- **The A2A client goes through `SafeHttpClient` (MCP/A2A item).** `A2AToolProviderManager` keeps its lazy construction but now holds a `SafeHttpClient`, used through `sendNoRedirect`. The manager still validates each target by its own SSRF setting and still follows no redirect. It now also gets the deadline and the bounded body.
- **`WeatherTool` no longer hands the operator's key to the model (H17).**
  - `units` went into the URL verbatim. A value with a space in it made `URI.create` throw with the whole URL, `appid=<key>` included, as its message, and that message was returned as the tool's answer and logged with its stack trace. `units` is now an allowlist (`metric`/`imperial`/`standard`, case-insensitive, blank means metric). Anything else gets a plain error before any request is built.
  - The key is URL-encoded into the request.
  - Every error message passes through an `appid=` redactor before it reaches the model or the log, and the log line no longer carries the throwable.
  - Incidental fix: `standard` (Kelvin) is no longer labelled °F.
- **The SSRF address check unwraps IPv6 forms that carry an IPv4 address, and blocks the reserved IPv4 ranges (M-T1).** The JDK predicates look only at the IPv6 address itself. On a NAT64 network, `64:ff9b::7f00:1` is 127.0.0.1 once the gateway translates it, and it passed as public; so did `2002:7f00:1::` through a 6to4 relay. [`UrlValidationUtils`](../../src/main/java/ai/labs/eddi/modules/llm/tools/UrlValidationUtils.java) now judges the embedded IPv4 address of:
  - IPv4-mapped addresses;
  - IPv4-compatible `::/96`;
  - NAT64 `64:ff9b::/96`;
  - 6to4 `2002::/16`.

  It blocks the NAT64 local-use prefix `64:ff9b:1::/48` whole, since where the IPv4 address sits inside it is a per-network choice. It also blocks 240/4 (including broadcast), 198.18/15 and 192.0.0.0/24. The always-on cloud-metadata refusal unwraps the same forms. The class Javadoc claimed that "6to4 would be caught if private"; that was false and is corrected. `UrlValidationUtilsDeepBranchTest` had pinned 240.0.0.1 as *allowed* and now expects it blocked. [`security.md`](../security.md#private--internal-ip-blocking) lists the new ranges.
- **Inline OpenAPI specs may only reference themselves (M-S2).** `McpApiToolBuilder.parseSpec` parses inline specs with `setResolve(true)` and no location. swagger-parser then fetched every other `$ref` on its own: `./secret.yaml` and absolute paths from the server's filesystem, `http://127.0.0.1/…` over the network. What it read surfaced in the generated httpcalls config. A spec containing any `$ref` that does not start with `#` is now refused, with a message naming the reference. The finding proposed `setResolve(false)` instead. That was rejected because the builder reads component parameters and request bodies as resolved objects, so a spec using `$ref: '#/components/parameters/Limit'` would have silently lost the parameter. Local references need no I/O, so they stay resolved.

### Decisions

- **Limits live in the handler, not a new `SafeHttpClient` method.** Callers keep `send`/`sendValidated`, and the dozens of tests that mock those with `any()` handlers keep working. The handler is also usable with any JDK client.
- **The deadline floor is 3 × connect timeout, the value the between-hops check already used.** No caller's effective budget shrinks, and a caller that set a longer request timeout gets that instead. Reading a body that is still arriving after 30 s is now a failure for a caller with the default 15 s timeout. That is the intended change.
- **Two new operator properties, not six.** The page and PDF limits are the ones an operator plausibly tunes (a large intranet page, a scanned PDF). The search, weather and A2A limits bound fixed-shape API answers and stay constants.

### Not done

- **MCP client transport.** `StreamableHttpMcpTransport` is langchain4j's long-lived streaming transport with its own HTTP client. `SafeHttpClient` is request/response and cannot stand in for it. It stays as is.
- **Remote OpenAPI specs.** Specs fetched by URL keep resolving their `$ref`s, because multi-file specs are legitimate there and internal hosts are deliberately allowed for editors. A remote spec pointing a `$ref` at a `file:` URL is not covered by this change.

**Files:** `BoundedBodyHandlers.java` (new), `SafeHttpClient.java`, `WebScraperTool.java`, `PdfReaderTool.java`, `WebSearchTool.java`, `WeatherTool.java`, `AttachmentForwarder.java`, `A2AToolProviderManager.java`, `UrlValidationUtils.java`, `McpApiToolBuilder.java`, `docs/security.md`, `docs/configuration-reference.md`. Tests: `BoundedBodyHandlersTest`, `BodyHandlerProbe` (test helper), `SafeHttpClientBodyBoundsTest`, `UrlValidationUtilsEmbeddedIPv4Test`, `McpApiToolBuilderExternalRefTest`, plus cases in `AttachmentForwarderTest`, `WebScraperToolExtendedTest`, `PdfReaderToolTest`, `WeatherToolExtendedTest`, `A2ATaskCredentialWiringTest`.

```decision-log
| 2026-09-26 | Response-size limits are enforced by a bounded BodyHandler passed to the existing SafeHttpClient methods, and one wall-clock deadline covers every send path including the body read | H16: outbound fetches buffered whole bodies before checking size (OOM) and the per-request timeout stopped at the headers (trickled bodies held tool calls open) | New sendBounded(...) methods on SafeHttpClient (would break every mock of send/sendValidated); setResolve(false) for inline OpenAPI specs (drops local component refs) |
```
